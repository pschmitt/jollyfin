package dev.pschmitt.jellyfin.presentation.film

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.PlayerActivity
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.core.presentation.delete.DeleteItemEvent
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloaderAction
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloaderState
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyMovie
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyVideoMetadata
import dev.pschmitt.jellyfin.core.presentation.search.SearchEvent
import dev.pschmitt.jellyfin.film.presentation.movie.MovieAction
import dev.pschmitt.jellyfin.film.presentation.movie.MovieState
import dev.pschmitt.jellyfin.film.presentation.movie.MovieViewModel
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import dev.pschmitt.jellyfin.models.QueueItemStatus
import dev.pschmitt.jellyfin.models.isDownloadBroken
import dev.pschmitt.jellyfin.models.isDownloaded
import dev.pschmitt.jellyfin.presentation.film.components.ActorsRow
import dev.pschmitt.jellyfin.presentation.film.components.DeleteItemDialog
import dev.pschmitt.jellyfin.presentation.film.components.InfoDialog
import dev.pschmitt.jellyfin.presentation.film.components.InfoText
import dev.pschmitt.jellyfin.presentation.film.components.ItemButtonsBar
import dev.pschmitt.jellyfin.presentation.film.components.ItemDetailScaffold
import dev.pschmitt.jellyfin.presentation.film.components.ItemHeader
import dev.pschmitt.jellyfin.presentation.film.components.ItemMetaRow
import dev.pschmitt.jellyfin.presentation.film.components.ItemOverflowMenu
import dev.pschmitt.jellyfin.presentation.film.components.LocalStorageIndicator
import dev.pschmitt.jellyfin.presentation.film.components.ManualImportSheet
import dev.pschmitt.jellyfin.presentation.film.components.OverviewText
import dev.pschmitt.jellyfin.presentation.film.components.PlayOverlayButton
import dev.pschmitt.jellyfin.presentation.film.components.QueueBadge
import dev.pschmitt.jellyfin.presentation.film.components.ReleasePickerSheet
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
fun MovieScreen(
    movieId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    navigateToSettings: () -> Unit,
    viewModel: MovieViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val isOfflineMode = LocalOfflineMode.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaderState by downloaderViewModel.state.collectAsStateWithLifecycle()
    val manualImportState by viewModel.manualImport.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadMovie(movieId = movieId) }

    LaunchedEffect(state.movie) { state.movie?.let { movie -> downloaderViewModel.update(movie) } }

    ObserveAsEvents(downloaderViewModel.events) { event ->
        when (event) {
            is DownloaderEvent.Successful -> {
                viewModel.loadMovie(movieId = movieId)
            }
            is DownloaderEvent.Deleted -> {
                if (isOfflineMode) {
                    navigateBack()
                } else {
                    viewModel.loadMovie(movieId = movieId)
                }
            }
        }
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

    MovieScreenLayout(
        state = state,
        downloaderState = downloaderState,
        downloadLocationPreference = downloaderViewModel.downloadLocationPreference,
        onRefresh = { viewModel.loadMovie(movieId = movieId) },
        onAction = { action ->
            when (action) {
                is MovieAction.Play -> {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.putExtra("itemId", movieId.toString())
                    intent.putExtra("itemKind", BaseItemKind.MOVIE.serialName)
                    intent.putExtra("startFromBeginning", action.startFromBeginning)
                    context.startActivity(intent)
                }
                is MovieAction.PlayTrailer -> {
                    try {
                        uriHandler.openUri(action.trailer)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                is MovieAction.MarkAsPlayed ->
                    Toast.makeText(context, CoreR.string.marked_as_played_toast, Toast.LENGTH_SHORT)
                        .show()
                is MovieAction.UnmarkAsPlayed ->
                    Toast.makeText(
                            context,
                            CoreR.string.marked_as_unplayed_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is MovieAction.MarkAsFavorite ->
                    Toast.makeText(
                            context,
                            CoreR.string.added_to_favorites_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is MovieAction.UnmarkAsFavorite ->
                    Toast.makeText(
                            context,
                            CoreR.string.removed_from_favorites_toast,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is MovieAction.OnBackClick -> navigateBack()
                is MovieAction.OnHomeClick -> navigateHome()
                is MovieAction.OnSettingsClick -> navigateToSettings()
                is MovieAction.NavigateToPerson -> navigateToPerson(action.personId)
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MovieScreenLayout(
    state: MovieState,
    downloaderState: DownloaderState,
    downloadLocationPreference: String = "ask",
    onRefresh: () -> Unit = {},
    onAction: (MovieAction) -> Unit,
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
        onBackClick = { onAction(MovieAction.OnBackClick) },
        onHomeClick = { onAction(MovieAction.OnHomeClick) },
        onSettingsClick = { onAction(MovieAction.OnSettingsClick) },
    ) {
        // Same default Material3 indicator as Downloads/Library/Home - one loading-feedback
        // language across the whole app instead of a screen-specific spinner. fillMaxSize keeps
        // the indicator centered even before content (and its size) exists.
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            state.movie?.let { movie ->
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                    ItemHeader(
                        item = movie,
                        scrollState = scrollState,
                        content = {
                            PlayOverlayButton(
                                item = movie,
                                onClick = {
                                    onAction(MovieAction.Play(startFromBeginning = false))
                                },
                                enabled = movie.canPlay,
                                isDeleting = downloaderState.isDeleting,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        },
                    )
                    Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(
                                text = movie.name,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 3,
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.weight(1f).testTag("e2e-movie-title"),
                            )
                            ItemOverflowMenu { closeMenu ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (movie.played) CoreR.string.unmark_as_played
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
                                            if (movie.played) MovieAction.UnmarkAsPlayed
                                            else MovieAction.MarkAsPlayed
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (movie.favorite)
                                                    CoreR.string.remove_from_favorites
                                                else CoreR.string.add_to_favorites
                                            )
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    if (movie.favorite)
                                                        CoreR.drawable.ic_heart_filled
                                                    else CoreR.drawable.ic_heart
                                                ),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        closeMenu()
                                        onAction(
                                            if (movie.favorite) MovieAction.UnmarkAsFavorite
                                            else MovieAction.MarkAsFavorite
                                        )
                                    },
                                )
                                // Always offered, regardless of Radarr configuration/tmdbId
                                // presence - a search that can't resolve a target fails with a
                                // clear toast instead of the entry silently vanishing.
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(CoreR.string.search_episode_automatic))
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(CoreR.drawable.ic_radarr),
                                            contentDescription = null,
                                            tint = Color.Unspecified,
                                        )
                                    },
                                    onClick = {
                                        closeMenu()
                                        onAction(MovieAction.SearchMovieAutomatic)
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(CoreR.string.search_episode_manual))
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(CoreR.drawable.ic_radarr),
                                            contentDescription = null,
                                            tint = Color.Unspecified,
                                        )
                                    },
                                    onClick = {
                                        closeMenu()
                                        onAction(MovieAction.OpenReleasePicker)
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
                        movie.originalTitle?.let { originalTitle ->
                            if (originalTitle != movie.name) {
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
                            dateText = movie.premiereDate?.format(state.dateFormat),
                            runtimeTicks = movie.runtimeTicks,
                            officialRating = movie.officialRating,
                            communityRating = movie.communityRating,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            state.queueStatus?.let { queueStatus ->
                                QueueBadge(status = queueStatus)
                            }
                        }
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        val deleteDownload: () -> Unit = {
                            onDownloaderAction(DownloaderAction.DeleteDownload(movie))
                            Toast.makeText(
                                    androidContext,
                                    CoreR.string.download_deleted_toast,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                        val downloadedSource =
                            if (movie.isDownloaded()) {
                                movie.sources.firstOrNull { it.type == JollyfinSourceType.LOCAL }
                            } else {
                                null
                            }
                        ItemButtonsBar(
                            item = movie,
                            downloaderState = downloaderState,
                            downloadLocationPreference = downloadLocationPreference,
                            onPlayClick = { startFromBeginning ->
                                onAction(MovieAction.Play(startFromBeginning = startFromBeginning))
                            },
                            onTrailerClick = { uri -> onAction(MovieAction.PlayTrailer(uri)) },
                            onDownloadClick = { storageIndex ->
                                onDownloaderAction(DownloaderAction.Download(movie, storageIndex))
                            },
                            onDownloadCancelClick = {
                                onDownloaderAction(DownloaderAction.CancelDownload(movie))
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
                        )
                        downloadedSource?.let { source ->
                            // Size lives on the "Delete download" tile above - only surface this
                            // caption for a broken (0-byte/missing) download.
                            if (!source.path.endsWith(".download") && movie.isDownloadBroken()) {
                                Spacer(Modifier.height(MaterialTheme.spacings.small))
                                LocalStorageIndicator(
                                    path = source.path,
                                    sizeBytes = source.size,
                                    isBroken = true,
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
                            val cascadable =
                                movie.tmdbId != null &&
                                    (state.radarrConfigured || state.seerrConfigured)
                            DeleteItemDialog(
                                message = stringResource(CoreR.string.delete_movie_message),
                                pvrCascadeLabel =
                                    if (cascadable) {
                                        stringResource(CoreR.string.also_remove_from_radarr)
                                    } else {
                                        null
                                    },
                                pvrCascadeSummary =
                                    if (cascadable) {
                                        stringResource(CoreR.string.also_remove_from_radarr_summary)
                                    } else {
                                        null
                                    },
                                onConfirm = { cascadeToPvr ->
                                    onAction(MovieAction.DeleteItem(cascadeToPvr))
                                    deleteDialogOpen = false
                                },
                                onDismiss = { deleteDialogOpen = false },
                            )
                        }
                        OverviewText(text = movie.overview, maxCollapsedLines = 3)
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        InfoText(
                            genres = movie.genres,
                            director = state.director,
                            writers = state.writers,
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    }
                    if (state.actors.isNotEmpty()) {
                        ActorsRow(
                            actors = state.actors,
                            onActorClick = { personId ->
                                onAction(MovieAction.NavigateToPerson(personId))
                            },
                            contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                        )
                    }
                    Spacer(Modifier.height(paddingBottom))
                }
            }
        }
    }

    state.releasePicker?.let { releasePicker ->
        ReleasePickerSheet(
            state = releasePicker,
            onGrab = { release -> onAction(MovieAction.GrabRelease(release)) },
            onDismissRequest = { onAction(MovieAction.DismissReleasePicker) },
        )
    }
}

@PreviewScreenSizes
@Composable
private fun EpisodeScreenLayoutPreview() {
    JollyfinTheme {
        MovieScreenLayout(
            state = MovieState(movie = dummyMovie, videoMetadata = dummyVideoMetadata),
            downloaderState = DownloaderState(),
            onAction = {},
            onDownloaderAction = {},
        )
    }
}
