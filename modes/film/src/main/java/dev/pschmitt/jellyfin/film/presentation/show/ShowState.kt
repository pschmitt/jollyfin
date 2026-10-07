package dev.pschmitt.jellyfin.film.presentation.show

import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.models.CalendarEntry
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinItemPerson
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.JollyfinShow
import dev.pschmitt.jellyfin.models.UpcomingSeason
import dev.pschmitt.jellyfin.repository.ExistingAutoDownloadScope

data class ShowState(
    val show: JollyfinShow? = null,
    val nextUp: JollyfinEpisode? = null,
    val nextAiring: CalendarEntry? = null,
    val seasons: List<JollyfinSeason> = emptyList(),
    // Sonarr-known seasons of this show not yet in the Jellyfin library - always empty unless
    // Sonarr is configured and the show is matched (see SeasonEpisodesRepository). Rendered as
    // dimmed placeholder cards after the real seasons, see ShowScreen.
    val missingSeasons: List<UpcomingSeason> = emptyList(),
    // Season numbers among [missingSeasons] that have a pending "download when available" request
    // queued (see PendingDownloadRequestDto) - drives the queued/not-queued icon state on
    // UpcomingSeasonCard.
    val queuedSeasonNumbers: Set<Int> = emptySet(),
    val actors: List<JollyfinItemPerson> = emptyList(),
    val director: JollyfinItemPerson? = null,
    val writers: List<JollyfinItemPerson> = emptyList(),
    val autoDownloadEnabled: Boolean = false,
    val existingScope: ExistingAutoDownloadScope = ExistingAutoDownloadScope(),
    val hasDownloads: Boolean = false,
    val downloadsSizeBytes: Long = 0L,
    // Total episode count across every season - drives the aggregate Info dialog (there's no
    // single video file's metadata to show at the show level, unlike Movie/Episode).
    val episodeCount: Int = 0,
    // The show's tvdb/tmdb ids, fetched once per loadShow() - tvdbId to resolve missingSeasons,
    // tmdbId to open the Seerr detail view when the user taps a missing-season placeholder.
    val seriesTvdbId: String? = null,
    val seriesTmdbId: Int? = null,
    // Gates the "also remove from Sonarr/Seerr" cascade option on the delete-from-Jellyfin
    // dialog - shown when either service is configured, independently of the other.
    val sonarrConfigured: Boolean = false,
    val seerrConfigured: Boolean = false,
    // Services whose web UI is reachable (enabled + base URL), offered as "Open in ..." entries
    // in the overflow menu (JF-94).
    val webUiServices: List<PvrService> = emptyList(),
    // Whether the current Jellyfin user's policy allows deleting media at all - gates whether
    // "Delete from Jellyfin" is shown in the overflow menu, rather than showing it and having the
    // delete fail with a permissions error.
    val canDelete: Boolean = false,
    // Drives the pull-to-refresh spinner - separate from `show == null` (first load, full-screen
    // spinner instead) since a refresh keeps showing the existing content underneath.
    val isRefreshing: Boolean = false,
    val error: Exception? = null,
)
