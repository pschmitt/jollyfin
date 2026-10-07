package dev.pschmitt.jellyfin.api.pvr

import kotlinx.serialization.Serializable

/**
 * Response DTOs for the Sonarr/Radarr v3 REST APIs. Only the fields Jollyfin actually needs are
 * modeled - both APIs return many more fields per record, so every DTO here relies on the caller
 * decoding with `ignoreUnknownKeys = true` (see [PvrJson]).
 */

// region Sonarr - GET /api/v3/series

@Serializable
data class SonarrSeries(
    val id: Int,
    val tvdbId: Int = 0,
    val tmdbId: Int = 0,
    val title: String = "",
    // The series' path segment in Sonarr's own web UI (`/series/<titleSlug>`).
    val titleSlug: String? = null,
    val images: List<PvrImage> = emptyList(),
)

// endregion

// region Radarr - GET /api/v3/movie

@Serializable
data class RadarrMovie(
    val id: Int,
    val tmdbId: Int = 0,
    val title: String = "",
    // The movie's path segment in Radarr's own web UI (`/movie/<titleSlug>`).
    val titleSlug: String? = null,
    val images: List<PvrImage> = emptyList(),
)

// endregion

// region Sonarr - GET /api/v3/queue
// Sonarr's queue entries are per-episode: seriesId + episodeId + seasonNumber identify what's
// being grabbed. The episode's number within its season is only available via the nested
// `episode` object, which Sonarr only embeds when the request passes `includeEpisode=true` (see
// SonarrApi.getQueue()) - needed to resolve the specific Jellyfin episode by season/episode
// number, since `episodeId` is Sonarr's own internal id and has no meaning to Jellyfin.

@Serializable data class SonarrEpisode(val episodeNumber: Int = 0)

/**
 * Per-file/per-reason breakdown Sonarr/Radarr attach to a queue item's tracked download (import
 * blocked reasons, per-file import results, ...). `messages` is empty for a bare top-level reason
 * (e.g. "One or more episodes expected in this release were not imported or missing from the
 * release") and non-empty for a per-file entry, where `title` is the filename.
 */
@Serializable
data class PvrStatusMessage(val title: String? = null, val messages: List<String> = emptyList())

@Serializable
data class SonarrQueueItem(
    val id: Int,
    val seriesId: Int = 0,
    val episodeId: Int = 0,
    val seasonNumber: Int = 0,
    val episode: SonarrEpisode? = null,
    val title: String? = null,
    val status: String? = null,
    val trackedDownloadStatus: String? = null,
    val trackedDownloadState: String? = null,
    val size: Long = 0L,
    val sizeleft: Long = 0L,
    val timeleft: String? = null,
    val estimatedCompletionTime: String? = null,
    val errorMessage: String? = null,
    val statusMessages: List<PvrStatusMessage> = emptyList(),
    // Sonarr's own id for the underlying download-client transfer (not this queue row) - the key
    // GET/POST /api/v3/manualimport filters/targets by, see the "Manual Import" region below.
    val downloadId: String? = null,
)

@Serializable
data class SonarrQueueResponse(
    val page: Int = 1,
    val pageSize: Int = 0,
    val totalRecords: Int = 0,
    val records: List<SonarrQueueItem> = emptyList(),
)

// endregion

// region Radarr - GET /api/v3/queue
// Radarr's queue entries are per-movie: movieId identifies what's being grabbed (no
// season/episode concept).

@Serializable
data class RadarrQueueItem(
    val id: Int,
    val movieId: Int = 0,
    val title: String? = null,
    val status: String? = null,
    val trackedDownloadStatus: String? = null,
    val trackedDownloadState: String? = null,
    val size: Long = 0L,
    val sizeleft: Long = 0L,
    val timeleft: String? = null,
    val estimatedCompletionTime: String? = null,
    val errorMessage: String? = null,
    val statusMessages: List<PvrStatusMessage> = emptyList(),
    // Radarr's own id for the underlying download-client transfer (not this queue row) - the key
    // GET/POST /api/v3/manualimport filters/targets by, see the "Manual Import" region below.
    val downloadId: String? = null,
)

@Serializable
data class RadarrQueueResponse(
    val page: Int = 1,
    val pageSize: Int = 0,
    val totalRecords: Int = 0,
    val records: List<RadarrQueueItem> = emptyList(),
)

// endregion

// region Sonarr - GET /api/v3/calendar
// Unlike /queue, /calendar accepts includeSeries=true, which embeds the series object (with
// tvdbId) directly on each entry - no separate getSeries() call/join needed to resolve tvdbId.
// The endpoint returns a flat JSON array, not a paginated {records: [...]} wrapper.

@Serializable
data class PvrImage(
    val coverType: String? = null,
    val remoteUrl: String? = null,
    val url: String? = null,
)

@Serializable
data class SonarrCalendarSeries(
    val tvdbId: Int = 0,
    // Sonarr v4 series resources also carry the TMDB id - used to open the Seerr detail view
    // for entries that aren't in the Jellyfin library yet.
    val tmdbId: Int = 0,
    val title: String = "",
    val images: List<PvrImage> = emptyList(),
)

@Serializable
data class SonarrCalendarEntry(
    val id: Int,
    val seriesId: Int = 0,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val title: String? = null,
    val airDateUtc: String? = null,
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
    val series: SonarrCalendarSeries? = null,
)

// endregion

// region Sonarr - GET /api/v3/episode?seriesId=X
// Unlike /calendar (a global date-range view across all shows), this returns every episode of one
// specific series - what SeasonUpcomingEpisodesRepository needs to find episodes Sonarr knows
// about that aren't in the Jellyfin library yet, regardless of whether they've aired. A flat JSON
// array, same as /calendar and /series.

@Serializable
data class SonarrEpisodeDto(
    val id: Int,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val title: String? = null,
    val airDateUtc: String? = null,
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
)

// endregion

// region Sonarr - PUT /api/v3/episode/monitor
// Bulk monitored on/off toggle - the closest Sonarr equivalent to "don't grab this one again" for
// a single episode (there is no per-episode delete/exclude the way there is for a whole series).

@Serializable
data class SonarrEpisodeMonitorRequest(val episodeIds: List<Int>, val monitored: Boolean)

// endregion

// region Sonarr/Radarr - POST /api/v3/command, GET /api/v3/command/{id}
// POST triggers an automatic search: the PVR service picks and grabs the best matching release
// itself, with no release list surfaced to the caller (compare GET/POST /api/v3/release below,
// which is the interactive/manual counterpart). The POST is answered as soon as the command is
// *queued*, not once it finishes - GET /api/v3/command/{id} is polled afterwards to find out
// when/how it ended, so the app can notify the user once the search actually completes. The
// request payloads differ per service ("EpisodeSearch" + episodeIds vs "MoviesSearch" + movieIds),
// the response shape is identical.

@Serializable data class SonarrCommandRequest(val name: String, val episodeIds: List<Int>)

@Serializable data class SonarrSeriesCommandRequest(val name: String, val seriesId: Int)

@Serializable data class RadarrCommandRequest(val name: String, val movieIds: List<Int>)

@Serializable data class PvrCommandResponse(val id: Int, val status: String? = null)

// endregion

// region Sonarr - GET /api/v3/episode/{id}
// Single-episode lookup, used only to build a human-readable notification once an automatic search
// (triggered above) finishes - the command response/status carries no episode-identifying text.

@Serializable data class SonarrEpisodeSeriesRef(val title: String? = null)

@Serializable
data class SonarrEpisodeDetail(
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val title: String? = null,
    val series: SonarrEpisodeSeriesRef? = null,
)

// endregion

// region Sonarr/Radarr - GET /api/v3/release?episodeId=X (Sonarr) / ?movieId=X (Radarr),
// POST /api/v3/release
// GET lists candidate releases (interactive/manual search) without grabbing anything; POSTing a
// release's guid+indexerId back grabs that specific one. Sonarr and Radarr use the same release
// resource shape, so one set of DTOs serves both. Only the fields Jollyfin's release picker needs
// are modeled - the release resource has many more.

@Serializable data class PvrGrabReleaseRequest(val guid: String, val indexerId: Int)

@Serializable data class PvrQualityName(val name: String? = null)

@Serializable data class PvrReleaseQuality(val quality: PvrQualityName? = null)

@Serializable
data class PvrRelease(
    val guid: String,
    val indexerId: Int = 0,
    val indexer: String? = null,
    val title: String? = null,
    val size: Long = 0L,
    val seeders: Int? = null,
    val ageHours: Double? = null,
    val quality: PvrReleaseQuality? = null,
    val rejected: Boolean = false,
    val rejections: List<String> = emptyList(),
)

// endregion

// region Sonarr/Radarr - GET /api/v3/rootfolder
// One entry per *configured* TV-shows/movies storage location - only the folder(s) the user
// explicitly set up as a root folder, unlike /diskspace below (which also reports every other
// mount point the service's host can see). Only exposes freeSpace, not totalSpace - paired with
// /diskspace (matched by path) to get both numbers for the folder that's actually the media
// library, not just an arbitrary/largest visible mount. Both APIs return the same shape; a flat
// JSON array.

@Serializable data class PvrRootFolderDto(val path: String = "")

// endregion

// region Sonarr/Radarr - GET /api/v3/diskspace
// Free/total space for every mount point the service's host can see - broader than /rootfolder
// above, which is why the repository matches entries here against a configured root folder's
// path rather than trusting this list on its own. A flat JSON array.

@Serializable
data class PvrDiskSpaceDto(
    val path: String = "",
    val freeSpace: Long = 0L,
    val totalSpace: Long = 0L,
)

// endregion

// region Radarr - GET /api/v3/calendar
// Radarr's calendar entries are full movie objects (same shape as /api/v3/movie), so tmdbId is
// already present per entry - no separate getMovie() call/join needed. Also a flat JSON array,
// same as Sonarr's calendar endpoint.

@Serializable
data class RadarrCalendarEntry(
    val id: Int,
    val tmdbId: Int = 0,
    val title: String = "",
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
    val images: List<PvrImage> = emptyList(),
    val inCinemas: String? = null,
    val digitalRelease: String? = null,
    val physicalRelease: String? = null,
)

// endregion

// region Sonarr/Radarr - GET /api/v3/manualimport?downloadId=X, POST /api/v3/command (ManualImport)
// Surfaces the individual files inside a download Sonarr/Radarr couldn't auto-import (e.g.
// trackedDownloadState=importBlocked - a season-pack release where the service can't work out
// every episode, or a rejected quality/language), so the user can review its own guess per file
// and confirm (or exclude) it rather than the file sitting stuck in the queue forever. Field
// names/shapes here are grounded in a live Sonarr v3 (4.0.18) response, not just API docs - see
// the ManualImport DTOs' kdoc for the exact request that produced them.
//
// The GET response's `quality`/`languages` objects are round-tripped back verbatim in the POST
// command below (Sonarr/Radarr's own web UI does the same) - Jollyfin doesn't attempt to
// second-guess quality/language detection, only which episode(s)/movie a file maps to and
// whether to import it at all.

@Serializable data class PvrQualityDetail(val id: Int = 0, val name: String? = null)

@Serializable
data class PvrQualityRevision(
    val version: Int = 1,
    val real: Int = 0,
    val isRepack: Boolean = false,
)

@Serializable
data class PvrQualityInfo(
    val quality: PvrQualityDetail? = null,
    val revision: PvrQualityRevision? = null,
)

@Serializable data class PvrLanguage(val id: Int = 0, val name: String? = null)

@Serializable data class PvrRejection(val reason: String, val type: String? = null)

@Serializable
data class SonarrManualImportEpisode(
    val id: Int,
    val episodeNumber: Int = 0,
    val title: String? = null,
)

@Serializable data class SonarrManualImportSeriesRef(val id: Int = 0, val title: String? = null)

@Serializable
data class SonarrManualImportItem(
    // Not a stable identifier across requests (Sonarr recomputes it per call) - only used to
    // round-trip a file's own guessed mapping back in the ManualImport command below, same as
    // Sonarr's own web UI does.
    val id: Int,
    val path: String? = null,
    val folderName: String? = null,
    val name: String? = null,
    val size: Long = 0L,
    val seasonNumber: Int? = null,
    val series: SonarrManualImportSeriesRef? = null,
    val episodes: List<SonarrManualImportEpisode> = emptyList(),
    val quality: PvrQualityInfo? = null,
    val languages: List<PvrLanguage> = emptyList(),
    val downloadId: String? = null,
    val rejections: List<PvrRejection> = emptyList(),
)

@Serializable
data class RadarrManualImportItem(
    val id: Int,
    val path: String? = null,
    val folderName: String? = null,
    val name: String? = null,
    val size: Long = 0L,
    val movie: RadarrMovie? = null,
    val quality: PvrQualityInfo? = null,
    val languages: List<PvrLanguage> = emptyList(),
    val downloadId: String? = null,
    val rejections: List<PvrRejection> = emptyList(),
)

@Serializable
data class SonarrManualImportFile(
    val id: Int,
    val path: String? = null,
    val folderName: String? = null,
    val seriesId: Int? = null,
    val episodeIds: List<Int> = emptyList(),
    val quality: PvrQualityInfo? = null,
    val languages: List<PvrLanguage> = emptyList(),
    val downloadId: String? = null,
)

@Serializable
data class RadarrManualImportFile(
    val id: Int,
    val path: String? = null,
    val folderName: String? = null,
    val movieId: Int? = null,
    val quality: PvrQualityInfo? = null,
    val languages: List<PvrLanguage> = emptyList(),
    val downloadId: String? = null,
)

@Serializable
data class SonarrManualImportCommandRequest(
    val name: String = "ManualImport",
    val files: List<SonarrManualImportFile>,
    val importMode: String = "auto",
)

@Serializable
data class RadarrManualImportCommandRequest(
    val name: String = "ManualImport",
    val files: List<RadarrManualImportFile>,
    val importMode: String = "auto",
)

// endregion
