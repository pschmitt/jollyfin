package dev.pschmitt.jellyfin.backup

import dev.pschmitt.jellyfin.models.AutoDownloadRuleDto
import dev.pschmitt.jellyfin.models.Server
import dev.pschmitt.jellyfin.models.ServerAddress
import dev.pschmitt.jellyfin.models.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Everything a backup preserves: saved servers/logins, auto-download rules, every app preference,
 * and a manifest of what was downloaded (not the files themselves - those are multi-GB and
 * trivially re-downloadable from the server; the manifest just lets restore offer to re-queue
 * them).
 */
@Serializable
data class BackupEnvelope(
    val version: Int = 1,
    val createdAt: Long,
    val servers: List<BackupServer>,
    val autoDownloadRules: List<AutoDownloadRuleDto>,
    val preferences: Map<String, PrefValue>,
    val downloadedItems: List<BackupDownloadedItem>,
    // dev.pschmitt.jellyfin.security.SecureCredentialStore entries, keyed by its own key names
    // (PvrCredentialKeys.SONARR_API_KEY/RADARR_API_KEY/SEERR_API_KEY plus each service's HTTP
    // headers/basic-auth) - see BackupManager.buildBackup()/restore(). The enabled toggle and base
    // URL round-trip via [preferences] above (dumpPreferences() resolves them from the active
    // profile's live PvrServiceConfig, not the dead legacy AppPreferences fields); without this
    // field, a restored backup would look "configured" but silently fail to fetch anything, since
    // the API key itself lives in a separate encrypted store dumpPreferences() never touches.
    // Defaults to empty so backups written before this field existed still decode.
    val secrets: Map<String, String> = emptyMap(),
    // Which app build wrote this file - not read by restore() today, but lets a future format
    // change (bumping [version]) tell an old backup apart from a merely-old app instead of
    // guessing from a bare deserialize failure. Defaults let pre-existing backups (written before
    // these fields existed) still decode.
    val appVersionName: String = "",
    val appVersionCode: Long = 0,
    val packageId: String = "",
)

/**
 * A `SharedPreferences` value, tagged with its concrete type so restore can call the matching
 * `SharedPreferences.Editor` putter without having to guess a type back out of plain JSON (where
 * e.g. an Int and a Long are otherwise indistinguishable once round-tripped as a bare number).
 */
@Serializable
sealed interface PrefValue {
    @Serializable data class BoolValue(val value: Boolean) : PrefValue

    @Serializable data class IntValue(val value: Int) : PrefValue

    @Serializable data class LongValue(val value: Long) : PrefValue

    @Serializable data class FloatValue(val value: Float) : PrefValue

    @Serializable data class StringValue(val value: String) : PrefValue

    @Serializable data class StringSetValue(val value: Set<String>) : PrefValue
}

@Serializable
data class BackupServer(
    val server: Server,
    val addresses: List<ServerAddress>,
    val users: List<User>,
)

@Serializable
data class BackupDownloadedItem(val serverId: String, val itemId: String, val itemKind: String)

data class RestoreSummary(
    val serversRestored: Int,
    val usersRestored: Int,
    val rulesRestored: Int,
    val downloadedItems: List<BackupDownloadedItem>,
    // Not yet written to the DB - see BackupManager.restore()'s doc. Applied only once the user
    // answers the redownload prompt, via BackupManager.applyAutoDownloadRules().
    val autoDownloadRules: List<AutoDownloadRuleDto>,
)

object BackupDownloadedItemKind {
    const val MOVIE = "movie"
    const val EPISODE = "episode"
}

/**
 * Restoring downloads requires an active, authenticated session against the right server, which may
 * not exist yet right after restore - so the picked items are stashed as JSON in
 * [dev.pschmitt.jellyfin.settings.domain.AppPreferences.pendingRestoreDownloads] and processed
 * later once a session for the matching server is active.
 */
private val pendingRestoreDownloadsJson = Json { prettyPrint = false }

fun encodePendingRestoreDownloads(items: List<BackupDownloadedItem>): String =
    pendingRestoreDownloadsJson.encodeToString(
        ListSerializer(BackupDownloadedItem.serializer()),
        items,
    )

fun decodePendingRestoreDownloads(json: String): List<BackupDownloadedItem> =
    pendingRestoreDownloadsJson.decodeFromString(
        ListSerializer(BackupDownloadedItem.serializer()),
        json,
    )
