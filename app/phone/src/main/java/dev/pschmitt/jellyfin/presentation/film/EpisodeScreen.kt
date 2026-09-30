package dev.pschmitt.jellyfin.presentation.film

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.PlayerActivity
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.core.presentation.delete.DeleteItemEvent
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSelection
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSizeEstimate
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloaderAction
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloaderState
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyEpisode
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyVideoMetadata
import dev.pschmitt.jellyfin.core.presentation.search.SearchEvent
import dev.pschmitt.jellyfin.film.presentation.episode.EpisodeAction
import dev.pschmitt.jellyfin.film.presentation.episode.EpisodeState
import dev.pschmitt.jellyfin.film.presentation.episode.EpisodeViewModel
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import dev.pschmitt.jellyfin.models.QueueItemStatus
import dev.pschmitt.jellyfin.models.RemoteDeviceInfo
import dev.pschmitt.jellyfin.models.isDownloadBroken
import dev.pschmitt.jellyfin.models.isDownloaded
import dev.pschmitt.jellyfin.models.isMarkedForAutoDeletion
import dev.pschmitt.jellyfin.presentation.components.TopBarTitle
import dev.pschmitt.jellyfin.presentation.film.components.ActorsRow
import dev.pschmitt.jellyfin.presentation.film.components.DeleteItemDialog
import dev.pschmitt.jellyfin.presentation.film.components.InfoDialog
import dev.pschmitt.jellyfin.presentation.film.components.ItemButtonsBar
import dev.pschmitt.jellyfin.presentation.film.components.ItemDetailScaffold
import dev.pschmitt.jellyfin.presentation.film.components.ItemHeader
import dev.pschmitt.jellyfin.presentation.film.components.ItemMetaRow
import dev.pschmitt.jellyfin.presentation.film.components.ItemOverflowMenu
import dev.pschmitt.jellyfin.presentation.film.components.LocalStorageIndicator
import dev.pschmitt.jellyfin.presentation.film.components.ManualImportSheet
import dev.pschmitt.jellyfin.presentation.film.components.OverviewText
import dev.pschmitt.jellyfin.presentation.film.components.PlayOverlayButton
import dev.pschmitt.jellyfin.presentation.film.components.ReleasePickerSheet
import dev.pschmitt.jellyfin.presentation.film.components.ShareDownloadMenuItem
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.presentation.utils.LocalOfflineMode
import dev.pschmitt.jellyfin.presentation.utils.rememberSafePadding
import dev.pschmitt.jellyfin.utils.ObserveAsEvents
import dev.pschmitt.jellyfin.utils.format
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EpisodeScreen(
    episodeId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    navigateToSeason: (seasonId: UUID) -> Unit,
    navigateToShow: (showId: UUID) -> Unit,
    navigateToSettings: () -> Unit,
    viewModel: EpisodeViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val isOfflineMode = LocalOfflineMode.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaderState by downloaderViewModel.state.collectAsStateWithLifecycle()
    val manualImportState by viewModel.manualImport.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadEpisode(episodeId = episodeId) }

    LaunchedEffect(state.episode) {
        state.episode?.let { episode -> downloaderViewModel.update(episode) }
    }

    ObserveAsEvents(downloaderViewModel.events) { event ->
        when (event) {
            is DownloaderEvent.Successful -> {
                viewModel.loadEpisode(episodeId = episodeId)
            }
            is DownloaderEvent.Deleted -> {
                if (isOfflineMode) {
                    navigateBack()
                } else {
                    viewModel.loadEpisode(episodeId = episodeId)
                }
            }
        }
    }

    ObserveAsEvents(viewModel.searchEvents) { event ->
        val message =
            when (event) {
                is SearchEvent.SearchTriggered ->
                    context.getString(CoreR.string.search_triggered_toast)
                is SearchEvent.ReleaseGrabbed ->
                    context.getString(CoreR.string.release_grabbed_toast)
                is SearchEvent.Failed ->
                    context.getString(
                        CoreR.string.search_failed_toast,
                        event.message ?: context.getString(CoreR.string.unknown_error),
                    )
            }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    ObserveAsEvents(viewModel.deleteEvents) { event ->
        when (event) {
            is DeleteItemEvent.Deleted -> {
                Toast.makeText(context, CoreR.string.item_deleted_toast, Toast.LENGTH_SHORT).show()
                navigateBack()
            }
            is DeleteItemEvent.Failed -> {
                Toast.makeText(
                        context,
                        context.getString(
                            CoreR.string.item_delete_failed_toast,
                            event.message ?: context.getString(CoreR.string.unknown_error),
                        ),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }
    }

    EpisodeScreenLayout(
        state = state,
        downloaderState = downloaderState,
        downloadLocationPreference = downloaderViewModel.downloadLocationPreference,
        getSeasons = viewModel::getSeasons,
        getSeasonSize = viewModel::getUndownloadedEpisodeSize,
        getOtherDevices = viewModel::getOtherDevices,
        onRefresh = { viewModel.loadEpisode(episodeId = episodeId) },
        onAction = { action ->
            when (action) {
                is EpisodeAction.Play -> {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.putExtra("itemId", episodeId.toString())
                    intent.putExtra("itemKind", BaseItemKind.EPISODE.serialName)
                    intent.putExtra("startFromBeginning", action.startFromBeginning)
                    context.startActivity(intent)
                }
                is EpisodeAction.MarkAsPlayed ->
                    Toast.makeText(context, CoreR.string.marked_as_played_toast, Toast.LENGTH_SHORT)
                        .show()
                is EpisodeAction.UnmarkAsPlayed ->
                    Toast.makeText(
                            context,
                            CoreR.string.marked_as_unplayed_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is EpisodeAction.MarkAsFavorite ->
                    Toast.makeText(
                            context,
                            CoreR.string.added_to_favorites_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is EpisodeAction.UnmarkAsFavorite ->
                    Toast.makeText(
                            context,
                            CoreR.string.removed_from_favorites_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is EpisodeAction.OnBackClick -> navigateBack()
                is EpisodeAction.OnHomeClick -> navigateHome()
                is EpisodeAction.OnSettingsClick -> navigateToSettings()
                is EpisodeAction.NavigateToPerson -> navigateToPerson(action.personId)
                is EpisodeAction.NavigateToSeason -> navigateToSeason(action.seasonId)
                is EpisodeAction.NavigateToShow -> navigateToShow(action.showId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onDownloaderAction = { action -> downloaderViewModel.onAction(action) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeScreenLayout(
    state: EpisodeState,
    downloaderState: DownloaderState,
    downloadLocationPreference: String = "ask",
    onRefresh: () -> Unit = {},
    getSeasons: suspend () -> List<JollyfinSeason> = { emptyList() },
    getSeasonSize: suspend (seasonId: UUID, onlyUnwatched: Boolean) -> DownloadSizeEstimate =
        { _, _ ->
            DownloadSizeEstimate()
        },
    getOtherDevices: suspend () -> List<RemoteDeviceInfo> = { emptyList() },
    onAction: (EpisodeAction) -> Unit,
    onDownloaderAction: (DownloaderAction) -> Unit,
    onManageImportClick: () -> Unit = {},
) {
    val androidContext = LocalContext.current
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val scrollState = rememberScrollState()
    var infoDialogOpen by remember { mutableStateOf(false) }
    var deleteDialogOpen by remember { mutableStateOf(false) }

    ItemDetailScaffold(
        hasBackButton = true,
        hasHomeButton = true,
        onBackClick = { onAction(EpisodeAction.OnBackClick) },
        onHomeClick = { onAction(EpisodeAction.OnHomeClick) },
        onSettingsClick = { onAction(EpisodeAction.OnSettingsClick) },
        topBarContent = {
            state.episode?.let { episode ->
                TopBarTitle(
                    text =
                        episode.seasonName
                            ?: stringResource(
                                CoreR.string.season_number,
                                episode.parentIndexNumber,
                            ),
                    modifier =
                        Modifier.clickable {
                            onAction(EpisodeAction.NavigateToSeason(episode.seasonId))
                        },
                )
            }
        },
    ) {
        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
            state.episode?.let { episode ->
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                    ItemHeader(
                        item = episode,
                        scrollState = scrollState,
                        content = {
                            PlayOverlayButton(
                                item = episode,
                                onClick = {
                                    onAction(EpisodeAction.Play(startFromBeginning = false))
                                },
                                enabled = episode.canPlay,
                                isDeleting = downloaderState.isDeleting,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        },
                    )
                    Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        val downloadedSource =
                            if (episode.isDownloaded()) {
                                episode.sources.firstOrNull { it.type == JollyfinSourceType.LOCAL }
                            } else {
                                null
                            }
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(
                                text = episode.name,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.weight(1f),
                            )
                            ItemOverflowMenu { closeMenu ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (episode.played) CoreR.string.unmark_as_played
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
                                            if (episode.played) EpisodeAction.UnmarkAsPlayed
                                            else EpisodeAction.MarkAsPlayed
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (episode.favorite) {
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
                                                    if (episode.favorite) {
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
                                            if (episode.favorite) EpisodeAction.UnmarkAsFavorite
                                            else EpisodeAction.MarkAsFavorite
                                        )
                                    },
                                )
                                // Always offered, regardless of Sonarr configuration/tvdbId
                                // presence - a search that can't resolve a target fails with a
                                // clear toast instead of the entry silently vanishing.
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
                                        onAction(EpisodeAction.SearchEpisodeAutomatic)
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(CoreR.string.search_episode_manual))
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
                                        onAction(EpisodeAction.OpenReleasePicker)
                                    },
                                )
                                if (state.videoMetadata != null) {
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
                                if (state.autoDeleteWatchedEnabled && downloadedSource != null) {
                                    val excludeFromAutoDelete =
                                        downloadedSource.excludeFromAutoDelete
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (excludeFromAutoDelete) {
                                                        CoreR.string
                                                            .download_action_allow_auto_delete
                                                    } else {
                                                        CoreR.string
                                                            .download_action_exclude_from_auto_delete
                                                    }
                                                )
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painter =
                                                    painterResource(
                                                        if (excludeFromAutoDelete) {
                                                            CoreR.drawable.ic_lock
                                                        } else {
                                                            CoreR.drawable.ic_unlock
                                                        }
                                                    ),
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            closeMenu()
                                            onAction(EpisodeAction.ToggleExcludeFromAutoDelete)
                                        },
                                    )
                                }
                                downloadedSource
                                    ?.path
                                    ?.takeUnless { it.endsWith(".download") }
                                    ?.let { path ->
                                        ShareDownloadMenuItem(
                                            path = path,
                                            title =
                                                "${episode.seriesName} - " +
                                                    "S%02dE%02d - ${episode.name}"
                                                        .format(
                                                            episode.parentIndexNumber,
                                                            episode.indexNumber,
                                                        ),
                                            closeMenu = closeMenu,
                                        )
                                    }
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
                        Text(
                            text = episode.seriesName,
                            modifier =
                                Modifier.clickable {
                                    onAction(EpisodeAction.NavigateToShow(episode.seriesId))
                                },
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val seasonName =
                            episode.seasonName
                                ?: stringResource(
                                    CoreR.string.season_number,
                                    episode.parentIndexNumber,
                                )
                        Text(
                            text =
                                "$seasonName - " +
                                    stringResource(
                                        id = CoreR.string.episode_number,
                                        episode.indexNumber,
                                    ),
                            modifier =
                                Modifier.clickable {
                                    onAction(EpisodeAction.NavigateToSeason(episode.seasonId))
                                },
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        ItemMetaRow(
                            dateText = episode.premiereDate?.format(state.dateFormat),
                            runtimeTicks = episode.runtimeTicks,
                            communityRating = episode.communityRating,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        val deleteDownload: () -> Unit = {
                            onDownloaderAction(DownloaderAction.DeleteDownload(episode))
                            Toast.makeText(
                                    androidContext,
                                    CoreR.string.download_deleted_toast,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                        ItemButtonsBar(
                            item = episode,
                            downloaderState = downloaderState,
                            downloadLocationPreference = downloadLocationPreference,
                            onPlayClick = { startFromBeginning ->
                                onAction(
                                    EpisodeAction.Play(startFromBeginning = startFromBeginning)
                                )
                            },
                            onTrailerClick = {},
                            onDownloadClick = { storageIndex ->
                                onDownloaderAction(DownloaderAction.Download(episode, storageIndex))
                            },
                            onDownloadCancelClick = {
                                onDownloaderAction(DownloaderAction.CancelDownload(episode))
                            },
                            onDownloadForceClick = {
                                onDownloaderAction(DownloaderAction.ForceDownload)
                            },
                            onDownloadPauseClick = {
                                onDownloaderAction(DownloaderAction.PauseDownload)
                            },
                            onDownloadResumeClick = {
                                onDownloaderAction(DownloaderAction.ResumeDownload)
                            },
                            onDownloadDeleteClick = deleteDownload,
                            onDownloadCardClick =
                                state.queueStatus
                                    ?.status
                                    ?.takeIf {
                                        it == QueueItemStatus.WARNING ||
                                            it == QueueItemStatus.FAILED
                                    }
                                    ?.let { { onManageImportClick() } },
                            modifier = Modifier.fillMaxWidth(),
                            enableDownloadDialog = true,
                            showEpisodeDownloadOption = true,
                            initialSelection =
                                DownloadSelection(
                                    seasonIds = state.existingScope.seasonIds,
                                    alsoFutureSeasons = state.existingScope.alsoFutureSeasons,
                                ),
                            initialAlsoFollowNew = state.existingScope.alsoFollowNew,
                            initialOnlyUnwatched = state.existingScope.onlyUnwatched,
                            getSeasons = getSeasons,
                            getSeasonSize = getSeasonSize,
                            getOtherDevices = getOtherDevices,
                            onBulkDownload = {
                                selection,
                                alsoFollowNew,
                                onlyUnwatched,
                                targetDeviceId ->
                                onAction(
                                    EpisodeAction.DownloadWithScope(
                                        selection,
                                        alsoFollowNew,
                                        onlyUnwatched,
                                        targetDeviceId,
                                    )
                                )
                                if (targetDeviceId != null) {
                                    Toast.makeText(
                                            androidContext,
                                            CoreR.string.remote_config_download_sent_toast,
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                }
                            },
                            onPushEpisodeDownload = { targetDeviceId ->
                                onDownloaderAction(
                                    DownloaderAction.PushDownload(episode, targetDeviceId)
                                )
                                Toast.makeText(
                                        androidContext,
                                        CoreR.string.remote_config_download_sent_toast,
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            },
                        )
                        downloadedSource?.let { source ->
                            val isBroken = episode.isDownloadBroken()
                            val isMarkedForDeletion =
                                state.autoDeleteWatchedEnabled &&
                                    episode.isMarkedForAutoDeletion(state.autoDeleteWatchedHours)
                            // Size lives on the "Delete download" tile above - only surface this
                            // caption for states that tile can't show.
                            if (
                                !source.path.endsWith(".download") &&
                                    (isBroken || isMarkedForDeletion)
                            ) {
                                Spacer(Modifier.height(MaterialTheme.spacings.small))
                                LocalStorageIndicator(
                                    path = source.path,
                                    sizeBytes = source.size,
                                    isBroken = isBroken,
                                    isMarkedForDeletion = isMarkedForDeletion,
                                    showSize = false,
                                )
                            }
                        }
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        if (infoDialogOpen && state.videoMetadata != null) {
                            InfoDialog(
                                videoMetadata = state.videoMetadata!!,
                                downloadedFilePath =
                                    downloadedSource?.path?.takeUnless { it.endsWith(".download") },
                                onDismiss = { infoDialogOpen = false },
                            )
                        }
                        if (deleteDialogOpen) {
                            val pvrCascadable = state.seriesTvdbId != null && state.sonarrConfigured
                            DeleteItemDialog(
                                message = stringResource(CoreR.string.delete_episode_message),
                                pvrCascadeLabel =
                                    if (pvrCascadable) {
                                        stringResource(CoreR.string.also_unmonitor_in_sonarr)
                                    } else {
                                        null
                                    },
                                pvrCascadeSummary =
                                    if (pvrCascadable) {
                                        stringResource(
                                            CoreR.string.also_unmonitor_in_sonarr_summary
                                        )
                                    } else {
                                        null
                                    },
                                onConfirm = { cascadeToPvr ->
                                    onAction(EpisodeAction.DeleteItem(cascadeToPvr))
                                    deleteDialogOpen = false
                                },
                                onDismiss = { deleteDialogOpen = false },
                            )
                        }
                        OverviewText(text = episode.overview)
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    }
                    if (state.actors.isNotEmpty()) {
                        ActorsRow(
                            actors = state.actors,
                            onActorClick = { personId ->
                                onAction(EpisodeAction.NavigateToPerson(personId))
                            },
                            contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                        )
                    }
                    Spacer(Modifier.height(paddingBottom))
                }
            } ?: run { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
        }
    }

    state.releasePicker?.let { releasePicker ->
        ReleasePickerSheet(
            state = releasePicker,
            onGrab = { release -> onAction(EpisodeAction.GrabRelease(release)) },
            onDismissRequest = { onAction(EpisodeAction.DismissReleasePicker) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreenSizes
@Composable
private fun EpisodeScreenLayoutPreview() {
    JollyfinTheme {
        EpisodeScreenLayout(
            state = EpisodeState(episode = dummyEpisode, videoMetadata = dummyVideoMetadata),
            downloaderState = DownloaderState(),
            onAction = {},
            onDownloaderAction = {},
        )
    }
}
