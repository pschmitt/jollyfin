package dev.pschmitt.jellyfin.models

import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

interface JollyfinItem {
    val id: UUID
    val name: String
    val originalTitle: String?
    val overview: String
    val played: Boolean
    val favorite: Boolean
    val canPlay: Boolean
    val canDownload: Boolean
    val sources: List<JollyfinSource>
    val runtimeTicks: Long
    val playbackPositionTicks: Long
    val unplayedItemCount: Int?
    val images: JollyfinImages
    val chapters: List<JollyfinChapter>

    /**
     * When this item was added to the Jellyfin library (server's `DateCreated`). Only populated for
     * items mapped straight from a [BaseItemDto] (the online path) - null for items rebuilt from
     * local DB rows (offline/download storage), where the server isn't there to ask.
     */
    val dateCreated: DateTime?
}

suspend fun BaseItemDto.toJollyfinItem(
    jellyfinRepository: JellyfinRepository,
    serverDatabase: ServerDatabaseDao? = null,
): JollyfinItem? {
    return when (type) {
        BaseItemKind.MOVIE -> toJollyfinMovie(jellyfinRepository, serverDatabase)
        BaseItemKind.EPISODE -> toJollyfinEpisode(jellyfinRepository)
        BaseItemKind.SEASON -> toJollyfinSeason(jellyfinRepository)
        BaseItemKind.SERIES -> toJollyfinShow(jellyfinRepository, serverDatabase)
        BaseItemKind.BOX_SET -> toJollyfinBoxSet(jellyfinRepository)
        BaseItemKind.FOLDER -> toJollyfinFolder(jellyfinRepository)
        else -> null
    }
}

fun JollyfinItem.isDownloading(): Boolean {
    return sources
        .filter { it.type == JollyfinSourceType.LOCAL }
        .any { it.path.endsWith(".download") }
}

fun JollyfinItem.isDownloaded(): Boolean {
    return sources
        .filter { it.type == JollyfinSourceType.LOCAL }
        .any { !it.path.endsWith(".download") }
}

/**
 * A completed local download whose file is actually missing or empty on disk right now - e.g. the
 * storage volume it lived on got reformatted or unmounted. [JollyfinSource.size] is a live
 * `File(path).length()` read (see `JollyfinSourceDto.toJollyfinSource`), which silently returns 0
 * for a vanished file rather than throwing - a completed (non-`.download`) source is never
 * legitimately 0 bytes, so this is an unambiguous signal to stop offering Play and surface a
 * re-download/delete choice instead.
 */
fun JollyfinItem.isDownloadBroken(): Boolean {
    return sources
        .filter { it.type == JollyfinSourceType.LOCAL && !it.path.endsWith(".download") }
        .any { it.size <= 0L }
}

/**
 * Whether this item was added to the library within the last [days] days - drives the "NEW" badge
 * on library carousels. Always false when [dateCreated] is unknown (offline items).
 */
fun JollyfinItem.isRecentlyAdded(days: Long = 7): Boolean {
    val addedAt = dateCreated ?: return false
    return addedAt.isAfter(LocalDateTime.now().minusDays(days))
}

/**
 * TMDB id, when known - only [JollyfinMovie]/[JollyfinShow] carry one (Jellyfin's own
 * `ProviderIds["Tmdb"]`, a nullable String there since it's Jellyfin-sourced metadata, not
 * guaranteed present). Used to match a Jellyfin library item against a Seerr search result, whose
 * `tmdbId` is a non-nullable `Int` (TMDB is the ground truth Seerr is built on).
 */
fun JollyfinItem.tmdbIdOrNull(): Int? =
    when (this) {
        is JollyfinMovie -> tmdbId
        is JollyfinShow -> tmdbId
        else -> null
    }?.toIntOrNull()

/**
 * Whether this downloaded episode is eligible for
 * [AutoDeleteWatchedWorker][dev.pschmitt.jellyfin.work.AutoDeleteWatchedWorker] to delete right
 * now: watched, watched more than [thresholdHours] ago, and not pinned via the local source's
 * `excludeFromAutoDelete` flag. Movies are never auto-deleted (the worker only ever considers
 * episodes), so there's no equivalent on the base [JollyfinItem] - only episodes carry
 * [JollyfinEpisode.lastPlayedDate]. Drives both the worker's own deletion pass and the "marked for
 * deletion" badge on the Downloads screen/episode detail page, so both always agree.
 */
fun JollyfinEpisode.isMarkedForAutoDeletion(thresholdHours: Int): Boolean {
    val localSource = sources.firstOrNull { it.type == JollyfinSourceType.LOCAL } ?: return false
    if (localSource.excludeFromAutoDelete) return false
    val watchedAt = lastPlayedDate ?: return false
    return played && watchedAt.isBefore(LocalDateTime.now().minusHours(thresholdHours.toLong()))
}
