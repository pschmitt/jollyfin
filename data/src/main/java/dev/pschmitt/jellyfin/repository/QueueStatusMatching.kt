package dev.pschmitt.jellyfin.repository

import dev.pschmitt.jellyfin.api.pvr.PvrImage
import dev.pschmitt.jellyfin.api.pvr.PvrStatusMessage
import dev.pschmitt.jellyfin.api.pvr.RadarrManualImportItem
import dev.pschmitt.jellyfin.api.pvr.RadarrMovie
import dev.pschmitt.jellyfin.api.pvr.RadarrQueueItem
import dev.pschmitt.jellyfin.api.pvr.SonarrManualImportItem
import dev.pschmitt.jellyfin.api.pvr.SonarrQueueItem
import dev.pschmitt.jellyfin.api.pvr.SonarrSeries
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.JollyfinShow
import dev.pschmitt.jellyfin.models.ManualImportCandidate
import dev.pschmitt.jellyfin.models.PvrQueueEntry
import dev.pschmitt.jellyfin.models.PvrSource
import dev.pschmitt.jellyfin.models.QueueItemStatus
import dev.pschmitt.jellyfin.models.QueueStatus
import java.util.UUID

/**
 * Pure functions matching Sonarr/Radarr queue entries to Jellyfin items - no suspend, no I/O, so
 * they're directly unit-testable without Room/Hilt/Android in the loop. Every queue entry produces
 * a [PvrQueueEntry]; a lookup that fails along the way (unknown provider id, orphaned queue
 * reference, episode not yet synced into Jellyfin's library, a torrent added manually on the PVR
 * side, ...) yields an unmatched entry (`item = null`) titled from the PVR side's own metadata
 * instead of being dropped - this must never throw, since a single bad PVR-side reference shouldn't
 * take down the whole match.
 */

/** Sonarr's `series.tvdbId`/`movie.tmdbId` default to 0 when the field is absent from the DTO. */
private const val UNSET_PROVIDER_ID = 0

fun matchSonarr(
    series: List<SonarrSeries>,
    queue: List<SonarrQueueItem>,
    jellyfinShows: List<JollyfinShow>,
    episodesByShowId: Map<UUID, List<JollyfinEpisode>>,
): List<PvrQueueEntry> {
    val showByTvdbId: Map<String, JollyfinShow> =
        jellyfinShows.mapNotNull { show -> show.tvdbId?.let { it to show } }.toMap()
    val seriesById: Map<Int, SonarrSeries> = series.associateBy { it.id }

    return queue.map { item ->
        val sonarrSeries = seriesById[item.seriesId]
        val episodeNumber = item.episode?.episodeNumber?.takeIf { it != UNSET_PROVIDER_ID }
        val show =
            sonarrSeries
                ?.tvdbId
                ?.takeIf { it != UNSET_PROVIDER_ID }
                ?.let { showByTvdbId[it.toString()] }
        val episode =
            if (show != null && episodeNumber != null) {
                episodesByShowId[show.id]?.firstOrNull {
                    it.parentIndexNumber == item.seasonNumber && it.indexNumber == episodeNumber
                }
            } else {
                null
            }
        PvrQueueEntry(
            item = episode,
            title = sonarrQueueTitle(sonarrSeries, item, episodeNumber),
            status = item.toQueueStatus(),
            tmdbId = sonarrSeries?.tmdbId?.takeIf { it != UNSET_PROVIDER_ID },
            sonarrEpisodeId = item.episodeId.takeIf { it != UNSET_PROVIDER_ID },
            seasonNumber = item.seasonNumber.takeIf { it != UNSET_PROVIDER_ID },
            episodeNumber = episodeNumber,
            posterUrl = sonarrSeries?.images?.posterUrl(),
            queueItemId = item.id,
        )
    }
}

fun matchRadarr(
    movies: List<RadarrMovie>,
    queue: List<RadarrQueueItem>,
    jellyfinMovies: List<JollyfinMovie>,
): List<PvrQueueEntry> {
    val movieByTmdbId: Map<String, JollyfinMovie> =
        jellyfinMovies.mapNotNull { movie -> movie.tmdbId?.let { it to movie } }.toMap()
    val radarrMovieById: Map<Int, RadarrMovie> = movies.associateBy { it.id }

    return queue.map { item ->
        val radarrMovie = radarrMovieById[item.movieId]
        val movie =
            radarrMovie
                ?.tmdbId
                ?.takeIf { it != UNSET_PROVIDER_ID }
                ?.let { movieByTmdbId[it.toString()] }
        PvrQueueEntry(
            item = movie,
            title = radarrMovie?.title?.takeIf { it.isNotBlank() } ?: item.title ?: UNKNOWN_TITLE,
            status = item.toQueueStatus(),
            tmdbId = radarrMovie?.tmdbId?.takeIf { it != UNSET_PROVIDER_ID },
            posterUrl = radarrMovie?.images?.posterUrl(),
            queueItemId = item.id,
        )
    }
}

/**
 * Collapses queue entries into the per-item status map used for badges. Unmatched entries have no
 * item id to key by and are left out. If two queue entries resolve to the same Jellyfin item (e.g.
 * a retried download that shows up as two queue rows before Sonarr/Radarr cleans up the old one),
 * the later entry wins - [toMap] keeps the last occurrence of a duplicate key.
 */
fun List<PvrQueueEntry>.toQueueStatusMap(): Map<UUID, QueueStatus> = mapNotNull { entry ->
    entry.item?.let { it.id to entry.status.copy(queueItemId = entry.queueItemId) }
}
    .toMap()

fun List<PvrQueueEntry>.toRadarrQueueStatusMap(): Map<Int, QueueStatus> = filter {
    it.status.source == PvrSource.RADARR
}
    .mapNotNull { entry -> entry.tmdbId?.let { it to entry.status } }
    .toMap()

fun List<PvrQueueEntry>.toSonarrQueueStatusMap(): Map<Int, QueueStatus> = filter {
    it.status.source == PvrSource.SONARR
}
    .mapNotNull { entry -> entry.sonarrEpisodeId?.let { it to entry.status } }
    .toMap()

/**
 * The key two [PvrQueueEntry]s share when they're actually duplicates of the same underlying
 * release (e.g. two competing grabs of the same episode/movie still both awaiting manual import).
 * Deliberately not [PvrQueueEntry.item]'s id - that's *derived* from these same provider ids plus a
 * Jellyfin-side lookup, and can come back null on one entry but not the other if that lookup is
 * incomplete on just one side. [tmdbId]/[sonarrEpisodeId] come straight from the raw Sonarr/Radarr
 * queue row, independent of Jellyfin matching, so they're the reliable key. `null` when neither id
 * is present - nothing safe to group by, so the entry stays its own singleton.
 */
fun PvrQueueEntry.duplicateGroupKey(): Pair<PvrSource, Int>? =
    when (status.source) {
        PvrSource.RADARR -> tmdbId?.let { PvrSource.RADARR to it }
        PvrSource.SONARR -> sonarrEpisodeId?.let { PvrSource.SONARR to it }
    }

/**
 * Groups entries sharing a non-null [duplicateGroupKey] together, preserving first-occurrence
 * order; entries with no key each become their own single-element group.
 */
fun List<PvrQueueEntry>.groupDuplicates(): List<List<PvrQueueEntry>> {
    // Ungroupable entries get a unique key (identity) so they land in their own single-element
    // group instead of colliding with each other under a shared "null" key.
    val groups = LinkedHashMap<Any, MutableList<PvrQueueEntry>>()
    for (entry in this) {
        val key: Any = entry.duplicateGroupKey() ?: entry
        groups.getOrPut(key) { mutableListOf() }.add(entry)
    }
    return groups.values.map { it.toList() }
}

/**
 * The key two entries share when they're separate episodes of the same show+season worth clustering
 * into a single "Show - Season N" display row (e.g. a season grabbed as several per-episode
 * downloads instead of one season-pack release). Unlike [duplicateGroupKey], this is about
 * *different* episodes, not retries of the same one - callers must dedupe retries (see
 * [groupDuplicates]) before applying this. `null` when there's nothing safe to cluster: a
 * WARNING/FAILED entry (surfaced individually so it stays actionable), a movie (Radarr has no
 * season concept), or an entry missing the [tmdbId]/[seasonNumber] identity, or already a
 * season-pack row with no per-episode number (already reads as "Show - Season N" on its own).
 */
fun seasonClusterKey(
    source: PvrSource,
    status: QueueItemStatus,
    tmdbId: Int?,
    seasonNumber: Int?,
    episodeNumber: Int?,
): Pair<Int, Int>? {
    if (source != PvrSource.SONARR) return null
    if (status == QueueItemStatus.WARNING || status == QueueItemStatus.FAILED) return null
    if (episodeNumber == null) return null
    if (tmdbId == null || seasonNumber == null) return null
    return tmdbId to seasonNumber
}

/**
 * Combines a season cluster's individual statuses into one: sizes/remaining/speed sum across the
 * cluster and [QueueStatus.percent] is recomputed from the totals; [QueueStatus.status] is the most
 * "in-progress" of the cluster (DOWNLOADING > IMPORTING > QUEUED - WARNING/FAILED entries never
 * reach a cluster, see [seasonClusterKey]). [statuses] must be non-empty and share the same
 * [QueueStatus.source].
 */
fun aggregateQueueStatuses(statuses: List<QueueStatus>): QueueStatus {
    val sizeBytes = statuses.sumOf { it.sizeBytes }
    val remainingBytes = statuses.sumOf { it.remainingBytes }
    val speedBytesPerSecond = statuses.sumOf { it.speedBytesPerSecond }
    val percent =
        if (sizeBytes > 0) {
            (((sizeBytes - remainingBytes) * 100) / sizeBytes).toInt().coerceIn(0, 100)
        } else {
            -1
        }
    val etaSeconds =
        if (speedBytesPerSecond > 0 && remainingBytes > 0) {
            remainingBytes / speedBytesPerSecond
        } else {
            -1L
        }
    val status =
        when {
            statuses.any { it.status == QueueItemStatus.DOWNLOADING } -> QueueItemStatus.DOWNLOADING
            statuses.any { it.status == QueueItemStatus.IMPORTING } -> QueueItemStatus.IMPORTING
            else -> QueueItemStatus.QUEUED
        }
    return QueueStatus(
        source = statuses.first().source,
        status = status,
        percent = percent,
        sizeBytes = sizeBytes,
        remainingBytes = remainingBytes,
        speedBytesPerSecond = speedBytesPerSecond,
        etaSeconds = etaSeconds,
    )
}

/**
 * "Show - S3E5" -> "Show - Season 3" - strips the per-episode suffix off any cluster member's
 * title.
 */
fun seasonClusterTitle(anyEpisodeTitle: String, seasonNumber: Int): String =
    "${anyEpisodeTitle.substringBeforeLast(" - S")} - Season $seasonNumber"

/**
 * [groupDuplicates] first (so retries of the same episode never get double-counted as separate
 * episodes), then merges distinct same-season episodes per [seasonClusterKey] into one synthesized
 * entry - title/status combine every clustered episode
 * ([seasonClusterTitle]/[aggregateQueueStatuses]), [PvrQueueEntry.item] is left null since a
 * cluster no longer points at one specific episode. Singleton results (the common case) pass
 * through unchanged.
 */
fun List<PvrQueueEntry>.clusterSeasonsForDisplay(): List<PvrQueueEntry> {
    val episodeReps = groupDuplicates().map { it.last() }
    val groups = LinkedHashMap<Any, MutableList<PvrQueueEntry>>()
    for (rep in episodeReps) {
        val key: Any =
            seasonClusterKey(
                rep.status.source,
                rep.status.status,
                rep.tmdbId,
                rep.seasonNumber,
                rep.episodeNumber,
            ) ?: rep
        groups.getOrPut(key) { mutableListOf() }.add(rep)
    }
    return groups.values.map { group ->
        val rep = group.last()
        if (group.size == 1) {
            rep
        } else {
            rep.copy(
                title = rep.seasonNumber?.let { seasonClusterTitle(rep.title, it) } ?: rep.title,
                status = aggregateQueueStatuses(group.map { it.status }),
                item = null,
            )
        }
    }
}

/**
 * "Series - S1E5" when the episode is identified, "Series - Season 1" for season-pack grabs (no
 * per-episode number), falling back to the release title Sonarr reports for the download.
 */
private fun sonarrQueueTitle(
    series: SonarrSeries?,
    item: SonarrQueueItem,
    episodeNumber: Int?,
): String {
    val seriesTitle = series?.title?.takeIf { it.isNotBlank() }
    return when {
        seriesTitle != null && episodeNumber != null ->
            "$seriesTitle - S${item.seasonNumber}E$episodeNumber"
        seriesTitle != null && item.seasonNumber != 0 ->
            "$seriesTitle - Season ${item.seasonNumber}"
        seriesTitle != null -> seriesTitle
        else -> item.title ?: UNKNOWN_TITLE
    }
}

private const val UNKNOWN_TITLE = "Unknown"

private fun List<PvrImage>.posterUrl(): String? = firstOrNull {
    it.coverType.equals("poster", ignoreCase = true)
}
    ?.let { it.remoteUrl ?: it.url }

private fun SonarrQueueItem.toQueueStatus(): QueueStatus =
    buildQueueStatus(
        source = PvrSource.SONARR,
        status = status,
        trackedDownloadStatus = trackedDownloadStatus,
        trackedDownloadState = trackedDownloadState,
        size = size,
        sizeleft = sizeleft,
        timeleft = timeleft,
        errorMessage = errorMessage ?: statusMessages.toDisplayText(),
        downloadId = downloadId,
    )

private fun RadarrQueueItem.toQueueStatus(): QueueStatus =
    buildQueueStatus(
        source = PvrSource.RADARR,
        status = status,
        trackedDownloadStatus = trackedDownloadStatus,
        trackedDownloadState = trackedDownloadState,
        size = size,
        sizeleft = sizeleft,
        timeleft = timeleft,
        errorMessage = errorMessage ?: statusMessages.toDisplayText(),
        downloadId = downloadId,
    )

/**
 * Sonarr/Radarr's `statusMessages` mixes bare top-level reasons (e.g. "One or more episodes
 * expected in this release were not imported or missing from the release", `messages` empty) with
 * per-file import diagnostics (`title` = filename, `messages` = details for that file). The bare
 * reasons are what's worth surfacing as the queue item's status text; the per-file breakdown is too
 * verbose for a one-line summary and is only used as a fallback.
 */
private fun List<PvrStatusMessage>.toDisplayText(): String? {
    val reasons = filter {
        it.messages.isEmpty()
    }
        .mapNotNull { it.title?.takeIf(String::isNotBlank) }
    if (reasons.isNotEmpty()) return reasons.joinToString("; ")
    val first = firstOrNull() ?: return null
    return listOfNotNull(first.title?.takeIf(String::isNotBlank), first.messages.firstOrNull())
        .joinToString(": ")
        .takeIf { it.isNotBlank() }
}

private fun buildQueueStatus(
    source: PvrSource,
    status: String?,
    trackedDownloadStatus: String?,
    trackedDownloadState: String?,
    size: Long,
    sizeleft: Long,
    timeleft: String?,
    errorMessage: String?,
    downloadId: String?,
): QueueStatus {
    val etaSeconds = parseTimeleftSeconds(timeleft)
    val percent = if (size > 0) (((size - sizeleft) * 100) / size).toInt().coerceIn(0, 100) else -1
    val speedBytesPerSecond = if (etaSeconds > 0 && sizeleft > 0) sizeleft / etaSeconds else 0L
    return QueueStatus(
        source = source,
        status = mapQueueItemStatus(status, trackedDownloadStatus, trackedDownloadState),
        percent = percent,
        sizeBytes = size,
        remainingBytes = sizeleft,
        speedBytesPerSecond = speedBytesPerSecond,
        etaSeconds = etaSeconds,
        errorMessage = errorMessage,
        downloadId = downloadId,
    )
}

/**
 * Sonarr and Radarr share the same queue item status vocabulary:
 * - `status`: "queued" / "delay" / "paused" / "downloading" / "completed" / "failed" / "warning"
 * - `trackedDownloadStatus`: "ok" / "warning" / "error" - an overlay on top of `status` describing
 *   whether the *tracked* download (post-grab import tracking) is healthy.
 * - `trackedDownloadState`: "downloading" / "importPending" / "importing" / "imported" /
 *   "failedPending" / "failed"
 *
 * `trackedDownloadStatus` signals problems regardless of the coarser `status` field, so it's
 * checked first; otherwise `status`/`trackedDownloadState` together decide between
 * queued/downloading/importing.
 */
internal fun mapQueueItemStatus(
    status: String?,
    trackedDownloadStatus: String?,
    trackedDownloadState: String?,
): QueueItemStatus {
    val normalizedStatus = status?.lowercase()
    val normalizedTrackedStatus = trackedDownloadStatus?.lowercase()
    val normalizedTrackedState = trackedDownloadState?.lowercase()

    if (normalizedTrackedStatus == "error") return QueueItemStatus.FAILED
    if (normalizedTrackedStatus == "warning") return QueueItemStatus.WARNING

    return when {
        normalizedStatus == "failed" || normalizedTrackedState in FAILED_STATES ->
            QueueItemStatus.FAILED
        normalizedStatus == "warning" -> QueueItemStatus.WARNING
        normalizedStatus == "completed" || normalizedTrackedState in IMPORTING_STATES ->
            QueueItemStatus.IMPORTING
        normalizedStatus == "downloading" -> QueueItemStatus.DOWNLOADING
        else -> QueueItemStatus.QUEUED
    }
}

private val FAILED_STATES = setOf("failed", "failedpending")
private val IMPORTING_STATES = setOf("importpending", "importing", "imported")

/**
 * Sonarr's guess for which episode(s) a manual-import candidate file belongs to, formatted like the
 * rest of the app ("S1E6"); multi-episode files (a double-length special, say) join their episode
 * numbers ("S1E6E7"). Null when Sonarr couldn't determine any episode for the file at all
 * - see [ManualImportCandidate.canImport].
 */
internal fun SonarrManualImportItem.toCandidate(): ManualImportCandidate {
    val episodeLabel =
        episodes
            .takeIf { it.isNotEmpty() }
            ?.let { eps ->
                val seasonPrefix = seasonNumber?.let { "S$it" }.orEmpty()
                seasonPrefix + eps.joinToString(separator = "") { "E${it.episodeNumber}" }
            }
    return ManualImportCandidate(
        id = id,
        name = name ?: path?.substringAfterLast('/') ?: UNKNOWN_TITLE,
        sizeBytes = size,
        qualityName = quality?.quality?.name,
        episodeLabel = episodeLabel,
        canImport = series?.id != null && episodes.isNotEmpty(),
        rejections = rejections.map { it.reason },
    )
}

internal fun RadarrManualImportItem.toCandidate(): ManualImportCandidate =
    ManualImportCandidate(
        id = id,
        name = name ?: path?.substringAfterLast('/') ?: UNKNOWN_TITLE,
        sizeBytes = size,
        qualityName = quality?.quality?.name,
        episodeLabel = null,
        canImport = movie?.id != null,
        rejections = rejections.map { it.reason },
    )

/** Parses Sonarr/Radarr's `timeleft` duration string ("HH:MM:SS", sometimes "D.HH:MM:SS"). */
internal fun parseTimeleftSeconds(timeleft: String?): Long {
    if (timeleft.isNullOrBlank()) return -1L
    val dayAndRest = timeleft.split(".", limit = 2)
    val (days, clock) =
        if (dayAndRest.size == 2) dayAndRest[0] to dayAndRest[1] else "0" to dayAndRest[0]
    val parts = clock.split(":").mapNotNull { it.toLongOrNull() }
    if (parts.size != 3) return -1L
    val daysLong = days.toLongOrNull() ?: 0L
    val (hours, minutes, seconds) = parts
    return daysLong * 86_400L + hours * 3_600L + minutes * 60L + seconds
}
