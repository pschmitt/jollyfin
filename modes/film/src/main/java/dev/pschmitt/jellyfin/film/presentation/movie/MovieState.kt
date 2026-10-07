package dev.pschmitt.jellyfin.film.presentation.movie

import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.presentation.search.ReleasePickerState
import dev.pschmitt.jellyfin.models.JollyfinItemPerson
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.PvrQueueEntry
import dev.pschmitt.jellyfin.models.QueueStatus
import dev.pschmitt.jellyfin.models.VideoMetadata

data class MovieState(
    val movie: JollyfinMovie? = null,
    val videoMetadata: VideoMetadata? = null,
    val actors: List<JollyfinItemPerson> = emptyList(),
    val director: JollyfinItemPerson? = null,
    val writers: List<JollyfinItemPerson> = emptyList(),
    val dateFormat: String = "system",
    val releasePicker: ReleasePickerState? = null,
    val queueStatus: QueueStatus? = null,
    // Every entry in this movie's own duplicate cluster (see PvrQueueEntry.duplicateGroupKey) -
    // unlike [queueStatus], which is already collapsed to one. Only used to seed the manage-import
    // sheet with every duplicate's candidates; display still reads [queueStatus].
    val queueEntries: List<PvrQueueEntry> = emptyList(),
    // Gates the search button - no point offering a Radarr search that can only fail with a
    // toast when Radarr isn't (fully) configured.
    val radarrConfigured: Boolean = false,
    // Gates the "also remove from Radarr/Seerr" cascade option on the delete-from-Jellyfin
    // dialog - shown when either service is configured, independently of the other.
    val seerrConfigured: Boolean = false,
    // Services whose web UI is reachable (enabled + base URL), offered as "Open in ..." entries
    // in the overflow menu (JF-94).
    val webUiServices: List<PvrService> = emptyList(),
    // Whether the current Jellyfin user's policy allows deleting media at all - gates whether
    // "Delete from Jellyfin" is shown in the overflow menu, rather than showing it and having the
    // delete fail with a permissions error.
    val canDelete: Boolean = false,
    // Drives the pull-to-refresh spinner - separate from `movie == null` (first load, full-screen
    // spinner instead) since a refresh keeps showing the existing content underneath.
    val isRefreshing: Boolean = false,
    val error: Exception? = null,
)
