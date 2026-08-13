package dev.pschmitt.jellyfin.presentation.film

import android.app.DownloadManager
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.pschmitt.jellyfin.core.Constants
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyEpisode
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyMovie
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyQueueStatus
import dev.pschmitt.jellyfin.film.presentation.downloads.DownloadAction
import dev.pschmitt.jellyfin.film.presentation.downloads.DownloadShowGroup
import dev.pschmitt.jellyfin.film.presentation.downloads.DownloadsEvent
import dev.pschmitt.jellyfin.film.presentation.downloads.DownloadsState
import dev.pschmitt.jellyfin.film.presentation.downloads.DownloadsViewModel
import dev.pschmitt.jellyfin.film.presentation.downloads.PvrQueueGroup
import dev.pschmitt.jellyfin.film.presentation.downloads.PvrQueueUiItem
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinSource
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import dev.pschmitt.jellyfin.models.PvrServiceDiskSpace
import dev.pschmitt.jellyfin.models.PvrSource
import dev.pschmitt.jellyfin.models.QueueItemStatus
import dev.pschmitt.jellyfin.models.isDownloadBroken
import dev.pschmitt.jellyfin.models.isDownloaded
import dev.pschmitt.jellyfin.models.isMarkedForAutoDeletion
import dev.pschmitt.jellyfin.presentation.components.TopBarTitle
import dev.pschmitt.jellyfin.presentation.film.components.ClearDownloadsDialog
import dev.pschmitt.jellyfin.presentation.film.components.Direction
import dev.pschmitt.jellyfin.presentation.film.components.ItemPoster
import dev.pschmitt.jellyfin.presentation.film.components.ManualImportSheet
import dev.pschmitt.jellyfin.presentation.film.components.PvrErrorBanner
import dev.pschmitt.jellyfin.presentation.film.components.PvrQueueLoadingPlaceholder
import dev.pschmitt.jellyfin.presentation.film.components.SectionServiceIcons
import dev.pschmitt.jellyfin.presentation.film.components.ToggleOptionRow
import dev.pschmitt.jellyfin.presentation.theme.HeaderIconColors
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.utils.DeleteProgress
import dev.pschmitt.jellyfin.utils.DeviceStorageStats
import dev.pschmitt.jellyfin.utils.DownloadProgress
import dev.pschmitt.jellyfin.utils.MigrateProgress
import dev.pschmitt.jellyfin.utils.ObserveAsEvents
import dev.pschmitt.jellyfin.utils.formatBinaryFileSize
import dev.pschmitt.jellyfin.utils.formatBinaryUsagePair
import dev.pschmitt.jellyfin.utils.formatDownloadSpeed
import dev.pschmitt.jellyfin.utils.formatEta
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onItemClick: (item: JollyfinItem) -> Unit,
    onSettingsClick: () -> Unit,
    onShowClick: (UUID) -> Unit = {},
    onMoviesClick: () -> Unit = {},
    onGoToHomeClick: () -> Unit = {},
    onPvrItemClick: (PvrQueueUiItem, PvrSource) -> Unit = { _, _ -> },
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val androidContext = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val manualImportState by viewModel.manualImport.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.startObserving() }

    ObserveAsEvents(viewModel.events) { event ->
        val message =
            when (event) {
                is DownloadsEvent.PvrQueueItemRemoved ->
                    androidContext.getString(CoreR.string.pvr_queue_removed_toast, event.title)
                is DownloadsEvent.PvrQueueItemRemoveFailed ->
                    androidContext.getString(
                        CoreR.string.pvr_queue_remove_failed_toast,
                        event.message ?: androidContext.getString(CoreR.string.unknown_error),
                    )
                is DownloadsEvent.PvrQueueItemsRemoved ->
                    if (event.failed == 0) {
                        androidContext.getString(
                            CoreR.string.pvr_queue_removed_selected_toast,
                            event.removed,
                        )
                    } else {
                        androidContext.getString(
                            CoreR.string.pvr_queue_remove_selected_partial_toast,
                            event.removed,
                            event.removed + event.failed,
                            event.failed,
                        )
                    }
                is DownloadsEvent.ManualImportCompleted ->
                    androidContext.getString(CoreR.string.manual_import_completed_toast)
                is DownloadsEvent.ManualImportFailed ->
                    androidContext.getString(
                        CoreR.string.manual_import_failed_toast,
                        event.message ?: androidContext.getString(CoreR.string.unknown_error),
                    )
            }
        Toast.makeText(androidContext, message, Toast.LENGTH_SHORT).show()
    }

    var clearAllDialogOpen by remember { mutableStateOf(false) }
    var deleteSelectedDialogOpen by remember { mutableStateOf(false) }
    var deleteSelectedPvrDialogOpen by remember { mutableStateOf(false) }
    var migrateDialogOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDownloadDelete?>(null) }
    var pendingGroupDelete by remember { mutableStateOf<DownloadShowGroup?>(null) }
    var pendingPvrRemove by remember { mutableStateOf<Pair<PvrQueueUiItem, PvrSource>?>(null) }

    val allItems = state.movies + state.showGroups.flatMap { it.episodes }
    val totalSizeBytes =
        remember(allItems) {
            allItems.sumOf {
                it.sources.firstOrNull { s -> s.type == JollyfinSourceType.LOCAL }?.size ?: 0L
            }
        }
    val selectedSizeBytes =
        remember(allItems, state.selectedIds) {
            allItems
                .filter { it.id in state.selectedIds }
                .sumOf {
                    it.sources.firstOrNull { s -> s.type == JollyfinSourceType.LOCAL }?.size ?: 0L
                }
        }
    val selectedLocalSources =
        remember(allItems, state.selectedIds) {
            allItems
                .filter { it.id in state.selectedIds }
                .mapNotNull { it.sources.firstOrNull { s -> s.type == JollyfinSourceType.LOCAL } }
        }

    DownloadsScreenLayout(
        state = state,
        onSettingsClick = onSettingsClick,
        onTrashClick = {
            when {
                state.selectedIds.isNotEmpty() -> deleteSelectedDialogOpen = true
                state.selectedPvrQueueIds.isNotEmpty() -> deleteSelectedPvrDialogOpen = true
                else -> clearAllDialogOpen = true
            }
        },
        onMigrateClick = { migrateDialogOpen = true },
        onClearSelection = {
            if (state.selectedPvrQueueIds.isNotEmpty()) viewModel.togglePvrQueueSelectAll(false)
            else viewModel.toggleSelectAll(false)
        },
        onItemClick = onItemClick,
        onToggleSelection = viewModel::toggleSelection,
        onToggleSelectAll = viewModel::toggleSelectAll,
        onToggleGroupSelection = viewModel::setGroupSelected,
        onDownloadAction = viewModel::onDownloadAction,
        onSwipeDeleteRequest = { id, title, path, sizeBytes ->
            pendingDelete = PendingDownloadDelete(id, title, path, sizeBytes)
        },
        onSwipeDeleteGroupRequest = { group -> pendingGroupDelete = group },
        onPauseAllClick = viewModel::pauseAll,
        onResumeAllClick = viewModel::resumeAll,
        onShowClick = onShowClick,
        onMoviesClick = onMoviesClick,
        onGoToHomeClick = onGoToHomeClick,
        onForceGroup = viewModel::forceGroup,
        onPvrRemoveRequest = { item, source -> pendingPvrRemove = item to source },
        onPvrItemClick = onPvrItemClick,
        onTogglePvrQueueSelection = viewModel::togglePvrQueueSelection,
        onTogglePvrQueueSelectAll = viewModel::togglePvrQueueSelectAll,
        onManageImport = viewModel::openManualImport,
        onRefresh = viewModel::refresh,
        onRedownloadRequest = viewModel::redownloadItem,
        onRedownloadAllBrokenClick = viewModel::redownloadAllBroken,
    )

    manualImportState?.let { manualImport ->
        ManualImportSheet(
            state = manualImport,
            onSelectEntry = viewModel.manualImport::selectEntry,
            onToggleSelection = viewModel.manualImport::toggleSelection,
            onConfirm = viewModel::confirmManualImport,
            onReject = viewModel::rejectManualImport,
            onDismissRequest = viewModel.manualImport::close,
        )
    }

    pendingPvrRemove?.let { (queueItem, source) ->
        RemovePvrQueueItemDialog(
            title = queueItem.title,
            onConfirm = { removeFromClient, blocklist ->
                viewModel.removePvrQueueItem(queueItem, source, removeFromClient, blocklist)
                pendingPvrRemove = null
            },
            onDismiss = { pendingPvrRemove = null },
        )
    }

    if (migrateDialogOpen && state.deviceStorages.size > 1) {
        MigrateDownloadsDialog(
            selectedSources = selectedLocalSources,
            deviceStorages = state.deviceStorages,
            onConfirm = { toIndex ->
                viewModel.migrateSelected(toIndex)
                migrateDialogOpen = false
            },
            onDismiss = { migrateDialogOpen = false },
        )
    }

    if (deleteSelectedPvrDialogOpen) {
        RemoveSelectedPvrQueueItemsDialog(
            count = state.selectedPvrQueueIds.size,
            onConfirm = { removeFromClient, blocklist ->
                viewModel.removeSelectedPvrQueueItems(removeFromClient, blocklist)
                deleteSelectedPvrDialogOpen = false
            },
            onDismiss = { deleteSelectedPvrDialogOpen = false },
        )
    }

    if (clearAllDialogOpen) {
        ClearDownloadsDialog(
            title = stringResource(CoreR.string.clear_all_downloads),
            message = stringResource(CoreR.string.clear_all_downloads_message),
            sizeBytes = totalSizeBytes,
            onConfirm = { alsoRemoveRules ->
                viewModel.clearAllDownloads(alsoRemoveRules)
                Toast.makeText(
                        androidContext,
                        CoreR.string.downloads_deleted_toast,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                clearAllDialogOpen = false
            },
            onDismiss = { clearAllDialogOpen = false },
        )
    }

    if (deleteSelectedDialogOpen) {
        DeleteSelectedDownloadsDialog(
            count = state.selectedIds.size,
            sizeBytes = selectedSizeBytes,
            onConfirm = {
                viewModel.deleteSelected()
                deleteSelectedDialogOpen = false
            },
            onDismiss = { deleteSelectedDialogOpen = false },
        )
    }

    pendingDelete?.let { pending ->
        DeleteSingleDownloadDialog(
            title = pending.title,
            path = pending.path,
            sizeBytes = pending.sizeBytes,
            onConfirm = {
                viewModel.deleteItem(pending.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingGroupDelete?.let { group ->
        val groupSizeBytes =
            remember(group) {
                group.episodes.sumOf {
                    it.sources.firstOrNull { s -> s.type == JollyfinSourceType.LOCAL }?.size ?: 0L
                }
            }
        DeleteShowDownloadsDialog(
            seriesName = group.seriesName,
            episodeCount = group.episodes.size,
            sizeBytes = groupSizeBytes,
            onConfirm = {
                viewModel.deleteItems(group.episodes.map { it.id })
                pendingGroupDelete = null
            },
            onDismiss = { pendingGroupDelete = null },
        )
    }
}

private data class PendingDownloadDelete(
    val id: UUID,
    val title: String,
    val path: String?,
    val sizeBytes: Long?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreenLayout(
    state: DownloadsState,
    onSettingsClick: () -> Unit = {},
    onTrashClick: () -> Unit = {},
    onMigrateClick: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onItemClick: (JollyfinItem) -> Unit = {},
    onToggleSelection: (UUID) -> Unit = {},
    onToggleSelectAll: (Boolean) -> Unit = {},
    onToggleGroupSelection: (Set<UUID>, Boolean) -> Unit = { _, _ -> },
    onDownloadAction: (UUID, DownloadAction) -> Unit = { _, _ -> },
    onSwipeDeleteRequest: (UUID, String, String?, Long?) -> Unit = { _, _, _, _ -> },
    onSwipeDeleteGroupRequest: (DownloadShowGroup) -> Unit = {},
    onPauseAllClick: () -> Unit = {},
    onResumeAllClick: () -> Unit = {},
    onShowClick: (UUID) -> Unit = {},
    onMoviesClick: () -> Unit = {},
    onGoToHomeClick: () -> Unit = {},
    onForceGroup: (List<UUID>) -> Unit = {},
    onPvrRemoveRequest: (PvrQueueUiItem, PvrSource) -> Unit = { _, _ -> },
    onPvrItemClick: (PvrQueueUiItem, PvrSource) -> Unit = { _, _ -> },
    onTogglePvrQueueSelection: (PvrSource, Int) -> Unit = { _, _ -> },
    onTogglePvrQueueSelectAll: (Boolean) -> Unit = {},
    onManageImport: (PvrQueueUiItem, PvrSource) -> Unit = { _, _ -> },
    onRefresh: () -> Unit = {},
    onRedownloadRequest: (JollyfinItem) -> Unit = {},
    onRedownloadAllBrokenClick: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val allIds =
        remember(state.movies, state.showGroups) {
            (state.movies.map { it.id } + state.showGroups.flatMap { it.episodes }.map { it.id })
                .toSet()
        }
    val localSources =
        remember(state.movies, state.showGroups) {
            (state.movies + state.showGroups.flatMap { it.episodes }).mapNotNull {
                it.sources.firstOrNull { s -> s.type == JollyfinSourceType.LOCAL }
            }
        }
    val totalLocalSizeBytes = remember(localSources) { localSources.sumOf { it.size } }
    val brokenCount =
        remember(state.movies, state.showGroups) {
            (state.movies + state.showGroups.flatMap { it.episodes }).count {
                it.isDownloadBroken()
            }
        }
    val allSelected = allIds.isNotEmpty() && state.selectedIds.containsAll(allIds)
    val selectionMode = state.selectedIds.isNotEmpty()
    val pvrQueueKeys =
        remember(state.pvrQueueGroups) {
            state.pvrQueueGroups.flatMap { g -> g.items.map { g.source to it.queueItemId } }.toSet()
        }
    val pvrAllSelected =
        pvrQueueKeys.isNotEmpty() && state.selectedPvrQueueIds.containsAll(pvrQueueKeys)
    val pvrSelectionMode = state.selectedPvrQueueIds.isNotEmpty()
    // Which of Sonarr/Radarr's brand icons the "Pending downloads" header shows - whichever
    // service(s) actually have a group or an error right now, not a static "is it configured"
    // check (unlike Home's equivalent, this header only renders when there's something to show).
    val pvrHeaderIcons =
        remember(state.pvrQueueGroups, state.pvrErrors) {
            (state.pvrQueueGroups.map { it.source } + state.pvrErrors.map { it.source })
                .distinct()
                .map { source ->
                    if (source == PvrSource.SONARR) CoreR.drawable.ic_sonarr
                    else CoreR.drawable.ic_radarr
                }
        }

    var moviesCollapsed by remember { mutableStateOf(false) }
    var collapsedGroupIds by remember { mutableStateOf(emptySet<UUID>()) }
    var pvrQueueCollapsed by remember { mutableStateOf(false) }

    // Reset whenever there's no active batch, so the next delete shows the card fresh even if
    // the user dismissed a previous one.
    var deleteProgressDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleteProgress == null) {
        if (state.deleteProgress == null) deleteProgressDismissed = false
    }
    var moveProgressDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(state.moveProgress == null) {
        if (state.moveProgress == null) moveProgressDismissed = false
    }

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsets()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        bottomBar = {
            Column {
                state.deleteProgress?.let { progress ->
                    if (!deleteProgressDismissed) {
                        DeleteProgressCard(
                            progress = progress,
                            onDismiss = { deleteProgressDismissed = true },
                        )
                    }
                }
                state.moveProgress?.let { progress ->
                    if (!moveProgressDismissed) {
                        MoveProgressCard(
                            progress = progress,
                            onDismiss = { moveProgressDismissed = true },
                        )
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    TopBarTitle(
                        text = stringResource(CoreR.string.title_download),
                        iconRes = CoreR.drawable.ic_download,
                        iconTint = HeaderIconColors.Downloads,
                    )
                },
                navigationIcon = {
                    if (selectionMode || pvrSelectionMode) {
                        IconButton(onClick = onClearSelection) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_x),
                                contentDescription = stringResource(CoreR.string.cancel),
                            )
                        }
                    }
                },
                actions = {
                    if (
                        !selectionMode && !pvrSelectionMode && state.downloadProgress.isNotEmpty()
                    ) {
                        val allPaused =
                            state.downloadProgress.values.all {
                                it.status == DownloadManager.STATUS_PAUSED ||
                                    it.status == DownloadProgress.STATUS_AWAITING_FOREGROUND
                            }
                        IconButton(onClick = if (allPaused) onResumeAllClick else onPauseAllClick) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (allPaused) CoreR.drawable.ic_play
                                        else CoreR.drawable.ic_pause
                                    ),
                                contentDescription =
                                    stringResource(
                                        if (allPaused) CoreR.string.resume_all_downloads
                                        else CoreR.string.pause_all_downloads
                                    ),
                            )
                        }
                    }
                    if (selectionMode && state.deviceStorages.size > 1) {
                        IconButton(onClick = onMigrateClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_arrow_right_left),
                                contentDescription =
                                    stringResource(CoreR.string.migrate_selected_downloads),
                            )
                        }
                    }
                    if (!state.isEmpty || pvrSelectionMode) {
                        IconButton(onClick = onTrashClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_trash),
                                contentDescription =
                                    when {
                                        selectionMode ->
                                            stringResource(CoreR.string.delete_selected_downloads)
                                        pvrSelectionMode ->
                                            stringResource(
                                                CoreR.string.pvr_queue_remove_selected_title
                                            )
                                        else -> stringResource(CoreR.string.clear_all_downloads)
                                    },
                            )
                        }
                    }
                    if (!selectionMode && !pvrSelectionMode) {
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_settings),
                                contentDescription = stringResource(CoreR.string.title_settings),
                            )
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // The empty state is declared *after* the list below, not before - a Box stacks
            // children in declaration order, so an empty state declared first would sit
            // underneath the (still full-size, even with zero items) PullToRefreshBox/LazyColumn
            // and never receive taps, making its "Go to Home" button appear completely dead.
            PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (state.deviceStorages.isNotEmpty() || state.diskSpace.storage != null) {
                        item {
                            DownloadsStorageSummaryCard(
                                localSources = localSources,
                                deviceStorages = state.deviceStorages,
                                pvrStorage = state.diskSpace.storage,
                            )
                        }
                    }
                    if (
                        state.pvrQueueGroups.isNotEmpty() ||
                            state.pvrErrors.isNotEmpty() ||
                            state.pvrPendingSources.isNotEmpty()
                    ) {
                        stickyHeader {
                            SectionHeader(
                                text = stringResource(CoreR.string.pvr_queue_section_title),
                                onLongClick =
                                    if (pvrQueueKeys.isNotEmpty()) {
                                        { onTogglePvrQueueSelectAll(!pvrAllSelected) }
                                    } else {
                                        null
                                    },
                                collapsed = pvrQueueCollapsed,
                                onToggleCollapsed = { pvrQueueCollapsed = !pvrQueueCollapsed },
                                leadingIcons = pvrHeaderIcons,
                            )
                        }
                        if (!pvrQueueCollapsed) {
                            if (state.pvrErrors.isNotEmpty()) {
                                item {
                                    PvrErrorBanner(
                                        errors = state.pvrErrors,
                                        modifier =
                                            Modifier.padding(
                                                horizontal = MaterialTheme.spacings.default,
                                                vertical = MaterialTheme.spacings.small,
                                            ),
                                    )
                                }
                            } else if (
                                state.pvrQueueGroups.isEmpty() &&
                                    state.pvrPendingSources.isNotEmpty()
                            ) {
                                // Still waiting on this service's first successful poll this
                                // session -
                                // distinct from "genuinely nothing queued" (section wouldn't render
                                // at
                                // all) and from "confirmed unreachable" (the error banner above).
                                item {
                                    PvrQueueLoadingPlaceholder(
                                        modifier =
                                            Modifier.padding(
                                                horizontal = MaterialTheme.spacings.default,
                                                vertical = MaterialTheme.spacings.small,
                                            )
                                    )
                                }
                            }
                            state.pvrQueueGroups.forEach { group ->
                                items(items = group.items) { queueItem ->
                                    val key = group.source to queueItem.queueItemId
                                    PvrQueueRow(
                                        queueItem = queueItem,
                                        selectionMode = pvrSelectionMode,
                                        checked = key in state.selectedPvrQueueIds,
                                        onClick =
                                            if (
                                                queueItem.item != null || queueItem.tmdbId != null
                                            ) {
                                                {
                                                    queueItem.item?.let(onItemClick)
                                                        ?: onPvrItemClick(queueItem, group.source)
                                                }
                                            } else {
                                                null
                                            },
                                        onLongClick = {
                                            onTogglePvrQueueSelection(
                                                group.source,
                                                queueItem.queueItemId,
                                            )
                                        },
                                        onToggleSelection = {
                                            onTogglePvrQueueSelection(
                                                group.source,
                                                queueItem.queueItemId,
                                            )
                                        },
                                        onRemove = { onPvrRemoveRequest(queueItem, group.source) },
                                        onManageImport =
                                            if (queueItem.status.downloadId != null) {
                                                { onManageImport(queueItem, group.source) }
                                            } else {
                                                null
                                            },
                                    )
                                }
                            }
                        }
                    }
                    val maxDownloadSizeBytes =
                        state.maxDownloadSizeGb.toLong() * Constants.BYTES_PER_GIB
                    if (
                        state.maxDownloadSizeEnabled && totalLocalSizeBytes > maxDownloadSizeBytes
                    ) {
                        item {
                            MaxDownloadSizeBanner(
                                usedBytes = totalLocalSizeBytes,
                                capBytes = maxDownloadSizeBytes,
                            )
                        }
                    }
                    if (brokenCount > 0) {
                        item {
                            BrokenDownloadsBanner(
                                count = brokenCount,
                                onRedownloadAllClick = onRedownloadAllBrokenClick,
                            )
                        }
                    }
                    if (selectionMode) {
                        item {
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .clickable { onToggleSelectAll(!allSelected) }
                                        .padding(
                                            horizontal = MaterialTheme.spacings.default,
                                            vertical = MaterialTheme.spacings.small,
                                        ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = allSelected, onCheckedChange = onToggleSelectAll)
                                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                                Text(
                                    text = stringResource(CoreR.string.manage_downloads_select_all)
                                )
                            }
                        }
                    }
                    if (state.movies.isNotEmpty()) {
                        stickyHeader {
                            SectionHeader(
                                text = stringResource(CoreR.string.movies_label),
                                onClick = onMoviesClick,
                                collapsed = moviesCollapsed,
                                onToggleCollapsed = { moviesCollapsed = !moviesCollapsed },
                            )
                        }
                        if (!moviesCollapsed) {
                            items(items = state.movies, key = { it.id }) { movie ->
                                DownloadRow(
                                    item = movie,
                                    title = movie.name,
                                    checked = movie.id in state.selectedIds,
                                    selectionMode = selectionMode,
                                    progress = state.downloadProgress[movie.id],
                                    onClick = { onItemClick(movie) },
                                    onLongClick = { onToggleSelection(movie.id) },
                                    onToggleSelection = { onToggleSelection(movie.id) },
                                    onDownloadAction = { onDownloadAction(movie.id, it) },
                                    onSwipeDeleteRequest = {
                                        val source =
                                            movie.sources.firstOrNull {
                                                it.type == JollyfinSourceType.LOCAL
                                            }
                                        onSwipeDeleteRequest(
                                            movie.id,
                                            movie.name,
                                            source?.path,
                                            source?.size,
                                        )
                                    },
                                    deviceStorages = state.deviceStorages,
                                    isMigrating = movie.id in state.migratingIds,
                                    onRedownloadRequest = { onRedownloadRequest(movie) },
                                )
                            }
                        }
                    }
                    state.showGroups.forEach { group ->
                        val groupIds = group.episodes.map { it.id }.toSet()
                        val groupSelected =
                            groupIds.isNotEmpty() && state.selectedIds.containsAll(groupIds)
                        val hasQueuedEpisode =
                            group.episodes.any {
                                state.downloadProgress[it.id]?.status ==
                                    DownloadManager.STATUS_PENDING
                            }
                        val hasActiveDownload =
                            group.episodes.any {
                                state.downloadProgress[it.id]?.status?.let { status ->
                                    status != DownloadManager.STATUS_SUCCESSFUL
                                } == true
                            }
                        val hasMigratingEpisode = group.episodes.any { it.id in state.migratingIds }
                        val groupCollapsed = group.seriesId in collapsedGroupIds
                        stickyHeader {
                            ShowGroupHeader(
                                group = group,
                                checked = groupSelected,
                                selectionMode = selectionMode,
                                onToggle = { onToggleGroupSelection(groupIds, !groupSelected) },
                                onLongClick = { onToggleGroupSelection(groupIds, !groupSelected) },
                                onClick = { onShowClick(group.seriesId) },
                                canForce = hasQueuedEpisode,
                                onForceClick = { onForceGroup(group.episodes.map { it.id }) },
                                collapsed = groupCollapsed,
                                onToggleCollapsed = {
                                    collapsedGroupIds =
                                        if (groupCollapsed) collapsedGroupIds - group.seriesId
                                        else collapsedGroupIds + group.seriesId
                                },
                                swipeEnabled =
                                    !selectionMode && !hasActiveDownload && !hasMigratingEpisode,
                                onSwipeDeleteRequest = { onSwipeDeleteGroupRequest(group) },
                                deviceStorages = state.deviceStorages,
                            )
                        }
                        if (groupCollapsed) return@forEach
                        items(items = group.episodes, key = { it.id }) { episode ->
                            val episodeTitle =
                                stringResource(
                                    CoreR.string.episode_name_extended,
                                    episode.parentIndexNumber,
                                    episode.indexNumber,
                                    episode.name,
                                )
                            DownloadRow(
                                item = episode,
                                title = episodeTitle,
                                checked = episode.id in state.selectedIds,
                                selectionMode = selectionMode,
                                progress = state.downloadProgress[episode.id],
                                onClick = { onItemClick(episode) },
                                onLongClick = { onToggleSelection(episode.id) },
                                onToggleSelection = { onToggleSelection(episode.id) },
                                onDownloadAction = { onDownloadAction(episode.id, it) },
                                onSwipeDeleteRequest = {
                                    val source =
                                        episode.sources.firstOrNull {
                                            it.type == JollyfinSourceType.LOCAL
                                        }
                                    onSwipeDeleteRequest(
                                        episode.id,
                                        episodeTitle,
                                        source?.path,
                                        source?.size,
                                    )
                                },
                                deviceStorages = state.deviceStorages,
                                isMigrating = episode.id in state.migratingIds,
                                onRedownloadRequest = { onRedownloadRequest(episode) },
                                isMarkedForDeletion =
                                    state.autoDeleteWatchedEnabled &&
                                        episode.isMarkedForAutoDeletion(
                                            state.autoDeleteWatchedHours
                                        ),
                            )
                        }
                    }
                }
            }
            // Only show the empty state when there is truly nothing to display - no local
            // downloads, no PVR queue entries/errors (which render in the list above), and not
            // while the initial load is still in flight.
            if (
                state.isEmpty &&
                    !state.isLoading &&
                    state.pvrQueueGroups.isEmpty() &&
                    state.pvrErrors.isEmpty()
            ) {
                DownloadsEmptyState(
                    onGoToHomeClick = onGoToHomeClick,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun DownloadsEmptyState(onGoToHomeClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = MaterialTheme.spacings.large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_download),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        Text(
            text = stringResource(CoreR.string.downloads_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
        Text(
            text = stringResource(CoreR.string.downloads_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        Button(onClick = onGoToHomeClick) {
            Text(text = stringResource(CoreR.string.downloads_empty_go_home))
        }
    }
}

/**
 * Shown whenever one or more downloaded items have a missing/empty local file (see
 * [dev.pschmitt.jellyfin.models.isDownloadBroken]) - most commonly after a storage volume the
 * downloads lived on got removed or reformatted. A single button re-downloads every broken item at
 * once rather than making the user hunt each one down individually in a long list.
 */
@Composable
private fun MaxDownloadSizeBanner(usedBytes: Long, capBytes: Long, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = MaterialTheme.spacings.default),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.default),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_alert_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.default))
            Text(
                text =
                    stringResource(
                        CoreR.string.max_download_size_banner_message,
                        formatBinaryUsagePair(usedBytes, capBytes),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BrokenDownloadsBanner(
    count: Int,
    onRedownloadAllClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = MaterialTheme.spacings.default),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.default),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_alert_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.default))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(CoreR.string.broken_downloads_banner_message, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                Button(onClick = onRedownloadAllClick) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_download),
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                    Text(text = stringResource(CoreR.string.broken_downloads_banner_action))
                }
            }
        }
    }
}

/**
 * Storage at a glance: on-device space used (with a highlighted sub-segment for what this app's own
 * downloads occupy), plus a single PVR-side number - see
 * [dev.pschmitt.jellyfin.models.PvrDiskSpaceResult] for why Sonarr/Radarr never both get a row, and
 * why there's no Jellyfin-server number at all (the server API has no storage endpoint).
 */
@Composable
private fun DownloadsStorageSummaryCard(
    localSources: List<JollyfinSource>,
    deviceStorages: List<DeviceStorageStats>,
    pvrStorage: PvrServiceDiskSpace?,
    modifier: Modifier = Modifier,
) {
    // Edge-to-edge horizontally, matching every other Card in this list (SectionHeader,
    // ShowGroupHeader) - this previously had an outer margin *in addition to* the inner Column
    // padding below, making it visibly narrower than the show-title cards right above/below it in
    // the same list. A bottom margin is still needed vertically though - without it this card
    // sits flush against whatever's next in the list (the local downloads section, or the PVR
    // queue), with no visual separation between two otherwise-unrelated cards.
    Card(modifier = modifier.fillMaxWidth().padding(bottom = MaterialTheme.spacings.default)) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacings.default),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        ) {
            deviceStorages.forEach { device ->
                // Which local downloads actually live on *this* volume - a device where the
                // configured download location is external/removable storage (an SD card) must
                // not have its downloads' size shown against internal storage just because that
                // happens to be index 0; matching by path against this volume's own root is what
                // makes that attribution correct regardless of where downloads actually landed.
                val localUsedBytes =
                    remember(localSources, device.path) {
                        localSources.filter { it.path.startsWith(device.path) }.sumOf { it.size }
                    }
                // The bar reflects the *whole volume's* used space (system + every other app),
                // not just what this app downloaded - localUsedBytes is only highlighted as a
                // sub-segment within that, so the bar answers "how full is this storage" rather
                // than silently implying Jollyfin's downloads are the only thing using space.
                val deviceUsedBytes = (device.totalBytes - device.availableBytes).coerceAtLeast(0L)
                StorageUsageBar(
                    iconRes =
                        if (device.isRemovable) CoreR.drawable.ic_sd_card
                        else CoreR.drawable.ic_smartphone,
                    label =
                        if (deviceStorages.size > 1) {
                            stringResource(
                                if (device.isRemovable) CoreR.string.external
                                else CoreR.string.internal
                            )
                        } else {
                            stringResource(CoreR.string.storage_summary_on_device)
                        },
                    usedBytes = deviceUsedBytes,
                    totalBytes = device.totalBytes,
                    highlightBytes = localUsedBytes.coerceAtMost(deviceUsedBytes),
                    highlightCaption =
                        if (localUsedBytes > 0) {
                            stringResource(
                                CoreR.string.storage_downloads_caption,
                                formatBinaryFileSize(localUsedBytes),
                            )
                        } else {
                            null
                        },
                )
            }
            pvrStorage?.let { pvr ->
                StorageUsageBar(
                    iconRes = CoreR.drawable.ic_server,
                    label = stringResource(CoreR.string.storage_summary_server),
                    usedBytes = (pvr.totalBytes - pvr.freeBytes).coerceAtLeast(0L),
                    totalBytes = pvr.totalBytes,
                )
            }
        }
    }
}

/**
 * One storage row: icon/label/"X of Y used" header, plus a color-coded usage bar below it.
 * [highlightBytes] carves out a visually distinct sub-segment of [usedBytes] (e.g. "of the total
 * space used, this much is this app's own downloads") instead of a single flat fill color; omit it
 * (or pass 0) for a plain single-color bar.
 */
@Composable
private fun StorageUsageBar(
    iconRes: Int,
    label: String,
    usedBytes: Long,
    totalBytes: Long,
    modifier: Modifier = Modifier,
    highlightBytes: Long = 0L,
    highlightCaption: String? = null,
) {
    val fraction =
        if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    // The highlighted sub-segment (e.g. this app's own downloads) uses the warning-tiered color -
    // it's the one part of "used space" the user can act on from this screen, so it's worth
    // calling out the same way the bar already warns about a nearly-full device. The rest of the
    // used space (other apps/system) gets a neutral, still-visible-against-the-track color.
    val warningColor =
        when {
            fraction >= 0.9f -> MaterialTheme.colorScheme.error
            fraction >= 0.7f -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
    val otherUsedColor = MaterialTheme.colorScheme.outline
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatBinaryUsagePair(usedBytes, totalBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
        val otherUsedBytes = (usedBytes - highlightBytes).coerceAtLeast(0L)
        val freeBytes = (totalBytes - usedBytes).coerceAtLeast(0L)
        Box(modifier = Modifier.fillMaxWidth().height(6.dp)) {
            Row(
                modifier =
                    Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).background(trackColor)
            ) {
                if (highlightBytes > 0) {
                    Box(
                        modifier =
                            Modifier.weight(highlightBytes.toFloat())
                                .fillMaxHeight()
                                .background(warningColor)
                    )
                }
                if (otherUsedBytes > 0) {
                    Box(
                        modifier =
                            Modifier.weight(otherUsedBytes.toFloat())
                                .fillMaxHeight()
                                .background(otherUsedColor)
                    )
                }
                if (freeBytes > 0) {
                    Box(modifier = Modifier.weight(freeBytes.toFloat()).fillMaxHeight())
                }
            }
            // This bar is hand-drawn (not a real LinearProgressIndicator), so it doesn't get
            // Material3's default end-of-track "stop indicator" dot for free - draw the same
            // marker by hand so every progress/usage bar in the app looks consistent.
            Box(
                modifier =
                    Modifier.align(Alignment.CenterEnd)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(warningColor)
            )
        }
        if (highlightCaption != null) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(warningColor)
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.extraSmall))
                Text(
                    text = highlightCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Which storage volume a downloaded file's [path] actually lives on, as an icon - only meaningful
 * once there's more than one volume to distinguish between (matches the gating already used for the
 * "Internal"/"External" labels and the migrate action). Returns null for a single-volume device
 * (nothing to disambiguate) or when [path] doesn't match any known volume (e.g. still resolving).
 */
private fun storageIconFor(path: String?, deviceStorages: List<DeviceStorageStats>): Int? {
    if (path == null || deviceStorages.size <= 1) return null
    val storage = deviceStorages.firstOrNull { path.startsWith(it.path) } ?: return null
    return if (storage.isRemovable) CoreR.drawable.ic_sd_card else CoreR.drawable.ic_smartphone
}

@Composable
private fun DeleteProgressCard(progress: DeleteProgress, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.default)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacings.default),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        stringResource(
                            CoreR.string.delete_downloads_progress,
                            progress.done,
                            progress.total,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = {
                        if (progress.total > 0) progress.done / progress.total.toFloat() else 0f
                    },
                    // 4.dp, not 3 - Material3's default end-of-track "stop indicator" dot is
                    // itself 4.dp, so a shorter track clips it into invisibility.
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_x),
                    contentDescription = stringResource(CoreR.string.cancel),
                )
            }
        }
    }
}

@Composable
private fun MoveProgressCard(progress: MigrateProgress, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.default)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacings.default),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        stringResource(
                            CoreR.string.migrate_downloads_progress,
                            progress.done,
                            progress.total,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = {
                        if (progress.total > 0) progress.done / progress.total.toFloat() else 0f
                    },
                    // 4.dp, not 3 - Material3's default end-of-track "stop indicator" dot is
                    // itself 4.dp, so a shorter track clips it into invisibility.
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_x),
                    contentDescription = stringResource(CoreR.string.cancel),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionHeader(
    text: String,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    collapsed: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    leadingIcons: List<Int> = emptyList(),
) {
    Card {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .let { modifier ->
                        if (onLongClick != null) {
                            modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                        } else {
                            modifier.clickable(onClick = onClick)
                        }
                    }
                    // Matches ShowGroupHeader's own padding exactly, so the trailing collapse
                    // chevron lands at the same horizontal inset in both, and the two rows read as
                    // the same height - it used to be its own inner Row's padding here, leaving
                    // the chevron (a sibling, outside that inner Row) flush against the Card edge
                    // instead.
                    .padding(
                        horizontal = MaterialTheme.spacings.default,
                        vertical = MaterialTheme.spacings.medium,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionServiceIcons(leadingIcons)
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton(onClick = onToggleCollapsed) {
                Icon(
                    painter =
                        painterResource(
                            if (collapsed) CoreR.drawable.ic_chevron_down
                            else CoreR.drawable.ic_chevron_up
                        ),
                    contentDescription =
                        stringResource(
                            if (collapsed) CoreR.string.expand else CoreR.string.collapse
                        ),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShowGroupHeader(
    group: DownloadShowGroup,
    checked: Boolean,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    canForce: Boolean = false,
    onForceClick: () -> Unit = {},
    collapsed: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    swipeEnabled: Boolean = false,
    onSwipeDeleteRequest: () -> Unit = {},
    deviceStorages: List<DeviceStorageStats> = emptyList(),
) {
    val downloadedSizeBytes =
        remember(group.episodes) {
            group.episodes.sumOf { episode ->
                episode.sources.firstOrNull { it.type == JollyfinSourceType.LOCAL }?.size ?: 0L
            }
        }
    // Only set when every episode agrees on the same volume - a show split across storage
    // locations (e.g. some episodes migrated, some not yet) has no single icon to show truthfully.
    val groupStorageIcon =
        remember(group.episodes, deviceStorages) {
            group.episodes
                .mapNotNull { episode ->
                    episode.sources.firstOrNull { it.type == JollyfinSourceType.LOCAL }?.path
                }
                .map { storageIconFor(it, deviceStorages) }
                .distinct()
                .singleOrNull()
        }

    SwipeToDeleteContainer(enabled = swipeEnabled, onSwipeDeleteRequest = onSwipeDeleteRequest) {
        Card {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { if (selectionMode) onToggle() else onClick() },
                            onLongClick = onLongClick,
                        )
                        .padding(
                            horizontal = MaterialTheme.spacings.default,
                            vertical = MaterialTheme.spacings.small,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(32.dp).clip(MaterialTheme.shapes.extraSmall)) {
                    ItemPoster(item = group.episodes.first(), direction = Direction.VERTICAL)
                }
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.seriesName,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (downloadedSizeBytes > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            groupStorageIcon?.let { icon ->
                                Icon(
                                    painter = painterResource(icon),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = formatBinaryFileSize(downloadedSizeBytes),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (!selectionMode && canForce) {
                    IconButton(onClick = onForceClick) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_fast_forward),
                            contentDescription = stringResource(CoreR.string.download_action_force),
                        )
                    }
                }
                if (!selectionMode) {
                    IconButton(onClick = onToggleCollapsed) {
                        Icon(
                            painter =
                                painterResource(
                                    if (collapsed) CoreR.drawable.ic_chevron_down
                                    else CoreR.drawable.ic_chevron_up
                                ),
                            contentDescription =
                                stringResource(
                                    if (collapsed) CoreR.string.expand else CoreR.string.collapse
                                ),
                        )
                    }
                }
                if (selectionMode) {
                    Checkbox(checked = checked, onCheckedChange = { onToggle() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    item: JollyfinItem,
    title: String,
    checked: Boolean,
    selectionMode: Boolean,
    progress: DownloadProgress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onDownloadAction: (DownloadAction) -> Unit,
    onSwipeDeleteRequest: () -> Unit,
    deviceStorages: List<DeviceStorageStats> = emptyList(),
    isMigrating: Boolean = false,
    onRedownloadRequest: () -> Unit = {},
    isMarkedForDeletion: Boolean = false,
) {
    val activeProgress = progress?.takeIf { it.status != DownloadManager.STATUS_SUCCESSFUL }
    val isPending = activeProgress?.status == DownloadManager.STATUS_PENDING
    val isPaused = activeProgress?.status == DownloadManager.STATUS_PAUSED
    val isPausedByBatterySaver =
        activeProgress?.pausedByBatterySaver == true ||
            item.sources
                .firstOrNull { it.type == JollyfinSourceType.LOCAL }
                ?.pausedByBatterySaver == true
    val isVerifying = activeProgress?.status == DownloadProgress.STATUS_VERIFYING
    // The download service couldn't promote itself to the foreground right now (app was fully
    // backgrounded) - it'll resume automatically on next foreground/API 34+ job wake, but tapping
    // Resume here also works (a direct tap is itself foreground-eligible) - see
    // DownloadQueueRepository.markAwaitingForeground.
    val isAwaitingForeground = activeProgress?.status == DownloadProgress.STATUS_AWAITING_FOREGROUND
    val showResumeAction = isPaused || isAwaitingForeground
    // Only meaningful once the row has settled (not mid-download/mid-move, where the file is
    // legitimately incomplete/absent right now) - a resting completed source is never
    // legitimately 0 bytes, so outside those states it means the file vanished from disk.
    val isBroken = activeProgress == null && !isMigrating && item.isDownloadBroken()
    val localSource = item.sources.firstOrNull { it.type == JollyfinSourceType.LOCAL }
    val sizeBytes = localSource?.size ?: 0L
    val storageIcon =
        remember(localSource?.path, deviceStorages) {
            storageIconFor(localSource?.path, deviceStorages)
        }
    val swipeEnabled = activeProgress == null && !selectionMode && !isMigrating

    val content: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            when {
                                selectionMode -> onToggleSelection()
                                // The file's mid-copy to another volume right now - its DB path
                                // is about to change and the bytes at the old one may already
                                // be
                                // gone, so opening it for playback is unsafe until that
                                // settles.
                                isMigrating -> {}
                                // Nothing on disk to play - use the re-download/delete icons
                                // instead of guessing what a tap here should do.
                                isBroken -> {}
                                else -> onClick()
                            }
                        },
                        onLongClick = onLongClick,
                    )
                    .padding(
                        horizontal = MaterialTheme.spacings.default,
                        vertical = MaterialTheme.spacings.small,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(96.dp).clip(MaterialTheme.shapes.small)) {
                ItemPoster(item = item, direction = Direction.HORIZONTAL)
            }
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.default))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                when {
                    activeProgress != null -> {
                        if (isPaused && isPausedByBatterySaver) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(CoreR.string.download_paused),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_battery),
                                    contentDescription =
                                        stringResource(
                                            CoreR.string.download_paused_by_battery_saver
                                        ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp).size(14.dp),
                                )
                            }
                        } else {
                            Text(
                                text =
                                    when {
                                        isPending -> stringResource(CoreR.string.download_queued)
                                        isPaused -> stringResource(CoreR.string.download_paused)
                                        isAwaitingForeground ->
                                            stringResource(
                                                CoreR.string.download_awaiting_foreground
                                            )
                                        isVerifying ->
                                            stringResource(CoreR.string.download_verifying)
                                        activeProgress.percent >= 0 ->
                                            stringResource(
                                                CoreR.string.download_progress_status,
                                                activeProgress.percent,
                                                formatDownloadSpeed(
                                                    activeProgress.speedBytesPerSecond
                                                ),
                                                formatEta(activeProgress.etaSeconds),
                                            )
                                        else -> stringResource(CoreR.string.download_downloading)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!isPending && !isAwaitingForeground) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { activeProgress.percent.coerceAtLeast(0) / 100f },
                                // 4.dp, not 3 - Material3's default end-of-track "stop
                                // indicator"
                                // dot is itself 4.dp, so a shorter track clips it into
                                // invisibility.
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                            )
                        }
                    }
                    isMigrating -> {
                        Text(
                            text = stringResource(CoreR.string.download_row_migrating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // Indeterminate - MigrateDownloadsWorker only reports an aggregate
                        // done/total for the whole batch, not this item's own progress.
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                    }
                    isBroken -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_alert_circle),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(CoreR.string.download_row_broken),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            storageIcon?.let { icon ->
                                Icon(
                                    painter = painterResource(icon),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = formatBinaryFileSize(sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (isMarkedForDeletion) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_trash),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text =
                                        stringResource(
                                            CoreR.string.download_row_marked_for_deletion
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
            when {
                selectionMode -> {
                    Checkbox(checked = checked, onCheckedChange = { onToggleSelection() })
                }
                activeProgress != null -> {
                    if (isPending) {
                        IconButton(onClick = { onDownloadAction(DownloadAction.Force) }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_fast_forward),
                                contentDescription =
                                    stringResource(CoreR.string.download_action_force),
                            )
                        }
                    } else if (!isVerifying) {
                        IconButton(
                            onClick = {
                                onDownloadAction(
                                    if (showResumeAction) DownloadAction.Resume
                                    else DownloadAction.Pause
                                )
                            }
                        ) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (showResumeAction) CoreR.drawable.ic_play
                                        else CoreR.drawable.ic_pause
                                    ),
                                contentDescription =
                                    stringResource(
                                        if (showResumeAction) CoreR.string.download_action_resume
                                        else CoreR.string.download_action_pause
                                    ),
                            )
                        }
                    }
                    IconButton(onClick = { onDownloadAction(DownloadAction.Cancel) }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_x),
                            contentDescription =
                                stringResource(CoreR.string.download_action_cancel),
                        )
                    }
                }
                // No play button while the file is mid-move - see the onClick guard above for
                // why.
                isMigrating -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(2.dp),
                        strokeWidth = 2.dp,
                    )
                }
                // No play button here either - there's nothing playable on disk. Offer the two
                // ways out instead: try again, or give up and remove the dangling entry (the
                // trash icon here is a shortcut for the same swipe-to-delete gesture below).
                isBroken -> {
                    IconButton(onClick = onRedownloadRequest) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_download),
                            contentDescription =
                                stringResource(CoreR.string.download_action_redownload),
                        )
                    }
                    IconButton(onClick = onSwipeDeleteRequest) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_trash),
                            contentDescription = stringResource(CoreR.string.delete_download),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item.isDownloaded() -> {
                    IconButton(onClick = onClick) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_play),
                            contentDescription = stringResource(CoreR.string.download_action_play),
                        )
                    }
                }
            }
        }
    }

    SwipeToDeleteContainer(enabled = swipeEnabled, onSwipeDeleteRequest = onSwipeDeleteRequest) {
        content()
    }
}

/**
 * Row for a single Sonarr/Radarr queue entry - same visual shape as [DownloadRow] (poster, title,
 * progress bar, status text). The only available action is [onRemove] (delete from the PVR queue):
 * pause/resume live in the download client, which the PVR APIs don't expose. When
 * [PvrQueueUiItem.item] is null (the queue entry couldn't be matched to a local Jellyfin item), a
 * PVR's own poster is used when the queue entry has not reached Jellyfin yet. The row remains
 * non-clickable until Jellyfin supplies a matching item.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PvrQueueRow(
    queueItem: PvrQueueUiItem,
    onClick: (() -> Unit)?,
    onRemove: () -> Unit,
    selectionMode: Boolean = false,
    checked: Boolean = false,
    onLongClick: () -> Unit = {},
    onToggleSelection: () -> Unit = {},
    onManageImport: (() -> Unit)? = null,
) {
    val status = queueItem.status
    val isProblem =
        status.status == QueueItemStatus.WARNING || status.status == QueueItemStatus.FAILED
    val statusText =
        when (status.status) {
            QueueItemStatus.QUEUED -> stringResource(CoreR.string.download_queued)
            QueueItemStatus.DOWNLOADING ->
                if (status.percent >= 0) {
                    stringResource(
                        CoreR.string.download_progress_status,
                        status.percent,
                        formatDownloadSpeed(status.speedBytesPerSecond),
                        formatEta(status.etaSeconds),
                    )
                } else {
                    stringResource(CoreR.string.download_downloading)
                }
            QueueItemStatus.IMPORTING -> stringResource(CoreR.string.pvr_queue_status_importing)
            QueueItemStatus.WARNING -> stringResource(CoreR.string.pvr_queue_status_warning)
            QueueItemStatus.FAILED -> stringResource(CoreR.string.pvr_queue_status_failed)
        }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .combinedClickable(
                    enabled = selectionMode || onClick != null,
                    onClick = { if (selectionMode) onToggleSelection() else onClick?.invoke() },
                    onLongClick = onLongClick,
                )
                .padding(
                    horizontal = MaterialTheme.spacings.default,
                    vertical = MaterialTheme.spacings.small,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(96.dp).clip(MaterialTheme.shapes.small)) {
            val item = queueItem.item
            if (item != null) {
                ItemPoster(item = item, direction = Direction.HORIZONTAL)
            } else if (queueItem.posterUrl != null) {
                AsyncImage(
                    model = queueItem.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.77f),
                )
            } else {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .aspectRatio(1.77f)
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_film),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                painter =
                    painterResource(
                        if (status.source == PvrSource.SONARR) {
                            CoreR.drawable.ic_sonarr
                        } else {
                            CoreR.drawable.ic_radarr
                        }
                    ),
                contentDescription =
                    if (status.source == PvrSource.SONARR) {
                        "Sonarr"
                    } else {
                        "Radarr"
                    },
                // Unspecified, not the default LocalContentColor tint - these are full-color
                // brand logos, and Icon()'s automatic tint would flatten them into a solid
                // silhouette of the outer circle (i.e. just a plain colored/white badge).
                tint = Color.Unspecified,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(MaterialTheme.spacings.extraSmall)
                        .size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(MaterialTheme.spacings.default))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = queueItem.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Episode rows always get a subtitle line - "TBA" (matching Sonarr's own placeholder
            // for an episode whose title isn't known yet, e.g. unaired or metadata not synced)
            // when we don't have a real one. Movies have no separate subtitle concept at all
            // (their title already is the title), so Radarr rows show nothing here.
            if (queueItem.status.source == PvrSource.SONARR) {
                Text(
                    text = queueItem.subtitle ?: stringResource(CoreR.string.episode_title_tba),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The trailing action icon (right edge of the row) already signals a problem - no
            // need to repeat it here too now that this line no longer carries any status text.
            if (!isProblem) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.percent >= 0) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { status.percent.coerceIn(0, 100) / 100f },
                    // 4.dp, not 3 - Material3's default end-of-track "stop indicator" dot is
                    // itself 4.dp, so a shorter track clips it into invisibility.
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
        if (selectionMode) {
            Checkbox(checked = checked, onCheckedChange = { onToggleSelection() })
        } else if (isProblem && onManageImport != null) {
            // Delete/blocklist are both available from inside the manage-import sheet already -
            // no need for a separate trash icon here once there's an issue to resolve.
            IconButton(onClick = onManageImport) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_alert_circle),
                    contentDescription =
                        stringResource(CoreR.string.pvr_queue_manual_import_action),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_trash),
                    contentDescription = stringResource(CoreR.string.pvr_queue_remove_title),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Confirmation for removing a Sonarr/Radarr queue entry, with the two flags their queue-delete API
 * offers. "Remove from download client" defaults to on (matching the services' own web UI);
 * blocklisting is opt-in for the "this release is broken, grab another" case.
 */
@Composable
private fun RemovePvrQueueItemDialog(
    title: String,
    onConfirm: (removeFromClient: Boolean, blocklist: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var removeFromClient by remember { mutableStateOf(true) }
    var blocklist by remember { mutableStateOf(false) }

    AlertDialog(
        title = { Text(text = stringResource(CoreR.string.pvr_queue_remove_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                Text(text = stringResource(CoreR.string.pvr_queue_remove_message, title))
                ToggleOptionRow(
                    checked = removeFromClient,
                    label = stringResource(CoreR.string.pvr_queue_remove_from_client),
                    onToggle = { removeFromClient = it },
                )
                ToggleOptionRow(
                    checked = blocklist,
                    label = stringResource(CoreR.string.pvr_queue_blocklist),
                    onToggle = { blocklist = it },
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(removeFromClient, blocklist) }) {
                Text(
                    text = stringResource(CoreR.string.remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(CoreR.string.cancel)) }
        },
    )
}

/** Bulk version of [RemovePvrQueueItemDialog], for "clear all pending downloads". */
@Composable
private fun RemoveSelectedPvrQueueItemsDialog(
    count: Int,
    onConfirm: (removeFromClient: Boolean, blocklist: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var removeFromClient by remember { mutableStateOf(true) }
    var blocklist by remember { mutableStateOf(false) }

    AlertDialog(
        title = { Text(text = stringResource(CoreR.string.pvr_queue_remove_selected_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                Text(text = stringResource(CoreR.string.pvr_queue_remove_selected_message, count))
                ToggleOptionRow(
                    checked = removeFromClient,
                    label = stringResource(CoreR.string.pvr_queue_remove_from_client),
                    onToggle = { removeFromClient = it },
                )
                ToggleOptionRow(
                    checked = blocklist,
                    label = stringResource(CoreR.string.pvr_queue_blocklist),
                    onToggle = { blocklist = it },
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(removeFromClient, blocklist) }) {
                Text(
                    text = stringResource(CoreR.string.remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(CoreR.string.cancel)) }
        },
    )
}

@Composable
private fun SwipeToDeleteContainer(
    enabled: Boolean,
    onSwipeDeleteRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val maxSwipePx = with(density) { 96.dp.toPx() }
    val thresholdPx = maxSwipePx / 2f

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier.matchParentSize().padding(horizontal = MaterialTheme.spacings.default),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_trash),
                contentDescription = stringResource(CoreR.string.delete_download),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Box(
            modifier =
                Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .background(MaterialTheme.colorScheme.background)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state =
                            rememberDraggableState { delta ->
                                scope.launch {
                                    offsetX.snapTo(
                                        (offsetX.value + delta).coerceIn(-maxSwipePx, 0f)
                                    )
                                }
                            },
                        onDragStopped = {
                            if (offsetX.value < -thresholdPx) {
                                onSwipeDeleteRequest()
                            }
                            scope.launch { offsetX.animateTo(0f) }
                        },
                    )
        ) {
            content()
        }
    }
}

/**
 * Confirmation for migrating the current selection to another storage volume. Usually a plain
 * confirmation - the selection normally lives entirely on one volume, so there's exactly one
 * sensible destination (the other one), which is never offered as its own destination. But when the
 * selection is already split across more than one volume, either direction is a legitimate choice
 * (e.g. consolidate onto internal vs. onto external), so this lets the user pick - and reacts live
 * to that choice, since which sources actually need to move (and how many/how big) depends on which
 * volume is already "home" for a given item.
 */
@Composable
private fun MigrateDownloadsDialog(
    selectedSources: List<JollyfinSource>,
    deviceStorages: List<DeviceStorageStats>,
    onConfirm: (toStorageIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val presentIndices =
        remember(selectedSources, deviceStorages) {
            deviceStorages.indices.filter { index ->
                selectedSources.any { it.path.startsWith(deviceStorages[index].path) }
            }
        }
    val isMixed = presentIndices.size > 1

    var selectedTargetIndex by
        remember(selectedSources, deviceStorages) {
            // Default to a volume the selection isn't already entirely on, so there's actually
            // something to move - the common case (selection lives on one volume) must default
            // to the *other* one, not to itself (which would make every item a no-op and leave
            // nothing to migrate). Only once every volume already holds part of the selection
            // (a genuinely mixed pick spanning all of them) does "most of the selection" become
            // the sensible default, since every choice then moves something regardless - and
            // picking the majority's volume moves the fewest items.
            val absentIndices = deviceStorages.indices.filterNot { it in presentIndices }
            mutableStateOf(
                absentIndices.firstOrNull()
                    ?: deviceStorages.indices.maxByOrNull { index ->
                        selectedSources.count { it.path.startsWith(deviceStorages[index].path) }
                    }
                    ?: 0
            )
        }

    val toStorage = deviceStorages[selectedTargetIndex]
    val toLabel =
        stringResource(if (toStorage.isRemovable) CoreR.string.external else CoreR.string.internal)
    val fromIndex =
        deviceStorages.indices.firstOrNull { it != selectedTargetIndex } ?: selectedTargetIndex
    val fromLabel =
        stringResource(
            if (deviceStorages[fromIndex].isRemovable) CoreR.string.external
            else CoreR.string.internal
        )

    // Only sources not already on the destination actually move - with a mixed selection,
    // switching the destination changes which items those are, hence the count/size below.
    val toMoveSources =
        remember(selectedSources, selectedTargetIndex, deviceStorages) {
            selectedSources.filterNot { it.path.startsWith(toStorage.path) }
        }
    val itemCount = toMoveSources.size
    val sizeBytes = toMoveSources.sumOf { it.size }

    val toUsedBytes = (toStorage.totalBytes - toStorage.availableBytes).coerceAtLeast(0L)
    // What the destination will look like right after the move, not just its usage today - the
    // whole point of a preview here is showing the impact of the move before committing to it.
    val projectedUsedBytes = (toUsedBytes + sizeBytes).coerceAtMost(toStorage.totalBytes)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_arrow_right_left),
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(text = stringResource(CoreR.string.migrate_storage_title))
            }
        },
        text = {
            Column {
                if (isMixed) {
                    Text(
                        text = stringResource(CoreR.string.migrate_storage_choose_destination),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                    deviceStorages.forEachIndexed { index, storage ->
                        val label =
                            stringResource(
                                if (storage.isRemovable) CoreR.string.external
                                else CoreR.string.internal
                            )
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable { selectedTargetIndex = index }
                                    .padding(vertical = MaterialTheme.spacings.extraSmall),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = index == selectedTargetIndex,
                                onClick = { selectedTargetIndex = index },
                            )
                            Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                            Text(text = label)
                        }
                    }
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
                }
                if (itemCount > 0) {
                    Text(
                        text =
                            stringResource(
                                CoreR.string.migrate_storage_message,
                                itemCount,
                                formatBinaryFileSize(sizeBytes),
                                fromLabel,
                                toLabel,
                            )
                    )
                } else {
                    Text(
                        text =
                            stringResource(CoreR.string.migrate_storage_nothing_to_move, toLabel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
                StorageUsageBar(
                    iconRes =
                        if (toStorage.isRemovable) CoreR.drawable.ic_sd_card
                        else CoreR.drawable.ic_smartphone,
                    label = toLabel,
                    usedBytes = projectedUsedBytes,
                    totalBytes = toStorage.totalBytes,
                    highlightBytes = sizeBytes.coerceAtMost(projectedUsedBytes),
                    highlightCaption =
                        if (sizeBytes > 0) {
                            stringResource(
                                CoreR.string.migrate_storage_incoming_caption,
                                formatBinaryFileSize(sizeBytes),
                            )
                        } else {
                            null
                        },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedTargetIndex) }, enabled = itemCount > 0) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_arrow_right_left),
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(text = stringResource(CoreR.string.move))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(painter = painterResource(CoreR.drawable.ic_x), contentDescription = null)
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(text = stringResource(CoreR.string.cancel))
            }
        },
    )
}

@Composable
private fun DeleteSelectedDownloadsDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    sizeBytes: Long? = null,
) {
    AlertDialog(
        // Not AlertDialog's own `icon` slot - Material3 always renders that centered *above* the
        // title, not inline with it. Building the title as an icon+text Row instead keeps them on
        // the same line.
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_trash),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(text = stringResource(CoreR.string.delete_selected_downloads))
            }
        },
        text = {
            Column {
                Text(text = stringResource(CoreR.string.delete_selected_downloads_message, count))
                if (sizeBytes != null) {
                    Text(
                        text = formatBinaryFileSize(sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_trash),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(
                    text = stringResource(CoreR.string.delete_download),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(painter = painterResource(CoreR.drawable.ic_x), contentDescription = null)
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(text = stringResource(CoreR.string.cancel))
            }
        },
    )
}

@Composable
private fun DeleteSingleDownloadDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    path: String? = null,
    sizeBytes: Long? = null,
) {
    AlertDialog(
        // Not AlertDialog's own `icon` slot - Material3 always renders that centered *above* the
        // title, not inline with it. Building the title as an icon+text Row instead keeps them on
        // the same line.
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_trash),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(text = stringResource(CoreR.string.delete_download))
            }
        },
        text = {
            Column {
                Text(text = title)
                if (path != null) {
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sizeBytes != null) {
                    Text(
                        text = formatBinaryFileSize(sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_trash),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(
                    text = stringResource(CoreR.string.delete_download),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(painter = painterResource(CoreR.drawable.ic_x), contentDescription = null)
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(text = stringResource(CoreR.string.cancel))
            }
        },
    )
}

@Composable
private fun DeleteShowDownloadsDialog(
    seriesName: String,
    episodeCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    sizeBytes: Long? = null,
) {
    AlertDialog(
        // Not AlertDialog's own `icon` slot - Material3 always renders that centered *above* the
        // title, not inline with it. Building the title as an icon+text Row instead keeps them on
        // the same line.
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_trash),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(text = stringResource(CoreR.string.clear_season_downloads))
            }
        },
        text = {
            Column {
                Text(
                    text =
                        stringResource(
                            CoreR.string.delete_show_downloads_message,
                            episodeCount,
                            seriesName,
                        )
                )
                if (sizeBytes != null) {
                    Text(
                        text = formatBinaryFileSize(sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_trash),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(
                    text = stringResource(CoreR.string.delete_download),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(painter = painterResource(CoreR.drawable.ic_x), contentDescription = null)
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(text = stringResource(CoreR.string.cancel))
            }
        },
    )
}

private val dummyPvrQueueGroups =
    listOf(
        PvrQueueGroup(
            source = PvrSource.SONARR,
            items =
                listOf(
                    PvrQueueUiItem(
                        itemId = dummyEpisode.id,
                        title =
                            "${dummyEpisode.seriesName} - S${dummyEpisode.parentIndexNumber}E${dummyEpisode.indexNumber}",
                        item = dummyEpisode,
                        status = dummyQueueStatus,
                    ),
                    PvrQueueUiItem(
                        itemId = null,
                        title = "Some Unsynced Show - S01E02",
                        item = null,
                        status =
                            dummyQueueStatus.copy(status = QueueItemStatus.QUEUED, percent = -1),
                    ),
                ),
        ),
        PvrQueueGroup(
            source = PvrSource.RADARR,
            items =
                listOf(
                    PvrQueueUiItem(
                        itemId = dummyMovie.id,
                        title = dummyMovie.name,
                        item = dummyMovie,
                        status =
                            dummyQueueStatus.copy(
                                source = PvrSource.RADARR,
                                status = QueueItemStatus.WARNING,
                                errorMessage = "Sample import warning",
                            ),
                    )
                ),
        ),
    )

@PreviewScreenSizes
@Composable
private fun DownloadsScreenLayoutPreview() {
    JollyfinTheme {
        DownloadsScreenLayout(
            state =
                DownloadsState(
                    movies = listOf(dummyMovie),
                    showGroups =
                        listOf(
                            DownloadShowGroup(
                                seriesId = dummyEpisode.seriesId,
                                seriesName = dummyEpisode.seriesName,
                                episodes = listOf(dummyEpisode),
                            )
                        ),
                    pvrQueueGroups = dummyPvrQueueGroups,
                )
        )
    }
}

@PreviewScreenSizes
@Composable
private fun DownloadsScreenLayoutEmptyPreview() {
    JollyfinTheme { DownloadsScreenLayout(state = DownloadsState()) }
}
