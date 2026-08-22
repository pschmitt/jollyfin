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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSelection
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSizeEstimate
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloaderState
import dev.pschmitt.jellyfin.core.presentation.dummy.dummySeason
import dev.pschmitt.jellyfin.core.presentation.search.SearchEvent
import dev.pschmitt.jellyfin.film.presentation.season.SeasonAction
import dev.pschmitt.jellyfin.film.presentation.season.SeasonState
import dev.pschmitt.jellyfin.film.presentation.season.SeasonViewModel
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.RemoteDeviceInfo
import dev.pschmitt.jellyfin.presentation.components.TopBarTitle
import dev.pschmitt.jellyfin.presentation.film.components.AggregateInfoDialog
import dev.pschmitt.jellyfin.presentation.film.components.ClearDownloadsDialog
import dev.pschmitt.jellyfin.presentation.film.components.Direction
import dev.pschmitt.jellyfin.presentation.film.components.EpisodeCard
import dev.pschmitt.jellyfin.presentation.film.components.ItemButtonsBar
import dev.pschmitt.jellyfin.presentation.film.components.ItemDetailScaffold
import dev.pschmitt.jellyfin.presentation.film.components.ItemHeader
import dev.pschmitt.jellyfin.presentation.film.components.ItemMetaRow
import dev.pschmitt.jellyfin.presentation.film.components.ItemOverflowMenu
import dev.pschmitt.jellyfin.presentation.film.components.ItemPoster
import dev.pschmitt.jellyfin.presentation.film.components.PlayOverlayButton
import dev.pschmitt.jellyfin.presentation.film.components.ReleasePickerSheet
import dev.pschmitt.jellyfin.presentation.film.components.UpcomingEpisodeCard
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.presentation.utils.rememberSafePadding
import dev.pschmitt.jellyfin.utils.ObserveAsEvents
import dev.pschmitt.jellyfin.utils.displayNameWithContext
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun SeasonScreen(
    seasonId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToItem: (item: JollyfinItem) -> Unit,
    navigateToSeries: (seriesId: UUID) -> Unit,
    navigateToSeerr:
        (
            tmdbId: Int,
            seasonNumber: Int,
            episodeNumber: Int,
            sonarrEpisodeId: Int,
            airDate: String?,
            airTime: String?,
        ) -> Unit,
    navigateToSettings: () -> Unit,
    viewModel: SeasonViewModel = hiltViewModel(),
) {
    val androidContext = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadSeason(seasonId = seasonId) }

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

    SeasonScreenLayout(
        state = state,
        getSeasons = viewModel::getSeasons,
        getSeasonSize = viewModel::getUndownloadedEpisodeSize,
        getOtherDevices = viewModel::getOtherDevices,
        onRefresh = { viewModel.loadSeason(seasonId = seasonId) },
        onAction = { action ->
            when (action) {
                is SeasonAction.Play -> {
                    val intent = Intent(androidContext, PlayerActivity::class.java)
                    intent.putExtra("itemId", seasonId.toString())
                    intent.putExtra("itemKind", BaseItemKind.SEASON.serialName)
                    androidContext.startActivity(intent)
                }
                is SeasonAction.MarkAsPlayed ->
                    Toast.makeText(
                            androidContext,
                            CoreR.string.marked_as_played_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is SeasonAction.UnmarkAsPlayed ->
                    Toast.makeText(
                            androidContext,
                            CoreR.string.marked_as_unplayed_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is SeasonAction.MarkAsFavorite ->
                    Toast.makeText(
                            androidContext,
                            CoreR.string.added_to_favorites_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is SeasonAction.UnmarkAsFavorite ->
                    Toast.makeText(
                            androidContext,
                            CoreR.string.removed_from_favorites_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is SeasonAction.OnBackClick -> navigateBack()
                is SeasonAction.OnHomeClick -> navigateHome()
                is SeasonAction.OnSettingsClick -> navigateToSettings()
                is SeasonAction.NavigateToItem -> navigateToItem(action.item)
                is SeasonAction.NavigateToSeries -> navigateToSeries(action.seriesId)
                is SeasonAction.NavigateToSeerr ->
                    navigateToSeerr(
                        action.tmdbId,
                        action.seasonNumber,
                        action.episodeNumber,
                        action.sonarrEpisodeId,
                        action.airDate?.toString(),
                        action.airTime?.toString(),
                    )
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonScreenLayout(
    state: SeasonState,
    onAction: (SeasonAction) -> Unit,
    onRefresh: () -> Unit = {},
    getSeasons: suspend () -> List<JollyfinSeason> = { emptyList() },
    getSeasonSize: suspend (seasonId: UUID, onlyUnwatched: Boolean) -> DownloadSizeEstimate =
        { _, _ ->
            DownloadSizeEstimate()
        },
    getOtherDevices: suspend () -> List<RemoteDeviceInfo> = { emptyList() },
) {
    val androidContext = LocalContext.current
    val safePadding = rememberSafePadding()
    var clearSeasonDownloadsDialogOpen by remember { mutableStateOf(false) }
    var infoDialogOpen by remember { mutableStateOf(false) }

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val lazyListState = rememberLazyListState()

    ItemDetailScaffold(
        hasBackButton = true,
        hasHomeButton = true,
        onBackClick = { onAction(SeasonAction.OnBackClick) },
        onHomeClick = { onAction(SeasonAction.OnHomeClick) },
        onSettingsClick = { onAction(SeasonAction.OnSettingsClick) },
        topBarContent = {
            state.season?.let { season ->
                TopBarTitle(
                    text = season.seriesName,
                    modifier =
                        Modifier.clickable {
                            onAction(SeasonAction.NavigateToSeries(season.seriesId))
                        },
                )
            }
        },
    ) {
        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
            state.season?.let { season ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = lazyListState,
                    contentPadding = PaddingValues(bottom = paddingBottom),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                ) {
                    item {
                        ItemHeader(
                            item = season,
                            lazyListState = lazyListState,
                            content = {
                                PlayOverlayButton(
                                    item = season,
                                    onClick = {
                                        onAction(SeasonAction.Play(startFromBeginning = false))
                                    },
                                    enabled = season.canPlay && state.episodes.isNotEmpty(),
                                    modifier = Modifier.align(Alignment.Center),
                                )
                                Row(
                                    modifier =
                                        Modifier.align(Alignment.BottomStart)
                                            .padding(start = paddingStart, end = paddingEnd),
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    ItemPoster(
                                        item = season,
                                        direction = Direction.VERTICAL,
                                        modifier =
                                            Modifier.width(120.dp).clip(MaterialTheme.shapes.small),
                                    )
                                    Spacer(Modifier.width(MaterialTheme.spacings.medium))
                                    Column(modifier = Modifier) {
                                        Text(
                                            text = season.seriesName,
                                            overflow = TextOverflow.Ellipsis,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            text = season.name,
                                            overflow = TextOverflow.Ellipsis,
                                            maxLines = 3,
                                            style = MaterialTheme.typography.headlineMedium,
                                        )
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        ItemMetaRow(
                            modifier =
                                Modifier.padding(start = paddingStart, end = paddingEnd)
                                    .fillMaxWidth()
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        ItemButtonsBar(
                            item = season,
                            onPlayClick = { startFromBeginning ->
                                onAction(SeasonAction.Play(startFromBeginning = startFromBeginning))
                            },
                            onTrailerClick = {},
                            onDownloadClick = {},
                            onDownloadCancelClick = {},
                            onDownloadDeleteClick = {},
                            modifier =
                                Modifier.padding(start = paddingStart, end = paddingEnd)
                                    .fillMaxWidth(),
                            downloaderState = DownloaderState(),
                            enableDownloadDialog = true,
                            getSeasons = getSeasons,
                            getSeasonSize = getSeasonSize,
                            getOtherDevices = getOtherDevices,
                            initialSelection =
                                DownloadSelection(
                                    seasonIds =
                                        state.existingScope.seasonIds.ifEmpty { setOf(season.id) },
                                    alsoFutureSeasons = state.existingScope.alsoFutureSeasons,
                                ),
                            initialAlsoFollowNew = state.existingScope.alsoFollowNew,
                            initialOnlyUnwatched = state.existingScope.onlyUnwatched,
                            hasActiveDownloadOrRule =
                                state.hasDownloads || state.autoDownloadEnabled,
                            onDeleteDownloads = { clearSeasonDownloadsDialogOpen = true },
                            downloadIconTint =
                                if (state.autoDownloadEnabled) Color("#F2C94C".toColorInt())
                                else null,
                            onBulkDownload = {
                                selection,
                                alsoFollowNew,
                                onlyUnwatched,
                                targetDeviceId ->
                                onAction(
                                    SeasonAction.DownloadWithScope(
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
                            overflowContent = {
                                ItemOverflowMenu { closeMenu ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (season.played) CoreR.string.unmark_as_played
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
                                                if (season.played) SeasonAction.UnmarkAsPlayed
                                                else SeasonAction.MarkAsPlayed
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (season.favorite) {
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
                                                        if (season.favorite) {
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
                                                if (season.favorite) SeasonAction.UnmarkAsFavorite
                                                else SeasonAction.MarkAsFavorite
                                            )
                                        },
                                    )
                                    if (state.hasDownloads || state.autoDownloadEnabled) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        CoreR.string.clear_season_downloads
                                                    )
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    painter =
                                                        painterResource(CoreR.drawable.ic_trash),
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                closeMenu()
                                                clearSeasonDownloadsDialogOpen = true
                                            },
                                        )
                                    }
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
                                }
                            },
                        )
                    }
                    items(items = state.episodes, key = { episode -> episode.id }) { episode ->
                        EpisodeCard(
                            episode = episode,
                            onClick = { onAction(SeasonAction.NavigateToItem(episode)) },
                            modifier = Modifier.padding(start = paddingStart, end = paddingEnd),
                            downloadProgress = state.downloadProgress[episode.id],
                            queueStatus = state.queueStatus[episode.id],
                            onSearchAutomatic =
                                if (state.sonarrConfigured) {
                                    {
                                        onAction(
                                            SeasonAction.SearchEpisodeAutomatic(
                                                episodeNumber = episode.indexNumber,
                                                knownEpisodeId = null,
                                            )
                                        )
                                    }
                                } else {
                                    null
                                },
                            onSearchManual =
                                if (state.sonarrConfigured) {
                                    {
                                        onAction(
                                            SeasonAction.OpenReleasePicker(
                                                episodeNumber = episode.indexNumber,
                                                knownEpisodeId = null,
                                            )
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                    items(
                        items = state.upcomingEpisodes,
                        key = { episode -> "upcoming-${episode.episodeNumber}" },
                    ) { episode ->
                        UpcomingEpisodeCard(
                            episode = episode,
                            modifier = Modifier.padding(start = paddingStart, end = paddingEnd),
                            onClick =
                                state.seriesTmdbId?.let { tmdbId ->
                                    {
                                        onAction(
                                            SeasonAction.NavigateToSeerr(
                                                tmdbId = tmdbId,
                                                seasonNumber = episode.seasonNumber,
                                                episodeNumber = episode.episodeNumber,
                                                sonarrEpisodeId = episode.episodeId,
                                                airDate = episode.airDate,
                                                airTime = episode.airTime,
                                            )
                                        )
                                    }
                                },
                            onSearchAutomatic = {
                                onAction(
                                    SeasonAction.SearchEpisodeAutomatic(
                                        episodeNumber = episode.episodeNumber,
                                        knownEpisodeId = episode.episodeId,
                                    )
                                )
                            },
                            onSearchManual = {
                                onAction(
                                    SeasonAction.OpenReleasePicker(
                                        episodeNumber = episode.episodeNumber,
                                        knownEpisodeId = episode.episodeId,
                                    )
                                )
                            },
                            queued = state.queuedEpisodeNumbers.contains(episode.episodeNumber),
                            onToggleQueued = {
                                onAction(
                                    SeasonAction.ToggleEpisodeQueued(
                                        episodeNumber = episode.episodeNumber,
                                        sonarrEpisodeId = episode.episodeId,
                                    )
                                )
                            },
                        )
                    }
                }
            } ?: run { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
        }
    }

    if (clearSeasonDownloadsDialogOpen) {
        ClearDownloadsDialog(
            title = stringResource(CoreR.string.clear_season_downloads),
            message = stringResource(CoreR.string.clear_season_downloads_message),
            name = state.season?.displayNameWithContext(),
            sizeBytes = state.downloadsSizeBytes,
            onConfirm = { alsoRemoveRules ->
                onAction(SeasonAction.DeleteSeasonDownloads(alsoRemoveRules))
                Toast.makeText(
                        androidContext,
                        CoreR.string.downloads_deleted_toast,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                clearSeasonDownloadsDialogOpen = false
            },
            onDismiss = { clearSeasonDownloadsDialogOpen = false },
        )
    }

    if (infoDialogOpen) {
        AggregateInfoDialog(
            episodeCount = state.episodes.size,
            downloadedSizeBytes = state.downloadsSizeBytes,
            onDismiss = { infoDialogOpen = false },
        )
    }

    state.releasePicker?.let { releasePicker ->
        ReleasePickerSheet(
            state = releasePicker,
            onGrab = { release -> onAction(SeasonAction.GrabRelease(release)) },
            onDismissRequest = { onAction(SeasonAction.DismissReleasePicker) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreenSizes
@Composable
private fun SeasonScreenLayoutPreview() {
    JollyfinTheme { SeasonScreenLayout(state = SeasonState(season = dummySeason), onAction = {}) }
}
