package dev.pschmitt.jellyfin.models

/**
 * One Sonarr/Radarr queue entry as surfaced by
 * [dev.pschmitt.jellyfin.repository.QueueStatusRepository]. [item] is the Jellyfin library item the
 * entry was matched to (see `matchSonarr`/`matchRadarr` in `QueueStatusMatching.kt`) - null when
 * the download couldn't be resolved to anything in the library, e.g. a torrent added manually on
 * the Sonarr/Radarr side for a series/movie Jellyfin hasn't imported yet. Unmatched entries still
 * carry a human-readable [title] built from the PVR side's own metadata, so a queue view can list
 * every download rather than silently dropping the unmatched ones.
 */
data class PvrQueueEntry(
    val item: JollyfinItem?,
    val title: String,
    val status: QueueStatus,
    // Provider ids keep progress visible for Seerr-only media before Jellyfin imports the file.
    val tmdbId: Int? = null,
    val sonarrEpisodeId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String? = null,
    // The series'/movie's path segment in Sonarr's/Radarr's web UI - deep-links "Open in Sonarr/
    // Radarr" straight to its page. Null when the queue row couldn't be joined to a series/movie.
    val pvrTitleSlug: String? = null,
    // The PVR service's own id for this queue row - stable across polls, so snapshots can be
    // diffed to detect a download leaving the queue (= finished importing, in the common case).
    val queueItemId: Int = 0,
)

/**
 * One full poll of both services' queues. [errors] carries per-service fetch failures instead of
 * silently collapsing them into an empty queue - an unreachable Sonarr should read as "Sonarr is
 * unreachable", not "nothing is downloading". [fetchedSources] lists the services that were enabled
 * *and* answered this poll: only their entries' disappearance since the previous snapshot means
 * anything (see `QueueStatusRepositoryImpl.notifyFinishedDownloads`).
 */
data class PvrQueueSnapshot(
    val entries: List<PvrQueueEntry> = emptyList(),
    val errors: List<PvrFetchError> = emptyList(),
    val fetchedSources: Set<PvrSource> = emptySet(),
    // Services that are enabled/configured, still trying for their first-ever successful poll
    // this app session, and haven't failed enough times in a row yet to report an error - i.e.
    // "still loading, not yet known to be broken or working". A configured-but-never-reachable
    // service without prior good data would otherwise render identically to "nothing queued" for
    // the whole tolerated-failure grace window, which is what this exists to distinguish.
    val pendingSources: Set<PvrSource> = emptySet(),
)

/** A user-presentable per-service fetch failure - [message] already names the service. */
data class PvrFetchError(val source: PvrSource, val message: String)

/**
 * The download-progress payload of a single Sonarr/Radarr queue entry (see [PvrQueueEntry] for the
 * item association).
 */
data class QueueStatus(
    val source: PvrSource,
    val status: QueueItemStatus,
    val percent: Int = -1,
    val sizeBytes: Long = 0L,
    val remainingBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val etaSeconds: Long = -1L,
    val errorMessage: String? = null,
    // The underlying download-client transfer's id (distinct from the queue row's own id) - what
    // GET/POST /api/v3/manualimport filters/targets by. Null when the PVR service didn't report
    // one (should not happen in practice, but the field is optional on the wire).
    val downloadId: String? = null,
    // The PVR queue row's own id (mirrors PvrQueueEntry.queueItemId) - needed to remove/blocklist
    // the release from the manual-import sheet's reject action. 0 (never a real id) when this
    // QueueStatus wasn't built from a per-item lookup that carries it - see toQueueStatusMap().
    val queueItemId: Int = 0,
)

enum class PvrSource {
    SONARR,
    RADARR,
}

enum class QueueItemStatus {
    QUEUED,
    DOWNLOADING,
    IMPORTING,
    WARNING,
    FAILED,
}
