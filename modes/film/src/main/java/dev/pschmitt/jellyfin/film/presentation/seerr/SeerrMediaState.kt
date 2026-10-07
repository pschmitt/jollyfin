package dev.pschmitt.jellyfin.film.presentation.seerr

import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.presentation.search.ReleasePickerState
import dev.pschmitt.jellyfin.models.PvrQueueEntry
import dev.pschmitt.jellyfin.models.QueueStatus
import dev.pschmitt.jellyfin.models.SeerrMediaDetail
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class SeerrMediaState(
    val detail: SeerrMediaDetail? = null,
    // Sonarr-derived, already timezone-localized air date/time, passed in from the Season
    // screen's upcoming-episode row when known - see SeerrMediaViewModel.loadDetail.
    val knownAirDate: LocalDate? = null,
    val knownAirTime: LocalTime? = null,
    val isLoading: Boolean = false,
    // A request/cancel round-trip is in flight - disables the action button meanwhile.
    val isSubmitting: Boolean = false,
    val pvrSearchConfigured: Boolean = false,
    val manualPvrSearchAvailable: Boolean = false,
    // Services whose web UI is reachable, offered as "Open in ..." overflow entries (JF-94) - the
    // only way to reach e.g. a not-yet-downloaded episode's series in Sonarr.
    val webUiServices: List<PvrService> = emptyList(),
    val queueStatus: QueueStatus? = null,
    // Every entry in this item's own duplicate cluster (see PvrQueueEntry.duplicateGroupKey) -
    // unlike [queueStatus], which is already collapsed to one. Mirrors MovieState.queueEntries.
    val queueEntries: List<PvrQueueEntry> = emptyList(),
    val jellyfinShowId: UUID? = null,
    val jellyfinSeasonId: UUID? = null,
    // Set only when detail.episode is non-null and this exact episode (not just its season) is
    // matched in the library - the one genuine binary "is THIS episode available" signal, since
    // Seerr only tracks request/availability status at the season/show level.
    val jellyfinEpisodeId: UUID? = null,
    val releasePicker: ReleasePickerState? = null,
    val error: Exception? = null,
)
