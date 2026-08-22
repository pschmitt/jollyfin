package dev.pschmitt.jellyfin.presentation.film

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.PlayerActivity
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.core.presentation.delete.DeleteItemEvent
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSelection
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSizeEstimate
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloaderState
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyShow
import dev.pschmitt.jellyfin.core.presentation.search.SearchEvent
import dev.pschmitt.jellyfin.film.presentation.show.ShowAction
import dev.pschmitt.jellyfin.film.presentation.show.ShowState
import dev.pschmitt.jellyfin.film.presentation.show.ShowViewModel
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.RemoteDeviceInfo
import dev.pschmitt.jellyfin.models.UpcomingSeason
import dev.pschmitt.jellyfin.presentation.film.components.ActorsRow
import dev.pschmitt.jellyfin.presentation.film.components.AggregateInfoDialog
import dev.pschmitt.jellyfin.presentation.film.components.ClearDownloadsDialog
import dev.pschmitt.jellyfin.presentation.film.components.DeleteItemDialog
import dev.pschmitt.jellyfin.presentation.film.components.Direction
import dev.pschmitt.jellyfin.presentation.film.components.InfoText
import dev.pschmitt.jellyfin.presentation.film.components.ItemActionButton
import dev.pschmitt.jellyfin.presentation.film.components.ItemButtonsBar
import dev.pschmitt.jellyfin.presentation.film.components.ItemCard
import dev.pschmitt.jellyfin.presentation.film.components.ItemDetailScaffold
import dev.pschmitt.jellyfin.presentation.film.components.ItemHeader
import dev.pschmitt.jellyfin.presentation.film.components.ItemMetaRow
import dev.pschmitt.jellyfin.presentation.film.components.ItemOverflowMenu
import dev.pschmitt.jellyfin.presentation.film.components.ItemPoster
import dev.pschmitt.jellyfin.presentation.film.components.OverviewText
import dev.pschmitt.jellyfin.presentation.film.components.PlayOverlayButton
import dev.pschmitt.jellyfin.presentation.film.components.UpcomingSeasonCard
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.presentation.utils.rememberSafePadding
import dev.pschmitt.jellyfin.utils.ObserveAsEvents
import dev.pschmitt.jellyfin.utils.formatBinaryFileSize
import dev.pschmitt.jellyfin.utils.formatCalendarDate
import dev.pschmitt.jellyfin.utils.formatCalendarTime
import dev.pschmitt.jellyfin.utils.getShowDateString
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun ShowScreen(
    showId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToItem: (item: JollyfinItem) -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    navigateToSeerr: (tmdbId: Int, seasonNumber: Int) -> Unit,
    navigateToSettings: () -> Unit,
    viewModel: ShowViewModel = hiltViewModel(),
) {
    val androidContext = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadShow(showId = showId) }

    ObserveAsEvents(viewModel.deleteEvents) { event ->
        when (event) {
            is DeleteItemEvent.Deleted -> {
                Toast.makeText(androidContext, CoreR.string.item_deleted_toast, Toast.LENGTH_SHORT)
                    .show()
                navigateBack()
            }
            is DeleteItemEvent.Failed -> {
                Toast.makeText(
                        androidContext,
                        androidContext.getString(
                            CoreR.string.item_delete_failed_toast,
                            event.message ?: androidContext.getString(CoreR.string.unknown_error),
                        ),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }
    }

    ObserveAsEvents(viewModel.searchEvents) { event ->
        val message =
            when (event) {
                is SearchEvent.SearchTriggered ->
                    androidContext.getString(CoreR.string.search_triggered_toast)
                is SearchEvent.ReleaseGrabbed ->
                    androidContext.getString(CoreR.string.release_grabbed_toast)
                is SearchEvent.Failed ->
                    androidContext.getString(
                        CoreR.string.search_failed_toast,
                        event.message ?: androidContext.getString(CoreR.string.unknown_error),
                    )
            }
        Toast.makeText(androidContext, message, Toast.LENGTH_SHORT).show()
    }

    ShowScreenLayout(
        state = state,
        getSeasonSize = viewModel::getUndownloadedEpisodeSize,
        getOtherDevices = viewModel::getOtherDevices,
        onRefresh = { viewModel.loadShow(showId = showId) },
        onAction = { action ->
            when (action) {
                is ShowAction.Play -> {
                    val intent = Intent(androidContext, PlayerActivity::class.java)
                    intent.putExtra("itemId", showId.toString())
                    intent.putExtra("itemKind", BaseItemKind.SERIES.serialName)
                    androidContext.startActivity(intent)
                }
                is ShowAction.PlayTrailer -> {
                    try {
                        uriHandler.openUri(action.trailer)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(androidContext, e.localizedMessage, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
                is ShowAction.MarkAsPlayed ->
                    Toast.makeText(
                            androidContext,
                            CoreR.string.marked_as_played_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is ShowAction.UnmarkAsPlayed ->
                    Toast.makeText(
                            androidContext,
                            CoreR.string.marked_as_unplayed_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is ShowAction.MarkAsFavorite ->
                    Toast.makeText(
                            androidContext,
                            CoreR.string.added_to_favorites_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is ShowAction.UnmarkAsFavorite ->
                    Toast.makeText(
                            androidContext,
                            CoreR.string.removed_from_favorites_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is ShowAction.OnBackClick -> navigateBack()
                is ShowAction.OnHomeClick -> navigateHome()
                is ShowAction.OnSettingsClick -> navigateToSettings()
                is ShowAction.NavigateToItem -> navigateToItem(action.item)
                is ShowAction.NavigateToPerson -> navigateToPerson(action.personId)
                is ShowAction.NavigateToSeerr -> navigateToSeerr(action.tmdbId, action.seasonNumber)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@Composable
private fun ShowScreenLayout(
    state: ShowState,
    onAction: (ShowAction) -> Unit,
    onRefresh: () -> Unit = {},
    getSeasonSize: suspend (seasonId: UUID, onlyUnwatched: Boolean) -> DownloadSizeEstimate =
        { _, _ ->
            DownloadSizeEstimate()
        },
    getOtherDevices: suspend () -> List<RemoteDeviceInfo> = { emptyList() },
) {
    val androidContext = LocalContext.current
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val scrollState = rememberScrollState()
    var clearShowDownloadsDialogOpen by remember { mutableStateOf(false) }
    var deleteDialogOpen by remember { mutableStateOf(false) }
    var infoDialogOpen by remember { mutableStateOf(false) }

    ItemDetailScaffold(
        hasBackButton = true,
        hasHomeButton = true,
        onBackClick = { onAction(ShowAction.OnBackClick) },
        onHomeClick = { onAction(ShowAction.OnHomeClick) },
        onSettingsClick = { onAction(ShowAction.OnSettingsClick) },
    ) {
        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
            state.show?.let { show ->
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                    ItemHeader(
                        item = show,
                        scrollState = scrollState,
                        content = {
                            PlayOverlayButton(
                                item = show,
                                onClick = { onAction(ShowAction.Play(startFromBeginning = false)) },
                                enabled = show.canPlay && state.seasons.isNotEmpty(),
                                modifier = Modifier.align(Alignment.Center),
                            )
                        },
                    )
                    Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(
                                text = show.name,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 3,
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.weight(1f),
                            )
                            ItemOverflowMenu { closeMenu ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (show.played) CoreR.string.unmark_as_played
                                                else CoreR.string.mark_as_played
                                            )
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(CoreR.drawable.ic_check),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        closeMenu()
                                        onAction(
                                            if (show.played) ShowAction.UnmarkAsPlayed
                                            else ShowAction.MarkAsPlayed
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (show.favorite) {
                                                    CoreR.string.remove_from_favorites
                                                } else {
                                                    CoreR.string.add_to_favorites
                                                }
                                            )
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    if (show.favorite) {
                                                        CoreR.drawable.ic_heart_filled
                                                    } else {
                                                        CoreR.drawable.ic_heart
                                                    }
                                                ),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        closeMenu()
                                        onAction(
                                            if (show.favorite) ShowAction.UnmarkAsFavorite
                                            else ShowAction.MarkAsFavorite
                                        )
                                    },
                                )
                                // Always offered, regardless of Sonarr configuration/tmdbId
                                // presence - a search that can't resolve a target fails with a
                                // clear toast instead of the entry silently vanishing. No manual/
                                // interactive counterpart at the series level - Sonarr's release
                                // picker is per-episode, not per-series.
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(CoreR.string.search_episode_automatic))
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(CoreR.drawable.ic_sonarr),
                                            contentDescription = null,
                                            tint = Color.Unspecified,
                                        )
                                    },
                                    onClick = {
                                        closeMenu()
                                        onAction(ShowAction.SearchSeriesAutomatic)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(CoreR.string.info)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(CoreR.drawable.ic_info),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        closeMenu()
                                        infoDialogOpen = true
                                    },
                                )
                                if (state.canDelete) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text =
                                                    stringResource(
                                                        CoreR.string.delete_from_jellyfin
                                                    ),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(CoreR.drawable.ic_trash),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            closeMenu()
                                            deleteDialogOpen = true
                                        },
                                    )
                                }
                            }
                        }
                        show.originalTitle?.let { originalTitle ->
                            if (originalTitle != show.name) {
                                Text(
                                    text = originalTitle,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        ItemMetaRow(
                            dateText = getShowDateString(show),
                            runtimeTicks = show.runtimeTicks,
                            officialRating = show.officialRating,
                            communityRating = show.communityRating,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        ItemButtonsBar(
                            item = show,
                            onPlayClick = { startFromBeginning ->
                                onAction(ShowAction.Play(startFromBeginning = startFromBeginning))
                            },
                            onTrailerClick = { uri -> onAction(ShowAction.PlayTrailer(uri)) },
                            onDownloadClick = {},
                            onDownloadCancelClick = {},
                            onDownloadDeleteClick = {},
                            modifier = Modifier.fillMaxWidth(),
                            downloaderState = DownloaderState(),
                            enableDownloadDialog = true,
                            initialSelection =
                                DownloadSelection(
                                    seasonIds = state.existingScope.seasonIds,
                                    alsoFutureSeasons = state.existingScope.alsoFutureSeasons,
                                ),
                            initialAlsoFollowNew = state.existingScope.alsoFollowNew,
                            initialOnlyUnwatched = state.existingScope.onlyUnwatched,
                            getSeasons = { state.seasons },
                            getSeasonSize = getSeasonSize,
                            getOtherDevices = getOtherDevices,
                            hasActiveDownloadOrRule =
                                state.hasDownloads || state.autoDownloadEnabled,
                            onDeleteDownloads = { clearShowDownloadsDialogOpen = true },
                            downloadIconTint =
                                if (state.autoDownloadEnabled) Color("#F2C94C".toColorInt())
                                else null,
                            onBulkDownload = {
                                selection,
                                alsoFollowNew,
                                onlyUnwatched,
                                targetDeviceId ->
                                onAction(
                                    ShowAction.DownloadWithScope(
                                        selection,
                                        alsoFollowNew,
                                        onlyUnwatched,
                                        targetDeviceId,
                                    )
                                )
                                Toast.makeText(
                                        androidContext,
                                        when {
                                            targetDeviceId != null ->
                                                CoreR.string.remote_config_download_sent_toast
                                            alsoFollowNew ->
                                                CoreR.string.auto_download_enabled_toast
                                            else -> CoreR.string.download_queued_toast
                                        },
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            },
                            // Same "size as the label" tile the single-episode/movie Delete
                            // download
                            // tile uses (see ItemButtonsBar) - one reusable shape for both scopes
                            // instead of a bespoke button + separate disk-usage caption.
                            trailingContent = {
                                if (state.hasDownloads || state.autoDownloadEnabled) {
                                    ItemActionButton(
                                        icon = painterResource(CoreR.drawable.ic_trash),
                                        label =
                                            state.downloadsSizeBytes
                                                .takeIf { it > 0L }
                                                ?.let { formatBinaryFileSize(it) }
                                                ?: stringResource(
                                                    CoreR.string.clear_show_downloads
                                                ),
                                        onClick = { clearShowDownloadsDialogOpen = true },
                                        contentColor = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        OverviewText(text = show.overview, maxCollapsedLines = 3)
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        InfoText(
                            genres = show.genres,
                            director = state.director,
                            writers = state.writers,
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        state.nextUp?.let { nextUp ->
                            Text(
                                text = stringResource(CoreR.string.next_up),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.small))
                            Column(
                                modifier =
                                    Modifier.widthIn(max = 420.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable { onAction(ShowAction.NavigateToItem(nextUp)) }
                            ) {
                                ItemPoster(
                                    item = nextUp,
                                    direction = Direction.HORIZONTAL,
                                    modifier = Modifier.clip(MaterialTheme.shapes.medium),
                                )
                                Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                                Text(
                                    text =
                                        stringResource(
                                            id = CoreR.string.episode_name_extended,
                                            nextUp.parentIndexNumber,
                                            nextUp.indexNumber,
                                            nextUp.name,
                                        ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        }
                        if (state.nextUp == null) {
                            state.nextAiring?.let { nextAiring ->
                                Text(
                                    text =
                                        nextAiring.airTime?.let { airTime ->
                                            stringResource(
                                                CoreR.string.next_episode_airs_time,
                                                nextAiring.subtitle.orEmpty(),
                                                formatCalendarDate(nextAiring.date),
                                                formatCalendarTime(airTime),
                                            )
                                        }
                                            ?: stringResource(
                                                CoreR.string.next_episode_airs,
                                                nextAiring.subtitle.orEmpty(),
                                                formatCalendarDate(nextAiring.date),
                                            ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(MaterialTheme.spacings.medium))
                            }
                        }
                    }

                    if (state.seasons.isNotEmpty() || state.missingSeasons.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(start = paddingStart, end = paddingEnd)
                        ) {
                            Text(
                                text = stringResource(CoreR.string.seasons),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.small))
                        }
                        val seasonRowItems =
                            (state.seasons.map { SeasonRowItem.Real(it) } +
                                    state.missingSeasons.map { SeasonRowItem.Missing(it) })
                                .sortedByDescending { it.seasonNumber }
                        LazyRow(
                            contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                            horizontalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.default),
                        ) {
                            items(
                                items = seasonRowItems,
                                key = { item ->
                                    when (item) {
                                        is SeasonRowItem.Real -> item.season.id
                                        is SeasonRowItem.Missing ->
                                            "missing-${item.season.seasonNumber}"
                                    }
                                },
                            ) { item ->
                                when (item) {
                                    is SeasonRowItem.Real ->
                                        ItemCard(
                                            item = item.season,
                                            direction = Direction.VERTICAL,
                                            onClick = {
                                                onAction(ShowAction.NavigateToItem(item.season))
                                            },
                                        )
                                    is SeasonRowItem.Missing ->
                                        UpcomingSeasonCard(
                                            season = item.season,
                                            onClick =
                                                state.seriesTmdbId?.let { tmdbId ->
                                                    {
                                                        onAction(
                                                            ShowAction.NavigateToSeerr(
                                                                tmdbId = tmdbId,
                                                                seasonNumber =
                                                                    item.season.seasonNumber,
                                                            )
                                                        )
                                                    }
                                                },
                                            queued =
                                                state.queuedSeasonNumbers.contains(
                                                    item.season.seasonNumber
                                                ),
                                            onToggleQueued = {
                                                onAction(
                                                    ShowAction.ToggleSeasonQueued(
                                                        seasonNumber = item.season.seasonNumber
                                                    )
                                                )
                                            },
                                        )
                                }
                            }
                        }
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    }

                    if (state.actors.isNotEmpty()) {
                        ActorsRow(
                            actors = state.actors,
                            onActorClick = { personId ->
                                onAction(ShowAction.NavigateToPerson(personId))
                            },
                            contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                        )
                    }
                    Spacer(Modifier.height(paddingBottom))
                }
            } ?: run { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
        }
    }

    if (clearShowDownloadsDialogOpen) {
        ClearDownloadsDialog(
            title = stringResource(CoreR.string.clear_show_downloads),
            message = stringResource(CoreR.string.clear_show_downloads_message),
            name = state.show?.name,
            sizeBytes = state.downloadsSizeBytes,
            onConfirm = { alsoRemoveRules ->
                onAction(ShowAction.DeleteShowDownloads(alsoRemoveRules))
                Toast.makeText(
                        androidContext,
                        CoreR.string.downloads_deleted_toast,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                clearShowDownloadsDialogOpen = false
            },
            onDismiss = { clearShowDownloadsDialogOpen = false },
        )
    }

    if (deleteDialogOpen) {
        val cascadable =
            (state.seriesTvdbId != null && state.sonarrConfigured) ||
                (state.seriesTmdbId != null && state.seerrConfigured)
        DeleteItemDialog(
            message = stringResource(CoreR.string.delete_show_message),
            pvrCascadeLabel =
                if (cascadable) stringResource(CoreR.string.also_remove_from_sonarr) else null,
            pvrCascadeSummary =
                if (cascadable) {
                    stringResource(CoreR.string.also_remove_from_sonarr_summary)
                } else {
                    null
                },
            onConfirm = { cascadeToPvr ->
                onAction(ShowAction.DeleteItem(cascadeToPvr))
                deleteDialogOpen = false
            },
            onDismiss = { deleteDialogOpen = false },
        )
    }

    if (infoDialogOpen) {
        AggregateInfoDialog(
            episodeCount = state.episodeCount,
            downloadedSizeBytes = state.downloadsSizeBytes,
            onDismiss = { infoDialogOpen = false },
        )
    }
}

@PreviewScreenSizes
@Composable
private fun EpisodeScreenLayoutPreview() {
    JollyfinTheme { ShowScreenLayout(state = ShowState(show = dummyShow), onAction = {}) }
}

/**
 * Merges real [JollyfinSeason]s and Sonarr-known [UpcomingSeason] placeholders into one list so the
 * seasons row can be sorted by season number in descending order - rendering real seasons first and
 * missing ones appended at the end (as two separate `items()` blocks previously did) put a show's
 * e.g. season 4 placeholder after 1-3 but ahead of a real season 5, wherever one existed.
 */
private sealed interface SeasonRowItem {
    val seasonNumber: Int

    data class Real(val season: JollyfinSeason) : SeasonRowItem {
        override val seasonNumber: Int
            get() = season.indexNumber
    }

    data class Missing(val season: UpcomingSeason) : SeasonRowItem {
        override val seasonNumber: Int
            get() = season.seasonNumber
    }
}
