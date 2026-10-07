package dev.pschmitt.jellyfin.film.presentation.season

import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.presentation.search.ReleasePickerState
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.QueueStatus
import dev.pschmitt.jellyfin.models.UpcomingEpisode
import dev.pschmitt.jellyfin.repository.ExistingAutoDownloadScope
import dev.pschmitt.jellyfin.utils.DownloadProgress
import java.util.UUID

data class SeasonState(
    val season: JollyfinSeason? = null,
    val episodes: List<JollyfinEpisode> = emptyList(),
    // Sonarr-known episodes of this season not yet in the Jellyfin library - always empty unless
    // Sonarr is configured and the show is matched (see SeasonEpisodesRepository). Rendered as
    // greyed-out placeholder rows after the real episodes, see SeasonScreen.
    val upcomingEpisodes: List<UpcomingEpisode> = emptyList(),
    // Episode numbers among [upcomingEpisodes] that have a pending "download when available"
    // request queued (see PendingDownloadRequestDto) - drives the queued/not-queued icon state on
    // UpcomingEpisodeCard.
    val queuedEpisodeNumbers: Set<Int> = emptySet(),
    val autoDownloadEnabled: Boolean = false,
    val existingScope: ExistingAutoDownloadScope = ExistingAutoDownloadScope(),
    val hasDownloads: Boolean = false,
    val downloadsSizeBytes: Long = 0L,
    val downloadProgress: Map<UUID, DownloadProgress> = emptyMap(),
    val queueStatus: Map<UUID, QueueStatus> = emptyMap(),
    // The show's tvdb id, fetched once per loadSeason() - reused both for upcomingEpisodes and to
    // resolve a Sonarr episode id on demand when the user triggers a search on a real episode row.
    val seriesTvdbId: String? = null,
    val seriesTmdbId: Int? = null,
    // Services whose web UI is reachable, offered as "Open in ..." overflow entries (JF-94) -
    // Sonarr/Seerr open the series' own page, there being no per-episode/season page.
    val webUiServices: List<PvrService> = emptyList(),
    // Gates the per-episode search buttons - no point offering a Sonarr search that can only
    // fail with a toast when Sonarr isn't (fully) configured.
    val sonarrConfigured: Boolean = false,
    val releasePicker: ReleasePickerState? = null,
    // Mirrors AppPreferences.autoDeleteWatched/autoDeleteWatchedHours - see
    // DownloadsState.autoDeleteWatchedEnabled for the identical rationale (drives the "marked for
    // deletion" badge on downloaded episode rows).
    val autoDeleteWatchedEnabled: Boolean = false,
    val autoDeleteWatchedHours: Int = 24,
    // Drives the pull-to-refresh spinner - separate from `season == null` (first load, full-
    // screen spinner instead) since a refresh keeps showing the existing content underneath.
    val isRefreshing: Boolean = false,
    val error: Exception? = null,
)
