package dev.pschmitt.jellyfin.backup

import android.content.Context
import android.net.Uri
import dev.pschmitt.jellyfin.api.pvr.PvrClientConfigFull
import dev.pschmitt.jellyfin.api.pvr.PvrCredentialKeys
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.models.AutoDownloadRuleDto
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.settings.domain.models.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Builds, encodes/decodes, and restores backups. Not `@Inject`-constructed directly (`data` module
 * has no Hilt setup, matching how `JellyfinRepositoryImpl` etc. are wired) - see
 * core/di/BackupModule.kt for the Hilt `@Provides` binding.
 *
 * [resolvePvrConfig] resolves the active profile's effective Sonarr/Radarr/Seerr config from
 * `core`'s `PvrConfigResolver` - passed in as a plain lambda (rather than depending on
 * `PvrConfigResolver` directly) because that type lives in `core`, which depends on `data`, not the
 * other way around. Same pattern as `CalendarRepositoryImpl`'s `resolveSonarrConfig`.
 *
 * [putSecret] writes `SecureCredentialStore` - also a lambda, for the same reason.
 *
 * [reconcileProfiles] runs `ProfileMigrationRunner.reconcileAfterExternalRestore()` (also `core`,
 * also a lambda for the same reason) after [restore] finishes writing servers/users/prefs/secrets -
 * without it, restoring a backup taken before this device's one-time Profiles migration already ran
 * (e.g. onto a fresh install, which runs that migration as a no-op before the user ever taps
 * "Restore from backup") would leave the newly-restored users with no Profile and their PVR
 * credentials stuck in dead legacy keys nothing reads anymore.
 */
class BackupManager(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val resolvePvrConfig: (PvrService) -> PvrClientConfigFull? = { null },
    private val putSecret: (key: String, value: String) -> Unit = { _, _ -> },
    private val reconcileProfiles: () -> Unit = {},
) {
    // ignoreUnknownKeys - so a future field addition (e.g. from a newer app version's backup)
    // doesn't hard-fail decoding on this app version; matches every other Json{} instance in the
    // repo, which already sets this (this one had been the one exception).
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    suspend fun buildBackup(): BackupEnvelope =
        withContext(Dispatchers.IO) {
            val servers =
                database.getAllServersWithAddressesAndUsers().map {
                    BackupServer(server = it.server, addresses = it.addresses, users = it.users)
                }
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            BackupEnvelope(
                createdAt = System.currentTimeMillis(),
                servers = servers,
                autoDownloadRules = database.getAllAutoDownloadRules(),
                preferences = dumpPreferences(),
                downloadedItems = buildDownloadedItemsManifest(),
                secrets = dumpSecrets(),
                appVersionName = packageInfo.versionName ?: "",
                appVersionCode = packageInfo.longVersionCode,
                packageId = context.packageName,
            )
        }

    suspend fun writeBackup(envelope: BackupEnvelope, uri: Uri, password: String?) {
        withContext(Dispatchers.IO) {
            val plainBytes =
                json.encodeToString(BackupEnvelope.serializer(), envelope).toByteArray()
            val bytes = BackupCrypto.encode(plainBytes, password)
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Could not open $uri for writing")
        }
    }

    /**
     * @throws BackupCrypto.PasswordRequiredException, BackupCrypto.WrongPasswordException,
     *   UnsupportedBackupVersionException
     */
    suspend fun readBackup(uri: Uri, password: String?): BackupEnvelope =
        withContext(Dispatchers.IO) {
            val bytes =
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not open $uri for reading")
            val plainBytes = BackupCrypto.decode(bytes, password)
            val envelope = json.decodeFromString(BackupEnvelope.serializer(), String(plainBytes))
            if (envelope.version > CURRENT_VERSION) {
                throw UnsupportedBackupVersionException(envelope.version, envelope.appVersionName)
            }
            envelope
        }

    fun isBackupEncrypted(uri: Uri): Boolean {
        val bytes =
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        return BackupCrypto.isEncrypted(bytes)
    }

    /**
     * Deliberately does NOT insert [BackupEnvelope.autoDownloadRules] here, unlike servers/
     * users/preferences - those rules drive [dev.pschmitt.jellyfin.work.AutoDownloadWorker]
     * automatically, so importing them unconditionally would start downloading monitored
     * episodes/movies again even if the user answers "No" to the separate "redownload?" prompt
     * shown after this returns. They're carried on [RestoreSummary] instead and only actually
     * written by [applyAutoDownloadRules], which [RestoreBackupViewModel] calls solely from the
     * "Yes" branch of that prompt.
     */
    suspend fun restore(envelope: BackupEnvelope): RestoreSummary =
        withContext(Dispatchers.IO) {
            for (backupServer in envelope.servers) {
                database.insertServer(backupServer.server)
                for (address in backupServer.addresses) database.insertServerAddress(address)
                for (user in backupServer.users) database.insertUser(user)
            }
            restorePreferences(envelope.preferences)
            // Writes into the legacy flat SecureCredentialStore keys dumpSecrets() labels its
            // export with (see PvrCredentialKeys.legacyApiKey()) - reconcileProfiles() below reads
            // these same legacy keys (plus the plain prefs restorePreferences() just wrote) and
            // folds them into the main profile's real PvrServiceConfig + namespaced secrets.
            for ((key, value) in envelope.secrets) putSecret(key, value)
            reconcileProfiles()

            RestoreSummary(
                serversRestored = envelope.servers.size,
                usersRestored = envelope.servers.sumOf { it.users.size },
                rulesRestored = envelope.autoDownloadRules.size,
                downloadedItems = envelope.downloadedItems,
                autoDownloadRules = envelope.autoDownloadRules,
            )
        }

    suspend fun applyAutoDownloadRules(rules: List<AutoDownloadRuleDto>) =
        withContext(Dispatchers.IO) { for (rule in rules) database.insertAutoDownloadRule(rule) }

    private fun buildDownloadedItemsManifest(): List<BackupDownloadedItem> {
        val items = mutableListOf<BackupDownloadedItem>()
        for (server in database.getAllServersSync()) {
            for (movie in database.getMoviesByServerId(server.id)) {
                if (database.getSources(movie.id).any { it.type == JollyfinSourceType.LOCAL }) {
                    items.add(
                        BackupDownloadedItem(
                            serverId = server.id,
                            itemId = movie.id.toString(),
                            itemKind = BackupDownloadedItemKind.MOVIE,
                        )
                    )
                }
            }
            for (episode in database.getEpisodesByServerId(server.id)) {
                if (database.getSources(episode.id).any { it.type == JollyfinSourceType.LOCAL }) {
                    items.add(
                        BackupDownloadedItem(
                            serverId = server.id,
                            itemId = episode.id.toString(),
                            itemKind = BackupDownloadedItemKind.EPISODE,
                        )
                    )
                }
            }
        }
        return items
    }

    /**
     * Reads the active profile's resolved secrets per service - MUST go through [resolvePvrConfig]
     * (the profile-aware [dev.pschmitt.jellyfin.pvr.PvrConfigResolver]), not a direct
     * `SecureCredentialStore` read keyed by [PvrCredentialKeys]'s legacy flat constants, since
     * those are stale/unused once a profile has its own namespaced override. Still labels each
     * exported secret with its legacy flat key name (via [PvrCredentialKeys.legacyApiKey] and
     * friends) purely as the envelope's map key - [restore] writes it straight back with that same
     * label, seeded from whichever profile was active at export time. Mirrors
     * [dev.pschmitt.jellyfin.qrsetup.QrConfigManager.putPvrFields]'s secrets half.
     */
    private fun dumpSecrets(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (service in PvrService.entries) {
            val config = resolvePvrConfig(service) ?: continue
            config.apiKey?.let { result[PvrCredentialKeys.legacyApiKey(service)] = it }
            config.httpHeaders?.let { result[PvrCredentialKeys.legacyHttpHeaders(service)] = it }
            config.basicAuthUsername?.let {
                result[PvrCredentialKeys.legacyBasicAuthUsername(service)] = it
            }
            config.basicAuthPassword?.let {
                result[PvrCredentialKeys.legacyBasicAuthPassword(service)] = it
            }
        }
        return result
    }

    private fun dumpPreferences(): Map<String, PrefValue> {
        val result = mutableMapOf<String, PrefValue>()
        for ((key, value) in appPreferences.sharedPreferences.all) {
            // Ephemeral in-flight state, not a user setting - must never round-trip through a
            // backup. If it were captured here, restoring an old backup could resurrect a stale
            // pending-download signal that overrides whatever the user answers in the restore
            // flow's own "redownload?" prompt (see RestoreBackupViewModel.OnRedownloadNo).
            if (key == appPreferences.pendingRestoreDownloads.backendName) continue
            // A per-physical-device identity (RemoteConfigRepository's device registry key), not a
            // user setting - must never round-trip through a backup either. Restoring it onto a
            // different physical device would make that device impersonate whichever device the
            // backup was taken on in the shared cross-device registry: both would then heartbeat
            // under the same id, each one's own "other devices" list would filter the other out as
            // itself (see RemoteConfigRepositoryImpl.listOtherDevices), and the restored device
            // would never show up as a distinct entry to either side.
            if (key == appPreferences.thisDeviceId.backendName) continue
            result[key] =
                when (value) {
                    is Boolean -> PrefValue.BoolValue(value)
                    is Int -> PrefValue.IntValue(value)
                    is Long -> PrefValue.LongValue(value)
                    is Float -> PrefValue.FloatValue(value)
                    is String -> PrefValue.StringValue(value)
                    is Set<*> -> PrefValue.StringSetValue(value.filterIsInstance<String>().toSet())
                    else -> continue
                }
        }
        // Overrides whatever's in the raw SharedPreferences dump above: sonarrEnabled/sonarrBaseUrl
        // (and the radarr/seerr equivalents) are dead legacy fields the modern per-profile Settings
        // UI no longer writes, so they'd otherwise export as stale false/null even when the active
        // profile has a working Sonarr/Radarr/Seerr config - which made "Pending downloads" (and
        // queue polling/calendar) silently disappear after every restore, since only the API key
        // secret (dumpSecrets() above) reflected the real config. Mirrors
        // [dev.pschmitt.jellyfin.qrsetup.QrConfigManager.putPvrFields]'s plainPrefs half.
        for (service in PvrService.entries) {
            val config = resolvePvrConfig(service)
            result[enabledPreference(service).backendName] =
                PrefValue.BoolValue(config?.enabled == true)
            config?.baseUrl?.let {
                result[baseUrlPreference(service).backendName] = PrefValue.StringValue(it)
            }
        }
        return result
    }

    private fun enabledPreference(service: PvrService): Preference<Boolean> =
        when (service) {
            PvrService.SONARR -> appPreferences.sonarrEnabled
            PvrService.RADARR -> appPreferences.radarrEnabled
            PvrService.SEERR -> appPreferences.seerrEnabled
        }

    private fun baseUrlPreference(service: PvrService): Preference<String?> =
        when (service) {
            PvrService.SONARR -> appPreferences.sonarrBaseUrl
            PvrService.RADARR -> appPreferences.radarrBaseUrl
            PvrService.SEERR -> appPreferences.seerrBaseUrl
        }

    private fun restorePreferences(preferences: Map<String, PrefValue>) {
        val editor = appPreferences.sharedPreferences.edit()
        for ((key, value) in preferences) {
            // Belt-and-suspenders for backups written before dumpPreferences() started excluding
            // this key: never let an *older* backup reintroduce the device-identity collision on
            // restore either.
            if (key == appPreferences.thisDeviceId.backendName) continue
            when (value) {
                is PrefValue.BoolValue -> editor.putBoolean(key, value.value)
                is PrefValue.IntValue -> editor.putInt(key, value.value)
                is PrefValue.LongValue -> editor.putLong(key, value.value)
                is PrefValue.FloatValue -> editor.putFloat(key, value.value)
                is PrefValue.StringValue -> editor.putString(key, value.value)
                is PrefValue.StringSetValue -> editor.putStringSet(key, value.value)
            }
        }
        // commit() rather than apply() - the caller restarts the whole process right after a
        // successful restore (to rebuild JellyfinApi and other @Singleton state from the
        // now-current server/user), which would otherwise race apply()'s async disk write and
        // could lose the just-restored preferences on the very next cold start.
        editor.commit()
    }

    private companion object {
        // The highest BackupEnvelope.version this build of the app knows how to restore. Bump
        // this (and add the actual migration logic in restore()) the day a real format change
        // happens - there's only ever been one format so far, so there's nothing to migrate yet.
        const val CURRENT_VERSION = 1
    }
}

/**
 * Thrown by [BackupManager.readBackup] for a backup written by a newer app version whose format
 * this build doesn't understand yet - a clear message instead of a raw deserialize crash.
 * [writtenByAppVersion] is blank for backups from before this field existed (impossible in
 * practice, since old backups can only ever have [BackupEnvelope.version] <= the version this build
 * already knows).
 */
class UnsupportedBackupVersionException(backupVersion: Int, writtenByAppVersion: String) :
    Exception(
        "This backup was created by a newer version of the app" +
            (writtenByAppVersion.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            " and can't be restored here (backup format $backupVersion)"
    )
