package dev.pschmitt.jellyfin.profile

import dev.pschmitt.jellyfin.api.pvr.PvrCredentialKeys
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.models.Profile
import dev.pschmitt.jellyfin.models.PvrServiceConfig
import dev.pschmitt.jellyfin.security.SecureCredentialStore
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [run] is a one-time upgrade backfill run from BaseApplication.onCreate(), before anything else
 * touches profiles/PVR config: creates a Profile per pre-existing User and a PvrServiceConfig per
 * legacy global Sonarr/Radarr/Seerr setting, so upgrading users land on an equivalent Profiles
 * setup without losing servers, users, downloads, or PVR credentials. Gated by
 * AppPreferences.profilesMigrated so it only ever runs once. A fresh install has zero User rows, so
 * this is a no-op there (the first Profile is created when the first Jellyfin login succeeds, see
 * ProfileRepository.createProfile call sites).
 *
 * [reconcileAfterExternalRestore] covers the case [run] can't: a backup/QR restore happening
 * *after* a fresh install already ran [run] as a no-op, which otherwise leaves the newly-restored
 * users with no Profile and their PVR credentials stuck in dead legacy keys.
 */
@Singleton
class ProfileMigrationRunner
@Inject
constructor(
    private val dao: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val secureCredentialStore: SecureCredentialStore,
) {
    /**
     * One-time-per-key rename migration: the Seerr preference/credential keys used to keep their
     * pre-rebrand "jellyseerr" spelling (see git history) - this copies any value still sitting
     * under an old key into its renamed counterpart. Naturally idempotent (only copies when the new
     * key is still absent and the old one isn't), so unlike [run] it needs no
     * [AppPreferences.profilesMigrated]-style gating flag - safe to call on every cold start, and
     * again from [reconcileAfterExternalRestore] in case a just-applied backup/QR envelope wrote
     * secrets/prefs under the old names (an envelope exported by an older app build). Must run
     * before anything reads [AppPreferences.seerrEnabled]/[AppPreferences.seerrBaseUrl]/
     * [PvrCredentialKeys.SEERR_API_KEY] - see the call site in BaseApplication.onCreate(), ahead of
     * [run] itself.
     */
    fun migrateLegacySeerrKeyNames() {
        val prefs = appPreferences.sharedPreferences
        if (
            !prefs.contains(appPreferences.seerrEnabled.backendName) &&
                prefs.contains(LEGACY_SEERR_ENABLED_KEY)
        ) {
            appPreferences.setValue(
                appPreferences.seerrEnabled,
                prefs.getBoolean(LEGACY_SEERR_ENABLED_KEY, false),
            )
        }
        if (
            !prefs.contains(appPreferences.seerrBaseUrl.backendName) &&
                prefs.contains(LEGACY_SEERR_BASE_URL_KEY)
        ) {
            appPreferences.setValue(
                appPreferences.seerrBaseUrl,
                prefs.getString(LEGACY_SEERR_BASE_URL_KEY, null),
            )
        }
        if (
            !secureCredentialStore.contains(PvrCredentialKeys.SEERR_API_KEY) &&
                secureCredentialStore.contains(PvrCredentialKeys.LEGACY_JELLYSEERR_API_KEY)
        ) {
            secureCredentialStore.getString(PvrCredentialKeys.LEGACY_JELLYSEERR_API_KEY)?.let {
                secureCredentialStore.putStringBlocking(PvrCredentialKeys.SEERR_API_KEY, it)
            }
        }
    }

    fun run() {
        if (appPreferences.getValue(appPreferences.profilesMigrated)) return

        val users = dao.getAllUsers()
        if (users.isEmpty()) {
            appPreferences.setValue(appPreferences.profilesMigrated, true)
            return
        }

        val serverNamesById = dao.getAllServersSync().associate { it.id to it.name }
        val currentServerId = appPreferences.getValue(appPreferences.currentServer)
        val currentUserId = currentServerId?.let { dao.get(it) }?.currentUserId

        val profiles = users.map { user ->
            Profile(
                id = UUID.randomUUID(),
                name = "${user.name}@${serverNamesById[user.serverId] ?: user.serverId}",
                userId = user.id,
                isMain = user.id == currentUserId,
            )
        }

        // Fall back to the first profile if no current server/user was recorded (shouldn't happen
        // on a real upgrade, but a run must always end with exactly one main profile).
        val mainProfile =
            profiles.firstOrNull { it.isMain } ?: profiles.first().also { it.isMain = true }

        val pvrConfigs =
            PvrService.entries.map { service ->
                val config =
                    PvrServiceConfig(
                        id = UUID.randomUUID(),
                        service = service,
                        enabled = appPreferences.getValue(enabledPreference(service)),
                        baseUrl = appPreferences.getValue(baseUrlPreference(service)),
                    )
                when (service) {
                    PvrService.SONARR -> mainProfile.sonarrConfigId = config.id
                    PvrService.RADARR -> mainProfile.radarrConfigId = config.id
                    PvrService.SEERR -> mainProfile.seerrConfigId = config.id
                }
                config
            }

        // Every other profile keeps its FK null, which resolves to "inherit from main" - this is
        // what makes a second profile on the same server see the migrated arr config immediately.
        dao.backfillProfiles(profiles, pvrConfigs)

        for (config in pvrConfigs) {
            copyLegacySecrets(config.service, config.id)
        }

        appPreferences.setValue(appPreferences.currentProfileId, mainProfile.id.toString())
        appPreferences.setValue(appPreferences.profilesMigrated, true)
    }

    /**
     * Idempotent reconciliation for backup/QR restore: unlike [run], NOT gated by
     * [AppPreferences.profilesMigrated] - a restore can insert new User rows and refresh the legacy
     * global PVR prefs/secrets *after* [run] already completed as a no-op on a fresh install (zero
     * users at first launch, before the user taps "Restore from backup"), which is exactly the case
     * [run] alone can't handle: it never runs again, so those restored users would otherwise end up
     * with no Profile and their restored PVR credentials would sit in the legacy flat keys that
     * nothing reads anymore. Creates a Profile for any User that doesn't have one yet, guarantees
     * exactly one profile stays main, and refreshes the main profile's PvrServiceConfig rows +
     * secrets from whatever the restore just wrote into the global AppPreferences fields / legacy
     * SecureCredentialStore keys.
     */
    fun reconcileAfterExternalRestore() {
        migrateLegacySeerrKeyNames()

        val users = dao.getAllUsers()
        if (users.isEmpty()) return

        val existingProfiles = dao.getAllProfiles()
        val usersWithProfile = existingProfiles.map { it.userId }.toSet()
        val serverNamesById = dao.getAllServersSync().associate { it.id to it.name }

        var mainProfile = existingProfiles.firstOrNull { it.isMain }

        val newProfiles =
            users
                .filter { it.id !in usersWithProfile }
                .map { user ->
                    Profile(
                        id = UUID.randomUUID(),
                        name = "${user.name}@${serverNamesById[user.serverId] ?: user.serverId}",
                        userId = user.id,
                        isMain = mainProfile == null,
                    )
                }

        if (mainProfile == null) mainProfile = newProfiles.firstOrNull()
        val main = mainProfile ?: return // unreachable: users isn't empty, so at least one exists

        val pvrConfigs =
            PvrService.entries.map { service ->
                val config =
                    PvrServiceConfig(
                        id = UUID.randomUUID(),
                        service = service,
                        enabled = appPreferences.getValue(enabledPreference(service)),
                        baseUrl = appPreferences.getValue(baseUrlPreference(service)),
                    )
                when (service) {
                    PvrService.SONARR -> main.sonarrConfigId = config.id
                    PvrService.RADARR -> main.radarrConfigId = config.id
                    PvrService.SEERR -> main.seerrConfigId = config.id
                }
                config
            }

        // Always include `main` even when it pre-existed (i.e. isn't in newProfiles) -
        // insertProfile
        // uses REPLACE, so this is an upsert that persists the refreshed PVR config FKs above.
        val profilesToWrite = (newProfiles + main).distinctBy { it.id }
        dao.backfillProfiles(profilesToWrite, pvrConfigs)

        for (config in pvrConfigs) copyLegacySecrets(config.service, config.id)

        // Not just "if null": a backup/QR envelope restores this raw UUID string verbatim (it's an
        // ordinary AppPreferences key, not excluded like thisDeviceId), but Profile rows themselves
        // are never part of the envelope - they're recreated above with brand-new random UUIDs. A
        // restore onto a device that already had a profile (or a fresh install with no prior value)
        // would otherwise leave this pointing at a profile id from the SOURCE device that doesn't
        // exist locally, so every PvrConfigResolver.currentProfile() lookup silently returns null.
        val storedProfileId = appPreferences.getValue(appPreferences.currentProfileId)
        val storedProfileExists =
            storedProfileId
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?.let { dao.getProfile(it) } != null
        // commit(), not the setValue()/apply() default: BackupManager.restore() and
        // QrConfigManager.applyEnvelope() both call this method immediately before force-restarting
        // the process (see RestoreBackupScreen's restartProcess(), which calls
        // Runtime.getRuntime().exit(0) right after startActivity() - not a graceful Activity finish
        // that would let QueuedWork drain pending apply() writes first). An async write here races
        // that exit and can be silently dropped, which is exactly how this bug was first found: the
        // profile got recreated with a new id and the PvrServiceConfig/secrets were restored
        // correctly, but the dropped apply() left currentProfileId pointing at the stale id from
        // the backup's source device on the very next cold start, right back to square one.
        val editor = appPreferences.sharedPreferences.edit()
        if (!storedProfileExists) {
            editor.putString(appPreferences.currentProfileId.backendName, main.id.toString())
        }
        editor.putBoolean(appPreferences.profilesMigrated.backendName, true)
        editor.commit()
    }

    // Copies (never moves) the legacy flat keys - safe to re-run if a crash happens between this
    // and setting profilesMigrated, since copying the same value twice is a no-op. Uses
    // putStringBlocking rather than the default async putString: reconcileAfterExternalRestore()
    // runs inside BackupManager.restore()/QrConfigManager.applyEnvelope(), and both callers force a
    // full process restart immediately after returning (see
    // SecureCredentialStore.putStringBlocking's
    // kdoc) - an async apply() write here would race that restart and could silently lose the
    // just-copied secret, which is exactly the bug this method exists to avoid.
    private fun copyLegacySecrets(service: PvrService, configId: UUID) {
        secureCredentialStore.getString(PvrCredentialKeys.legacyApiKey(service))?.let {
            secureCredentialStore.putStringBlocking(PvrCredentialKeys.apiKey(service, configId), it)
        }
        secureCredentialStore.getString(PvrCredentialKeys.legacyHttpHeaders(service))?.let {
            secureCredentialStore.putStringBlocking(
                PvrCredentialKeys.httpHeaders(service, configId),
                it,
            )
        }
        secureCredentialStore.getString(PvrCredentialKeys.legacyBasicAuthUsername(service))?.let {
            secureCredentialStore.putStringBlocking(
                PvrCredentialKeys.basicAuthUsername(service, configId),
                it,
            )
        }
        secureCredentialStore.getString(PvrCredentialKeys.legacyBasicAuthPassword(service))?.let {
            secureCredentialStore.putStringBlocking(
                PvrCredentialKeys.basicAuthPassword(service, configId),
                it,
            )
        }
    }

    private fun enabledPreference(service: PvrService) =
        when (service) {
            PvrService.SONARR -> appPreferences.sonarrEnabled
            PvrService.RADARR -> appPreferences.radarrEnabled
            PvrService.SEERR -> appPreferences.seerrEnabled
        }

    private fun baseUrlPreference(service: PvrService) =
        when (service) {
            PvrService.SONARR -> appPreferences.sonarrBaseUrl
            PvrService.RADARR -> appPreferences.radarrBaseUrl
            PvrService.SEERR -> appPreferences.seerrBaseUrl
        }

    private companion object {
        const val LEGACY_SEERR_ENABLED_KEY = "pref_pvr_jellyseerr_enabled"
        const val LEGACY_SEERR_BASE_URL_KEY = "pref_pvr_jellyseerr_base_url"
    }
}
