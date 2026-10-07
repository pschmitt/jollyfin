package dev.pschmitt.jellyfin.film.presentation.media

import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.models.JollyfinCollection

data class MediaState(
    val libraries: List<JollyfinCollection> = emptyList(),
    val isLoading: Boolean = false,
    val error: Exception? = null,
    // Whether Sonarr or Radarr is enabled *and* has a base URL configured - the Calendar tab has
    // nothing useful to show otherwise, so NavigationRoot uses this to hide it entirely rather
    // than showing an always-empty screen.
    val showCalendarTab: Boolean = false,
    // Services whose web UI is reachable (enabled + base URL) - each gets an (opt-in) navbar tab.
    val webUiServices: List<PvrService> = emptyList(),
)
