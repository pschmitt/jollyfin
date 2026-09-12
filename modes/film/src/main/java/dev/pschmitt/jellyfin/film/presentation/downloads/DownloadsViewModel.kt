package dev.pschmitt.jellyfin.film.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import dev.pschmitt.jellyfin.models.PvrQueueEntry
import dev.pschmitt.jellyfin.models.PvrSource
import dev.pschmitt.jellyfin.models.isDownloadBroken
import dev.pschmitt.jellyfin.models.isDownloading
import dev.pschmitt.jellyfin.models.toJollyfinEpisodes
import dev.pschmitt.jellyfin.models.toJollyfinMovies
import dev.pschmitt.jellyfin.repository.AutoDownloadRuleRepository
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import dev.pschmitt.jellyfin.repository.PvrDiskSpaceRepository
import dev.pschmitt.jellyfin.repository.QueueStatusRepository
import dev.pschmitt.jellyfin.repository.aggregateQueueStatuses
import dev.pschmitt.jellyfin.repository.groupDuplicates
import dev.pschmitt.jellyfin.repository.seasonClusterKey
import dev.pschmitt.jellyfin.repository.seasonClusterTitle
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.utils.AutoDownloadRuleEvaluator
import dev.pschmitt.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltViewModel
class DownloadsViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val database: ServerDatabaseDao,
    private val downloader: Downloader,
    private val autoDownloadRuleRepository: AutoDownloadRuleRepository,
    private val appPreferences: AppPreferences,
    private val queueStatusRepository: QueueStatusRepository,
    private val pvrDiskSpaceRepository: PvrDiskSpaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloadsState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<DownloadsEvent>()
    val events = eventsChannel.receiveAsFlow()

    // itemId (movie or episode id) -> downloadId for every item currently being tracked by
    // progressJobs, so pause/resume/cancel can turn an item id from the UI into the downloadId
    // the Downloader API needs.
    private val downloadIdsByItem = mutableMapOf<UUID, Long>()
    private val progressJobs = mutableMapOf<UUID, Job>()
    private var refreshJob: Job? = null
    private var pvrRefreshJob: Job? = null

    val manualImport = ManualImportController(queueStatusRepository, viewModelScope)

    fun startObserving() {
        // Unlike the one-shot registrations below, re-run every time the screen is (re-)entered,
        // not just the first time this ViewModel instance observes anything - the ViewModel (and
        // refreshJob) can outlive a single visit to this screen (e.g. surviving a tab switch), in
        // which case the early return below would otherwise skip this forever after the first
        // visit.
        refreshStorage()
        if (refreshJob != null) return
        refreshJob = viewModelScope.launch {
            while (isActive) {
                refreshDownloads()
                delay(REFRESH_INTERVAL_MS)
            }
        }
        pvrRefreshJob = viewModelScope.launch {
            while (isActive) {
                // PVR ETA/speed comes from Sonarr/Radarr, not DownloadManager. Refreshing
                // while this screen is visible keeps those values useful without tightening
                // the app-wide background polling interval.
                queueStatusRepository.refreshNow()
                delay(PVR_REFRESH_INTERVAL_MS)
            }
        }
        viewModelScope.launch {
            downloader.getDeleteProgressFlow().collect { progress ->
                val wasRunning = _state.value.deleteProgress != null
                _state.update { it.copy(deleteProgress = progress) }
                // Reconcile with the DB once the batch actually finishes - e.g. a failed
                // deletion should bring its item back rather than leave it optimistically
                // gone forever. Not needed on every tick: deleteItems()/clearAllDownloads()
                // already remove the selection from the list instantly.
                if (wasRunning && progress == null) refreshDownloads()
            }
        }
        viewModelScope.launch {
            downloader.getMigrateProgressFlow().collect { progress ->
                val wasRunning = _state.value.moveProgress != null
                _state.update { it.copy(moveProgress = progress) }
                if (wasRunning && progress == null) {
                    // Storage usage shifted between volumes - refresh the summary bars. Also
                    // re-fetch the items themselves: their LOCAL source path just changed, which
                    // is what the per-row Internal/External icon is resolved from.
                    _state.update { it.copy(migratingIds = emptySet()) }
                    refreshStorage()
                    refreshDownloads()
                }
            }
        }
        viewModelScope.launch {
            queueStatusRepository.getQueueSnapshotFlow().collect { snapshot ->
                val groups = buildPvrQueueGroups(snapshot.entries)
                val liveKeys =
                    groups
                        .flatMap { g ->
                            g.items.flatMap {
                                it.clusteredQueueItemIds.map { id -> g.source to id }
                            }
                        }
                        .toSet()
                _state.update {
                    it.copy(
                        pvrQueueGroups = groups,
                        pvrErrors = snapshot.errors,
                        pvrPendingSources = snapshot.pendingSources,
                        selectedPvrQueueIds = it.selectedPvrQueueIds.intersect(liveKeys),
                    )
                }
            }
        }
    }

    // Storage numbers don't change minute to minute, so this is a one-shot fetch on entering the
    // screen and on pull-to-refresh, not part of the polling loops above.
    //
    // These two fetches deliberately run as independent launches rather than sequentially, since
    // the PVR disk-space call is a network round trip (Sonarr/Radarr HTTP APIs, see
    // PvrDiskSpaceRepositoryImpl) while the device storage stats are a local StatFs call - no
    // reason to make the fast local one wait on the slow network one. This is also the root cause
    // of the previously-observed flaky on-device storage bar: with the old
    // `_state.value = _state.value.copy(...)` pattern, `_state.value` (the receiver of `.copy`) is
    // read/snapshotted *before* the suspending call on the same line, not after it resumes -
    // Kotlin evaluates a call's receiver before its arguments. So if this coroutine snapshots state
    // S0, then suspends on the network call, and the *other* launch below finishes first and writes
    // S1 = S0.copy(deviceStorage = ...), this coroutine resumes still holding stale S0 and does
    // `_state.value = S0.copy(diskSpace = ...)`, silently overwriting S1 and dropping the
    // deviceStorage update entirely. That race is timing-sensitive (depends on which of the two
    // calls happens to resolve first), which explains why it appeared/disappeared across
    // otherwise-identical runs. `_state.update { it.copy(...) }` fixes this: it always applies the
    // transform to the *current* value at the moment of the atomic update, never a pre-suspend
    // snapshot, so neither launch can clobber the other's write regardless of ordering.
    private fun refreshStorage() {
        viewModelScope.launch {
            val diskSpace = pvrDiskSpaceRepository.getDiskSpace()
            _state.update { it.copy(diskSpace = diskSpace) }
        }
        viewModelScope.launch {
            val deviceStorages = withContext(Dispatchers.IO) { downloader.getAllStorageStats() }
            _state.update { it.copy(deviceStorages = deviceStorages) }
        }
    }

    private suspend fun refreshDownloads() {
        if (_state.value.isEmpty) {
            _state.update { it.copy(isLoading = true, error = null) }
        }
        try {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return
            val userId = repository.getUserId()

            // toJollyfinMovies/toJollyfinEpisodes batch-fetch user data, sources, media streams
            // and trickplay info for the whole list up front (a handful of queries total) instead
            // of the per-row N+1 query pattern the singular toJollyfinMovie/toJollyfinEpisode do -
            // see their kdoc in JollyfinMovie.kt/JollyfinEpisode.kt for why that mattered here.
            val movies =
                withContext(Dispatchers.Default) {
                    database.getMoviesByServerId(serverId).toJollyfinMovies(database, userId)
                }
            val episodes =
                withContext(Dispatchers.Default) {
                    database.getEpisodesByServerId(serverId).toJollyfinEpisodes(database, userId)
                }
            val showGroups =
                withContext(Dispatchers.Default) {
                    episodes
                        .groupBy { it.seriesId }
                        .map { (seriesId, showEpisodes) ->
                            DownloadShowGroup(
                                seriesId = seriesId,
                                seriesName = showEpisodes.first().seriesName,
                                episodes =
                                    showEpisodes.sortedWith(
                                        compareBy({ it.parentIndexNumber }, { it.indexNumber })
                                    ),
                            )
                        }
                        .sortedBy { it.seriesName }
                }

            val allIds = (movies.map { it.id } + episodes.map { it.id }).toSet()
            _state.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    movies = movies,
                    showGroups = showGroups,
                    selectedIds = it.selectedIds.intersect(allIds),
                    autoDeleteWatchedEnabled =
                        appPreferences.getValue(appPreferences.autoDeleteWatched),
                    autoDeleteWatchedHours =
                        appPreferences.getValue(appPreferences.autoDeleteWatchedHours),
                    maxDownloadSizeEnabled =
                        appPreferences.getValue(appPreferences.maxDownloadSizeEnabled),
                    maxDownloadSizeGb = appPreferences.getValue(appPreferences.maxDownloadSizeGb),
                )
            }
            reconcileDownloadProgress(movies, episodes)
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, isRefreshing = false, error = e) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            queueStatusRepository.refreshNow()
            evaluateAutoDownloadRules()
            refreshDownloads()
        }
        refreshStorage()
    }

    // Mirrors AutoDownloadWorker's own evaluation - a manual pull-to-refresh shouldn't have to
    // wait for the next scheduled background check to pick up episodes a rule already covers.
    private suspend fun evaluateAutoDownloadRules() {
        try {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return
            val userId = repository.getUserId()
            val evaluator = AutoDownloadRuleEvaluator()
            for (rule in autoDownloadRuleRepository.getEnabledRules(serverId, userId)) {
                evaluator.evaluate(rule, database, repository, downloader, appPreferences)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to evaluate auto-download rules on manual refresh")
        }
    }

    private fun reconcileDownloadProgress(
        movies: List<JollyfinMovie>,
        episodes: List<JollyfinEpisode>,
    ) {
        val trackedItems: List<Pair<UUID, JollyfinItem>> =
            movies.filter { it.isDownloading() }.map { it.id to it } +
                episodes.filter { it.isDownloading() }.map { it.id to it }
        val desiredIds = trackedItems.map { it.first }.toSet()

        (progressJobs.keys - desiredIds).forEach { id ->
            progressJobs.remove(id)?.cancel()
            downloadIdsByItem.remove(id)
            _state.update { it.copy(downloadProgress = it.downloadProgress - id) }
        }

        trackedItems.forEach { (id, item) ->
            if (progressJobs.containsKey(id)) return@forEach
            val downloadId =
                item.sources.firstOrNull { it.type == JollyfinSourceType.LOCAL }?.downloadId
                    ?: return@forEach
            downloadIdsByItem[id] = downloadId
            progressJobs[id] = viewModelScope.launch {
                downloader.getProgressFlow(downloadId).collect { progress ->
                    _state.update {
                        it.copy(downloadProgress = it.downloadProgress + (id to progress))
                    }
                }
            }
        }
    }

    // Local-download selection and PVR-queue selection are mutually exclusive - both drive the
    // same top app bar (selection count, clear button, bulk-delete action), and a single bar can't
    // meaningfully represent two independent selections at once. Every toggle below clears the
    // other side's selection as soon as it goes from empty to non-empty.

    fun toggleSelection(id: UUID) {
        _state.update {
            val selectedIds = it.selectedIds
            val newSelectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            it.copy(
                selectedIds = newSelectedIds,
                selectedPvrQueueIds =
                    if (newSelectedIds.isNotEmpty()) emptySet() else it.selectedPvrQueueIds,
            )
        }
    }

    fun toggleSelectAll(selectAll: Boolean) {
        _state.update { state ->
            val allIds =
                (state.movies.map { it.id } +
                        state.showGroups.flatMap { it.episodes }.map { it.id })
                    .toSet()
            val newSelectedIds = if (selectAll) allIds else emptySet()
            state.copy(
                selectedIds = newSelectedIds,
                selectedPvrQueueIds =
                    if (newSelectedIds.isNotEmpty()) emptySet() else state.selectedPvrQueueIds,
            )
        }
    }

    fun setGroupSelected(ids: Set<UUID>, selected: Boolean) {
        _state.update {
            val selectedIds = it.selectedIds
            val newSelectedIds = if (selected) selectedIds + ids else selectedIds - ids
            it.copy(
                selectedIds = newSelectedIds,
                selectedPvrQueueIds =
                    if (newSelectedIds.isNotEmpty()) emptySet() else it.selectedPvrQueueIds,
            )
        }
    }

    /**
     * Toggles every id of a queue row's cluster together - a single-element list in the common (non
     * season-clustered) case, so this is also the plain per-row toggle.
     */
    fun togglePvrQueueSelectionCluster(source: PvrSource, queueItemIds: List<Int>) {
        _state.update {
            val keys = queueItemIds.map { id -> source to id }.toSet()
            val selected = it.selectedPvrQueueIds
            val allSelected = keys.isNotEmpty() && selected.containsAll(keys)
            val newSelected = if (allSelected) selected - keys else selected + keys
            it.copy(
                selectedPvrQueueIds = newSelected,
                selectedIds = if (newSelected.isNotEmpty()) emptySet() else it.selectedIds,
            )
        }
    }

    fun togglePvrQueueSelectAll(selectAll: Boolean) {
        _state.update { state ->
            val allKeys =
                state.pvrQueueGroups
                    .flatMap { group ->
                        group.items.flatMap { item ->
                            item.clusteredQueueItemIds.map { group.source to it }
                        }
                    }
                    .toSet()
            val newSelected = if (selectAll) allKeys else emptySet()
            state.copy(
                selectedPvrQueueIds = newSelected,
                selectedIds = if (newSelected.isNotEmpty()) emptySet() else state.selectedIds,
            )
        }
    }

    fun deleteSelected() {
        deleteItems(_state.value.selectedIds.toList())
    }

    fun deleteItem(id: UUID) {
        deleteItems(listOf(id))
    }

    fun deleteItems(ids: List<UUID>) {
        // Remove from the list instantly - the actual file/DB deletion runs in the background
        // via DeleteDownloadsWorker (see Downloader.deleteItems), which can take a while for a
        // large batch. Waiting for it before updating the UI (the old behaviour) is what made
        // bulk deletion feel choppy: the row would sit there until the next periodic refresh
        // caught up, then several rows would vanish at once.
        val idsSet = ids.toSet()
        _state.update { state ->
            state.copy(
                movies = state.movies.filterNot { it.id in idsSet },
                showGroups =
                    state.showGroups.mapNotNull { group ->
                        val remaining = group.episodes.filterNot { it.id in idsSet }
                        remaining.takeIf { it.isNotEmpty() }?.let { group.copy(episodes = it) }
                    },
                selectedIds = state.selectedIds - idsSet,
            )
        }
        viewModelScope.launch { downloader.deleteItems(ids) }
    }

    /**
     * Moves the current selection to a different storage volume - see [Downloader.migrateItems].
     */
    fun migrateSelected(toStorageIndex: Int) {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty()) return
        // Mark these ids as moving right away, not after migrateItems() returns - it only
        // enqueues MigrateDownloadsWorker (fast), so waiting on it first would leave a beat where
        // the selection's cleared but no "moving" indicator has appeared yet.
        _state.update {
            it.copy(
                selectedIds = it.selectedIds - ids.toSet(),
                migratingIds = it.migratingIds + ids,
            )
        }
        viewModelScope.launch { downloader.migrateItems(ids, toStorageIndex) }
    }

    /**
     * Re-triggers a download for an item whose local file is missing/empty on disk (see
     * [dev.pschmitt.jellyfin.models.isDownloadBroken]) - e.g. after the storage volume it lived on
     * got reformatted. [Downloader.downloadItem] re-inserts the source row in place (same id,
     * `OnConflictStrategy.REPLACE`) rather than erroring on the stale one, so no explicit
     * delete-first step is needed.
     */
    fun redownloadItem(item: JollyfinItem) {
        viewModelScope.launch {
            redownload(item, downloader.resolvePreferredStorageIndex())
            refreshDownloads()
        }
    }

    /** Bulk version of [redownloadItem] - every broken movie/episode currently in the list. */
    fun redownloadAllBroken() {
        viewModelScope.launch {
            val broken =
                (_state.value.movies + _state.value.showGroups.flatMap { it.episodes }).filter {
                    it.isDownloadBroken()
                }
            if (broken.isEmpty()) return@launch
            val storageIndex = downloader.resolvePreferredStorageIndex()
            broken.forEach { redownload(it, storageIndex) }
            refreshDownloads()
        }
    }

    private suspend fun redownload(item: JollyfinItem, storageIndex: Int) {
        val sourceId =
            item.sources.firstOrNull { it.type == JollyfinSourceType.LOCAL }?.id ?: return
        downloader.downloadItem(item, sourceId, storageIndex)
    }

    fun onDownloadAction(itemId: UUID, action: DownloadAction) {
        viewModelScope.launch {
            val downloadId = downloadIdsByItem[itemId] ?: return@launch
            when (action) {
                DownloadAction.Pause -> downloader.pauseDownload(downloadId)
                DownloadAction.Resume -> downloader.resumeDownload(downloadId)
                DownloadAction.Force -> downloader.forceDownload(downloadId)
                DownloadAction.Cancel -> {
                    downloader.cancelDownload(downloadId)
                    refreshDownloads()
                }
            }
        }
    }

    fun forceGroup(episodeIds: List<UUID>) {
        viewModelScope.launch {
            val downloadIds = episodeIds.mapNotNull { downloadIdsByItem[it] }
            if (downloadIds.isNotEmpty()) downloader.forceDownloadGroup(downloadIds)
        }
    }

    fun pauseAll() {
        viewModelScope.launch {
            downloadIdsByItem.values.toList().forEach { downloader.pauseDownload(it) }
        }
    }

    fun resumeAll() {
        viewModelScope.launch {
            downloadIdsByItem.values.toList().forEach { downloader.resumeDownload(it) }
        }
    }

    /**
     * Removes a Sonarr/Radarr queue entry (there is no API-side pause - that lives in the download
     * client). See [QueueStatusRepository.removeQueueItem] for the flag semantics. A season-
     * clustered row (`item.clusteredQueueItemIds.size > 1`) removes every underlying episode via
     * the bulk endpoint instead, same as [removeSelectedPvrQueueItems].
     */
    fun removePvrQueueItem(
        item: PvrQueueUiItem,
        source: PvrSource,
        removeFromClient: Boolean,
        blocklist: Boolean,
    ) {
        viewModelScope.launch {
            if (item.clusteredQueueItemIds.size <= 1) {
                queueStatusRepository
                    .removeQueueItem(
                        source = source,
                        queueItemId = item.queueItemId,
                        removeFromClient = removeFromClient,
                        blocklist = blocklist,
                    )
                    .fold(
                        onSuccess = {
                            eventsChannel.send(DownloadsEvent.PvrQueueItemRemoved(item.title))
                        },
                        onFailure = { e ->
                            eventsChannel.send(DownloadsEvent.PvrQueueItemRemoveFailed(e.message))
                        },
                    )
            } else {
                val keys = item.clusteredQueueItemIds.map { source to it }
                val failed =
                    queueStatusRepository.removeQueueItems(keys, removeFromClient, blocklist)
                eventsChannel.send(
                    DownloadsEvent.PvrQueueItemsRemoved(
                        removed = keys.size - failed.size,
                        failed = failed.size,
                    )
                )
            }
        }
    }

    /** Bulk version of [removePvrQueueItem] - e.g. "clear all pending downloads". */
    fun removeSelectedPvrQueueItems(removeFromClient: Boolean, blocklist: Boolean) {
        viewModelScope.launch {
            val selected = _state.value.selectedPvrQueueIds.toList()
            val failed =
                queueStatusRepository.removeQueueItems(selected, removeFromClient, blocklist)
            _state.update { it.copy(selectedPvrQueueIds = emptySet()) }
            eventsChannel.send(
                DownloadsEvent.PvrQueueItemsRemoved(
                    removed = selected.size - failed.size,
                    failed = failed.size,
                )
            )
        }
    }

    /**
     * Opens the "manage imports" sheet for a queue entry (see [ManualImportSheetState]) - seeded
     * with every entry in [item]'s duplicate cluster (see [PvrQueueUiItem.duplicates]), not just
     * the one this row displays, so the sheet can offer a choice when there's more than one. No-ops
     * when none of the cluster's entries have a `downloadId` - shouldn't happen in practice, but
     * Sonarr/Radarr technically don't guarantee the field.
     */
    fun openManualImport(item: PvrQueueUiItem, source: PvrSource) {
        val refs =
            item.duplicates.mapNotNull { entry ->
                entry.status.downloadId?.let { PendingImportRef(source, it, entry.queueItemId) }
            }
        if (refs.isEmpty()) return
        manualImport.open(item.title, refs)
    }

    fun confirmManualImport() {
        manualImport.confirm(
            onSuccess = {
                viewModelScope.launch { eventsChannel.send(DownloadsEvent.ManualImportCompleted) }
            },
            onFailure = { message ->
                viewModelScope.launch {
                    eventsChannel.send(DownloadsEvent.ManualImportFailed(message))
                }
            },
        )
    }

    /**
     * Rejects the whole release cluster the "manage imports" sheet is reviewing - removes every
     * entry in it and (usually) blocklists it, e.g. a release Sonarr/Radarr flagged as suspicious
     * or one where none of the files are worth importing. Distinct from [confirmManualImport],
     * which imports a subset of the files from one entry instead of discarding the cluster
     * outright.
     */
    fun rejectManualImport(removeFromClient: Boolean, blocklist: Boolean) {
        val title = manualImport.state.value?.title ?: return
        manualImport.reject(
            removeFromClient,
            blocklist,
            onSuccess = {
                viewModelScope.launch {
                    eventsChannel.send(DownloadsEvent.PvrQueueItemRemoved(title))
                }
            },
            onFailure = { message ->
                viewModelScope.launch {
                    eventsChannel.send(DownloadsEvent.PvrQueueItemRemoveFailed(message))
                }
            },
        )
    }

    fun clearAllDownloads(alsoRemoveRules: Boolean) {
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return@launch
            val userId = repository.getUserId()

            val itemIds =
                withContext(Dispatchers.Default) {
                    database.getMoviesByServerId(serverId).map { it.id } +
                        database.getEpisodesByServerId(serverId).map { it.id }
                }
            // Same optimistic-removal reasoning as deleteItems(): clear the list instantly
            // rather than waiting on the background worker.
            _state.update {
                it.copy(movies = emptyList(), showGroups = emptyList(), selectedIds = emptySet())
            }
            downloader.deleteItems(itemIds)

            if (alsoRemoveRules) {
                autoDownloadRuleRepository.deleteAllRules(serverId, userId)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        pvrRefreshJob?.cancel()
        progressJobs.values.forEach { it.cancel() }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 3000L
        private const val PVR_REFRESH_INTERVAL_MS = 10_000L
    }
}

/**
 * Maps [QueueStatusRepository]'s queue-entry snapshot into the UI-facing [PvrQueueGroup] list.
 * Matched entries carry the live-library [JollyfinItem][dev.pschmitt.jellyfin.models.JollyfinItem]
 * the repository resolved (poster + click-through); unmatched entries (e.g. a torrent added
 * manually on the PVR side for something not yet in Jellyfin) become title-only rows using the
 * PVR-side title the repository built.
 *
 * A free function (not a method) so it's directly unit-testable without a ViewModel/Hilt/Android in
 * the loop.
 */
internal fun buildPvrQueueGroups(entries: List<PvrQueueEntry>): List<PvrQueueGroup> =
    entries
        .groupBy { it.status.source }
        .map { (source, groupEntries) ->
            val episodeRows =
                groupEntries.groupDuplicates().map { cluster ->
                    // Duplicates share the same title/poster/ids by construction (that's what
                    // makes them a cluster) - only status can differ moment to moment, so the
                    // most recently-seen entry's is the freshest to show.
                    val entry = cluster.last()
                    PvrQueueUiItem(
                        itemId = entry.item?.id,
                        title = entry.item.toQueueTitle(fallback = entry.title),
                        subtitle = (entry.item as? JollyfinEpisode)?.name,
                        item = entry.item,
                        posterUrl = entry.posterUrl,
                        tmdbId = entry.tmdbId,
                        sonarrEpisodeId = entry.sonarrEpisodeId,
                        seasonNumber = entry.seasonNumber,
                        episodeNumber = entry.episodeNumber,
                        status = entry.status,
                        queueItemId = entry.queueItemId,
                        duplicates = cluster,
                    )
                }
            PvrQueueGroup(source = source, items = episodeRows.clusterSeasons(source))
        }

/**
 * Second pass over [buildPvrQueueGroups]'s already-deduped per-episode rows: merges distinct
 * episodes of the same show+season (per [seasonClusterKey]) into one synthesized row, so a season
 * grabbed as several separate per-episode downloads reads as one "Show - Season N (X episodes)" row
 * instead of X. Singleton groups (the common case) pass through unchanged.
 */
private fun List<PvrQueueUiItem>.clusterSeasons(source: PvrSource): List<PvrQueueUiItem> {
    val groups = LinkedHashMap<Any, MutableList<PvrQueueUiItem>>()
    for (row in this) {
        val key: Any =
            seasonClusterKey(
                source,
                row.status.status,
                row.tmdbId,
                row.seasonNumber,
                row.episodeNumber,
            ) ?: row
        groups.getOrPut(key) { mutableListOf() }.add(row)
    }
    return groups.values.map { group ->
        val rep = group.last()
        if (group.size == 1) {
            rep
        } else {
            rep.copy(
                title = rep.seasonNumber?.let { seasonClusterTitle(rep.title, it) } ?: rep.title,
                subtitle = null,
                status = aggregateQueueStatuses(group.map { it.status }),
                duplicates = emptyList(),
                episodeCount = group.size,
                clusteredQueueItemIds = group.map { it.queueItemId },
            )
        }
    }
}

private fun JollyfinItem?.toQueueTitle(fallback: String): String =
    when (this) {
        is JollyfinEpisode -> "$seriesName - S${parentIndexNumber}E$indexNumber"
        is JollyfinMovie -> name
        null -> fallback
        else -> name
    }
