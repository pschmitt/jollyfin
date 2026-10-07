package dev.pschmitt.jellyfin.presentation.film

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.film.presentation.seerr.SeerrMediaAction
import dev.pschmitt.jellyfin.film.presentation.seerr.SeerrMediaEvent
import dev.pschmitt.jellyfin.film.presentation.seerr.SeerrMediaState
import dev.pschmitt.jellyfin.film.presentation.seerr.SeerrMediaViewModel
import dev.pschmitt.jellyfin.models.QueueItemStatus
import dev.pschmitt.jellyfin.models.SeerrMediaDetail
import dev.pschmitt.jellyfin.models.SeerrMediaStatus
import dev.pschmitt.jellyfin.models.SeerrMediaType
import dev.pschmitt.jellyfin.presentation.components.ErrorDialog
import dev.pschmitt.jellyfin.presentation.film.components.ErrorCard
import dev.pschmitt.jellyfin.presentation.film.components.ItemActionButton
import dev.pschmitt.jellyfin.presentation.film.components.ItemHeader
import dev.pschmitt.jellyfin.presentation.film.components.ItemMetaRow
import dev.pschmitt.jellyfin.presentation.film.components.ItemOverflowMenu
import dev.pschmitt.jellyfin.presentation.film.components.ManualImportSheet
import dev.pschmitt.jellyfin.presentation.film.components.OpenInWebUiMenuItems
import dev.pschmitt.jellyfin.presentation.film.components.OverviewText
import dev.pschmitt.jellyfin.presentation.film.components.PvrQueueDownloadCard
import dev.pschmitt.jellyfin.presentation.film.components.PvrSearchButton
import dev.pschmitt.jellyfin.presentation.film.components.ReleasePickerSheet
import dev.pschmitt.jellyfin.presentation.film.components.SeerrStatusChip
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.presentation.utils.rememberSafePadding
import dev.pschmitt.jellyfin.pvr.PvrWebUiTarget
import dev.pschmitt.jellyfin.utils.ObserveAsEvents
import dev.pschmitt.jellyfin.utils.formatCalendarDate
import dev.pschmitt.jellyfin.utils.formatCalendarTime
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Detail view for a Seerr search result that is not (fully) in the library yet - metadata plus the
 * request/unrequest actions. Identified by TMDB id instead of a Jellyfin item id.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SeerrMediaScreen(
    tmdbId: Int,
    mediaType: SeerrMediaType,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    sonarrEpisodeId: Int? = null,
    // Sonarr-derived, already timezone-localized air date/time - see NavigationRoot's
    // SeerrMediaRoute doc. Null when this screen was reached some other way (e.g. search).
    airDate: LocalDate? = null,
    airTime: LocalTime? = null,
    navigateToShow: (UUID?) -> Unit = {},
    navigateToSeason: (Int, UUID?) -> Unit = { _, _ -> },
    navigateToWebUi: (PvrService, PvrWebUiTarget) -> Unit = { _, _ -> },
    navigateBack: () -> Unit,
    viewModel: SeerrMediaViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val manualImportState by viewModel.manualImport.state.collectAsStateWithLifecycle()

    // Request and cancel share their failure event, so remember which label to show for it.
    var lastActionWasCancel by remember { mutableStateOf(false) }

    LaunchedEffect(tmdbId, mediaType, seasonNumber, episodeNumber, sonarrEpisodeId) {
        viewModel.loadDetail(
            tmdbId,
            mediaType,
            seasonNumber,
            episodeNumber,
            sonarrEpisodeId,
            airDate,
            airTime,
        )
    }

    ObserveAsEvents(viewModel.events) { event ->
        val message =
            when (event) {
                is SeerrMediaEvent.Requested ->
                    context.getString(CoreR.string.discover_requested_toast, event.title)
                is SeerrMediaEvent.RequestCancelled ->
                    context.getString(CoreR.string.seerr_request_cancelled_toast, event.title)
                is SeerrMediaEvent.SearchTriggered ->
                    context.getString(
                        when (event.source) {
                            dev.pschmitt.jellyfin.models.PvrSource.SONARR ->
                                CoreR.string.sonarr_search_started_toast
                            dev.pschmitt.jellyfin.models.PvrSource.RADARR ->
                                CoreR.string.radarr_search_started_toast
                        }
                    )
                is SeerrMediaEvent.SearchFailed ->
                    context.getString(
                        when (event.source) {
                            dev.pschmitt.jellyfin.models.PvrSource.SONARR ->
                                CoreR.string.sonarr_search_failed_toast
                            dev.pschmitt.jellyfin.models.PvrSource.RADARR ->
                                CoreR.string.radarr_search_failed_toast
                        },
                        event.message ?: context.getString(CoreR.string.unknown_error),
                    )
                is SeerrMediaEvent.ReleaseGrabbed ->
                    context.getString(CoreR.string.release_grabbed_toast)
                is SeerrMediaEvent.ActionFailed ->
                    context.getString(
                        if (lastActionWasCancel) {
                            CoreR.string.seerr_cancel_failed_toast
                        } else {
                            CoreR.string.discover_request_failed_toast
                        },
                        event.message ?: context.getString(CoreR.string.unknown_error),
                    )
            }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    SeerrMediaScreenLayout(
        state = state,
        navigateToShow = navigateToShow,
        navigateToSeason = navigateToSeason,
        onOpenWebUi = { service ->
            navigateToWebUi(
                service,
                PvrWebUiTarget(tmdbId = tmdbId, isMovie = mediaType == SeerrMediaType.MOVIE),
            )
        },
        onAction = { action ->
            when (action) {
                is SeerrMediaAction.OnRequest -> lastActionWasCancel = false
                is SeerrMediaAction.OnCancelRequest -> lastActionWasCancel = true
                is SeerrMediaAction.OnBackClick -> navigateBack()
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onManageImportClick = viewModel::openManualImportForCurrentItem,
    )

    manualImportState?.let { manualImport ->
        ManualImportSheet(
            state = manualImport,
            onSelectEntry = viewModel.manualImport::selectEntry,
            onToggleSelection = viewModel.manualImport::toggleSelection,
            onConfirm = { viewModel.manualImport.confirm() },
            onReject = { removeFromClient, blocklist ->
                viewModel.manualImport.reject(removeFromClient, blocklist)
            },
            onDismissRequest = viewModel.manualImport::close,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SeerrMediaScreenLayout(
    state: SeerrMediaState,
    navigateToShow: (UUID?) -> Unit,
    navigateToSeason: (Int, UUID?) -> Unit,
    onAction: (SeerrMediaAction) -> Unit,
    onManageImportClick: () -> Unit = {},
    onOpenWebUi: (PvrService) -> Unit = {},
) {
    val safePadding = rememberSafePadding()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val scrollState = rememberScrollState()

    var showCancelDialog by remember { mutableStateOf(false) }
    var showErrorDialog by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.detail != null -> {
                val detail = state.detail!!
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                    ItemHeader(
                        backdropUrl =
                            detail.episode?.stillUrl
                                ?: detail.backdropUrl
                                ?: detail.season?.posterUrl
                                ?: detail.posterUrl
                    )
                    Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(
                                text = detail.episode?.title ?: detail.title,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.webUiServices.isNotEmpty()) {
                                ItemOverflowMenu { closeMenu ->
                                    OpenInWebUiMenuItems(
                                        services = state.webUiServices,
                                        closeMenu = closeMenu,
                                        onClick = onOpenWebUi,
                                    )
                                }
                            }
                        }
                        // Same breadcrumb style as EpisodeScreen's series-name/season-episode
                        // lines (plain clickable Text in labelLarge, not a TextButton) - this
                        // screen's hierarchy is more variable (plain movie, show only, show +
                        // season, show + season + episode), so the exact rows shown differ, but
                        // each one that IS shown matches Episode's look exactly.
                        detail.episode?.let { episode ->
                            Text(
                                text = detail.title,
                                modifier =
                                    Modifier.clickable { navigateToShow(state.jellyfinShowId) },
                                maxLines = 1,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = detail.season?.title ?: "Season ${episode.seasonNumber}",
                                modifier =
                                    Modifier.clickable {
                                        navigateToSeason(
                                            episode.seasonNumber,
                                            state.jellyfinSeasonId,
                                        )
                                    },
                                maxLines = 1,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                            ?: detail.season?.let {
                                Text(
                                    text = detail.title,
                                    modifier =
                                        Modifier.clickable { navigateToShow(state.jellyfinShowId) },
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        // Seerr only tracks request/availability status at the season/show level,
                        // so AVAILABLE/PARTIALLY_AVAILABLE don't mean anything precise for a
                        // single episode - an episode is either in the library or it isn't. Once
                        // there's an episode in view, resolve the chip from that binary fact
                        // instead of projecting the season's aggregate status onto it; the
                        // request-lifecycle states (NOT_REQUESTED/PENDING/PROCESSING) still
                        // describe the season accurately either way, so those pass through as-is.
                        val displayStatus =
                            when {
                                detail.episode == null -> detail.status
                                state.jellyfinEpisodeId != null -> SeerrMediaStatus.AVAILABLE
                                detail.status == SeerrMediaStatus.AVAILABLE ||
                                    detail.status == SeerrMediaStatus.PARTIALLY_AVAILABLE -> null
                                else -> detail.status
                            }
                        // The chip renders NOT_REQUESTED as "Requested" (its just-requested
                        // marker), so only show it once there actually is a request or status.
                        val showStatusChip =
                            displayStatus != null &&
                                (displayStatus != SeerrMediaStatus.NOT_REQUESTED ||
                                    detail.cancellableRequestIds.isNotEmpty())
                        ItemMetaRow(
                            dateText =
                                seerrDateText(detail, state.knownAirDate, state.knownAirTime),
                            runtimeTicks = (detail.runtimeMinutes ?: 0) * 600_000_000L,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (showStatusChip) SeerrStatusChip(status = displayStatus)
                        }
                        state.queueStatus?.let { queueStatus ->
                            Spacer(Modifier.height(MaterialTheme.spacings.small))
                            PvrQueueDownloadCard(
                                status = queueStatus,
                                onCardClick =
                                    queueStatus.status
                                        .takeIf {
                                            it == QueueItemStatus.WARNING ||
                                                it == QueueItemStatus.FAILED
                                        }
                                        ?.let { { onManageImportClick() } },
                            )
                        }
                        (detail.episode?.overview ?: detail.season?.overview ?: detail.overview)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { overview ->
                                Spacer(Modifier.height(MaterialTheme.spacings.medium))
                                OverviewText(text = overview, maxCollapsedLines = 5)
                            }
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        // Same tile shell as ItemButtonsBar's action row (Movie/Episode/Show/
                        // Season) - a wrapping FlowRow of uniform icon-above-label tiles - even
                        // though this screen's action set (Request/Cancel/Search, no Play/
                        // Download/overflow) is distinct enough that reusing ItemButtonsBar itself
                        // isn't a fit.
                        FlowRow(
                            horizontalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                            verticalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            if (
                                detail.status == SeerrMediaStatus.NOT_REQUESTED &&
                                    detail.cancellableRequestIds.isEmpty()
                            ) {
                                ItemActionButton(
                                    icon = painterResource(CoreR.drawable.ic_seerr),
                                    iconTint = Color.Unspecified,
                                    // Seerr has no per-episode requesting, so a season or episode
                                    // view always ends up requesting the season - make that
                                    // explicit rather than implying the whole show is requested.
                                    label =
                                        stringResource(
                                            if (detail.season != null) {
                                                CoreR.string.discover_request_season
                                            } else {
                                                CoreR.string.discover_request
                                            }
                                        ),
                                    onClick = {
                                        if (!state.isSubmitting)
                                            onAction(SeerrMediaAction.OnRequest)
                                    },
                                )
                            }
                            if (detail.cancellableRequestIds.isNotEmpty()) {
                                ItemActionButton(
                                    icon = painterResource(CoreR.drawable.ic_seerr),
                                    iconTint = Color.Unspecified,
                                    label = stringResource(CoreR.string.seerr_cancel_request),
                                    onClick = { if (!state.isSubmitting) showCancelDialog = true },
                                    contentColor = MaterialTheme.colorScheme.error,
                                )
                            }
                            // Not in the library yet - there's nothing to play, but TMDB usually
                            // has a trailer, so offer that instead while the request works its
                            // way through Sonarr/Radarr.
                            detail.trailerUrl?.let { trailerUrl ->
                                ItemActionButton(
                                    icon = painterResource(CoreR.drawable.ic_film),
                                    label = stringResource(CoreR.string.trailer),
                                    onClick = {
                                        try {
                                            uriHandler.openUri(trailerUrl)
                                        } catch (e: IllegalArgumentException) {
                                            Toast.makeText(
                                                    context,
                                                    e.localizedMessage,
                                                    Toast.LENGTH_SHORT,
                                                )
                                                .show()
                                        }
                                    },
                                )
                            }
                            if (state.pvrSearchConfigured || state.manualPvrSearchAvailable) {
                                PvrSearchButton(
                                    service =
                                        if (detail.mediaType == SeerrMediaType.TV) {
                                            dev.pschmitt.jellyfin.models.PvrSource.SONARR
                                        } else {
                                            dev.pschmitt.jellyfin.models.PvrSource.RADARR
                                        },
                                    onAutomaticSearch = {
                                        onAction(SeerrMediaAction.OnAutomaticSearchInPvr)
                                    },
                                    onManualSearch = {
                                        onAction(SeerrMediaAction.OnOpenReleasePicker)
                                    },
                                    label = stringResource(CoreR.string.search),
                                )
                            }
                        }
                        // Show-level view only - a season/episode view is already scoped to one
                        // season, so there's nothing to list. This is the only way to reach a
                        // season-scoped Seerr view directly from the show (the other path is via
                        // an episode's "back to season" link, further downstream).
                        if (
                            detail.mediaType == SeerrMediaType.TV &&
                                detail.season == null &&
                                detail.episode == null
                        ) {
                            detail.numberOfSeasons
                                ?.takeIf { it > 0 }
                                ?.let { numberOfSeasons ->
                                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                                    Text(
                                        text = stringResource(CoreR.string.seasons),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                                    Column {
                                        for (seasonNumber in 1..numberOfSeasons) {
                                            val seasonStatus =
                                                detail.seasons
                                                    .firstOrNull { it.seasonNumber == seasonNumber }
                                                    ?.status
                                            SeerrSeasonRow(
                                                seasonNumber = seasonNumber,
                                                status = seasonStatus,
                                                // No per-row Jellyfin season id is resolved here
                                                // (the
                                                // show-level view only resolves one show/season
                                                // pair
                                                // total, not all seasons at once) - always route
                                                // through a fresh season-scoped SeerrMediaRoute
                                                // load,
                                                // same as navigateToSeason already does when it has
                                                // no
                                                // Jellyfin season id to jump to directly.
                                                onClick = { navigateToSeason(seasonNumber, null) },
                                            )
                                        }
                                    }
                                }
                        }
                    }
                    Spacer(Modifier.height(paddingBottom))
                }
            }
            state.error != null -> {
                ErrorCard(
                    onShowStacktrace = { showErrorDialog = true },
                    onRetryClick = { onAction(SeerrMediaAction.OnRetryClick) },
                    modifier =
                        Modifier.align(Alignment.Center)
                            .padding(start = paddingStart, end = paddingEnd),
                )
                if (showErrorDialog) {
                    ErrorDialog(
                        exception = state.error!!,
                        onDismissRequest = { showErrorDialog = false },
                    )
                }
            }
            else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // ItemTopBar-style floating back button - the screen has no top app bar of its own.
        IconButton(
            onClick = { onAction(SeerrMediaAction.OnBackClick) },
            modifier =
                Modifier.padding(
                        start = safePadding.start + MaterialTheme.spacings.small,
                        top = safePadding.top + MaterialTheme.spacings.small,
                    )
                    .alpha(0.7f),
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White,
                ),
        ) {
            Icon(painter = painterResource(CoreR.drawable.ic_arrow_left), contentDescription = null)
        }
    }

    if (showCancelDialog) {
        state.detail?.let { detail ->
            AlertDialog(
                title = { Text(text = stringResource(CoreR.string.seerr_cancel_request)) },
                text = {
                    Text(
                        text =
                            stringResource(CoreR.string.seerr_cancel_request_message, detail.title)
                    )
                },
                onDismissRequest = { showCancelDialog = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onAction(SeerrMediaAction.OnCancelRequest)
                            showCancelDialog = false
                        }
                    ) {
                        Text(
                            text = stringResource(CoreR.string.seerr_cancel_request),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCancelDialog = false }) {
                        Text(text = stringResource(CoreR.string.close))
                    }
                },
            )
        }
    }

    state.releasePicker?.let { releasePicker ->
        ReleasePickerSheet(
            state = releasePicker,
            onGrab = { onAction(SeerrMediaAction.GrabRelease(it)) },
            onDismissRequest = { onAction(SeerrMediaAction.DismissReleasePicker) },
        )
    }
}

/**
 * One row in the show-level season list: "Season N" plus its status chip (omitted when the season
 * has never been touched - matching how the rest of this screen only shows a chip once there's an
 * actual request/status to report, see the show-level chip above). Tapping always navigates into a
 * season-scoped Seerr view, letting that screen resolve the Jellyfin ids itself.
 */
@Composable
private fun SeerrSeasonRow(seasonNumber: Int, status: SeerrMediaStatus?, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(vertical = MaterialTheme.spacings.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(CoreR.string.season_number, seasonNumber),
            style = MaterialTheme.typography.bodyLarge,
        )
        status?.let { SeerrStatusChip(status = it) }
    }
}

/**
 * The single date-ish segment [ItemMetaRow] shows - mirrors what MovieScreen/EpisodeScreen pass as
 * their own `dateText` (a premiere/air date), rather than the old bespoke meta line's full "title ·
 * S03E06 · date" or "year · type · runtime · genres" strings, which don't fit [ItemMetaRow]'s shape
 * (it only ever shows one date-like segment, runtime, rating, and community rating - see
 * MovieScreen/EpisodeScreen's own calls for the pattern this now matches).
 */
@Composable
private fun seerrDateText(
    detail: SeerrMediaDetail,
    knownAirDate: LocalDate?,
    knownAirTime: LocalTime?,
): String? {
    detail.episode?.let { episode ->
        // Prefer the already timezone-localized Sonarr air date/time (matches what the Season
        // screen's upcoming-episode row just showed) over Seerr/TMDB's plain, unlocalized
        // air_date string, which can land on a different calendar day - see SeasonAction.
        return knownAirDate?.let { date ->
            formatCalendarDate(date) +
                (knownAirTime?.let { time -> ", ${formatCalendarTime(time)}" } ?: "")
        } ?: episode.airDate?.take(10)
    }
    return detail.year?.toString()
}

@PreviewScreenSizes
@Composable
private fun SeerrMediaScreenLayoutPreview() {
    JollyfinTheme {
        SeerrMediaScreenLayout(
            state =
                SeerrMediaState(
                    detail =
                        SeerrMediaDetail(
                            tmdbId = 157336,
                            mediaType = SeerrMediaType.MOVIE,
                            title = "Interstellar",
                            year = 2014,
                            overview =
                                "The adventures of a group of explorers who make use of a " +
                                    "newly discovered wormhole to surpass the limitations on " +
                                    "human space travel and conquer the vast distances involved " +
                                    "in an interstellar voyage.",
                            posterUrl = null,
                            backdropUrl = null,
                            genres = listOf("Adventure", "Drama", "Science Fiction"),
                            runtimeMinutes = 169,
                            numberOfSeasons = null,
                            status = SeerrMediaStatus.NOT_REQUESTED,
                            cancellableRequestIds = emptyList(),
                        )
                ),
            navigateToShow = {},
            navigateToSeason = { _, _ -> },
            onAction = {},
        )
    }
}
