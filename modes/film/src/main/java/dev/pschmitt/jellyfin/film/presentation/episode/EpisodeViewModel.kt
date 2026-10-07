package dev.pschmitt.jellyfin.film.presentation.episode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.api.pvr.PvrRelease
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.presentation.delete.DeleteItemEvent
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSelection
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSizeEstimate
import dev.pschmitt.jellyfin.core.presentation.search.ReleasePickerState
import dev.pschmitt.jellyfin.core.presentation.search.SearchEvent
import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.di.ApplicationScope
import dev.pschmitt.jellyfin.film.domain.VideoMetadataParser
import dev.pschmitt.jellyfin.film.presentation.downloads.ManualImportController
import dev.pschmitt.jellyfin.film.presentation.downloads.PendingImportRef
import dev.pschmitt.jellyfin.models.AutoDownloadRuleDto
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinItemPerson
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import dev.pschmitt.jellyfin.models.QueueItemStatus
import dev.pschmitt.jellyfin.models.RemoteDeviceInfo
import dev.pschmitt.jellyfin.pvr.PvrConfiguration
import dev.pschmitt.jellyfin.pvr.PvrWebUiLinks
import dev.pschmitt.jellyfin.repository.AutoDownloadRuleRepository
import dev.pschmitt.jellyfin.repository.ExistingAutoDownloadScope
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import dev.pschmitt.jellyfin.repository.QueueStatusRepository
import dev.pschmitt.jellyfin.repository.RemoteConfigRepository
import dev.pschmitt.jellyfin.repository.SonarrSearchRepository
import dev.pschmitt.jellyfin.repository.toExistingScope
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.utils.AutoDownloadRuleEvaluator
import dev.pschmitt.jellyfin.utils.Downloader
import dev.pschmitt.jellyfin.utils.clearDownloads
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PersonKind
import timber.log.Timber

@HiltViewModel
class EpisodeViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val videoMetadataParser: VideoMetadataParser,
    private val database: ServerDatabaseDao,
    private val downloader: Downloader,
    private val autoDownloadRuleRepository: AutoDownloadRuleRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
    private val sonarrSearchRepository: SonarrSearchRepository,
    private val queueStatusRepository: QueueStatusRepository,
    private val pvrConfiguration: PvrConfiguration,
    private val pvrWebUiLinks: PvrWebUiLinks,
    @ApplicationScope private val externalScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(EpisodeState())
    val state = _state.asStateFlow()

    private val searchEventsChannel = Channel<SearchEvent>()
    val searchEvents = searchEventsChannel.receiveAsFlow()

    private val deleteEventsChannel = Channel<DeleteItemEvent>()
    val deleteEvents = deleteEventsChannel.receiveAsFlow()

    private val evaluator = AutoDownloadRuleEvaluator()

    private var queueStatusJob: Job? = null

    val manualImport = ManualImportController(queueStatusRepository, viewModelScope)

    lateinit var episodeId: UUID

    /** Opens the manage-import sheet for this episode's own PVR queue entry, if it has one. */
    fun openManualImportForCurrentItem() {
        val status = _state.value.queueStatus ?: return
        if (status.status != QueueItemStatus.WARNING && status.status != QueueItemStatus.FAILED)
            return
        val title = _state.value.episode?.name ?: return
        val refs =
            _state.value.queueEntries.mapNotNull { entry ->
                entry.status.downloadId?.let {
                    PendingImportRef(entry.status.source, it, entry.queueItemId)
                }
            }
        if (refs.isEmpty()) return
        manualImport.open(title, refs)
    }

    private fun observeQueueStatus(episodeId: UUID) {
        if (queueStatusJob != null) return
        queueStatusJob = viewModelScope.launch {
            queueStatusRepository.getQueueStatusFlow(episodeId).collect { status ->
                _state.value = _state.value.copy(queueStatus = status)
            }
        }
        viewModelScope.launch {
            queueStatusRepository.getQueueEntriesFlow(episodeId).collect { entries ->
                _state.value = _state.value.copy(queueEntries = entries)
            }
        }
    }

    fun loadEpisode(episodeId: UUID) {
        this.episodeId = episodeId
        observeQueueStatus(episodeId)
        viewModelScope.launch {
            _state.emit(_state.value.copy(isRefreshing = true))
            try {
                val episode = repository.getEpisode(episodeId)
                val videoMetadata = videoMetadataParser.parse(episode.sources.first())
                val actors = getActors(episode)
                val dateFormat = appPreferences.getValue(appPreferences.dateFormat)
                val existingScope = getExistingScope(episode.seriesId)
                val series = repository.getShow(episode.seriesId)
                val seriesTvdbId = series.tvdbId
                val canDelete = repository.canDeleteMedia()
                _state.emit(
                    _state.value.copy(
                        episode = episode,
                        videoMetadata = videoMetadata,
                        actors = actors,
                        dateFormat = dateFormat,
                        existingScope = existingScope,
                        seriesTvdbId = seriesTvdbId,
                        seriesTmdbId = series.tmdbId?.toIntOrNull(),
                        sonarrConfigured = pvrConfiguration.isSonarrConfigured(),
                        webUiServices =
                            pvrWebUiLinks.availableServices().filter { it != PvrService.RADARR },
                        canDelete = canDelete,
                        autoDeleteWatchedEnabled =
                            appPreferences.getValue(appPreferences.autoDeleteWatched),
                        autoDeleteWatchedHours =
                            appPreferences.getValue(appPreferences.autoDeleteWatchedHours),
                        isRefreshing = false,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e, isRefreshing = false))
            }
        }
    }

    private suspend fun resolveTargetEpisodeId(): Int? {
        val episode = _state.value.episode ?: return null
        val seriesTvdbId = _state.value.seriesTvdbId ?: return null
        return sonarrSearchRepository.resolveEpisodeId(
            seriesTvdbId,
            episode.parentIndexNumber,
            episode.indexNumber,
        )
    }

    private fun searchEpisodeAutomatic() {
        viewModelScope.launch {
            val episodeId = resolveTargetEpisodeId()
            val event =
                if (episodeId == null) {
                    SearchEvent.Failed("Could not find this episode in Sonarr")
                } else {
                    sonarrSearchRepository
                        .searchEpisode(episodeId)
                        .fold({ SearchEvent.SearchTriggered }, { SearchEvent.Failed(it.message) })
                }
            searchEventsChannel.send(event)
        }
    }

    private fun openReleasePicker() {
        viewModelScope.launch {
            _state.value = _state.value.copy(releasePicker = ReleasePickerState())
            val episodeId = resolveTargetEpisodeId()
            if (episodeId == null) {
                _state.value = _state.value.copy(releasePicker = null)
                searchEventsChannel.send(
                    SearchEvent.Failed("Could not find this episode in Sonarr")
                )
                return@launch
            }
            val result = sonarrSearchRepository.getReleases(episodeId)
            _state.value =
                _state.value.copy(
                    releasePicker =
                        result.getOrNull()?.let {
                            ReleasePickerState(isLoading = false, releases = it)
                        }
                )
            result.onFailure { searchEventsChannel.send(SearchEvent.Failed(it.message)) }
        }
    }

    private fun grabRelease(release: PvrRelease) {
        viewModelScope.launch {
            val result = sonarrSearchRepository.grabRelease(release)
            _state.value = _state.value.copy(releasePicker = null)
            searchEventsChannel.send(
                result.fold({ SearchEvent.ReleaseGrabbed }, { SearchEvent.Failed(it.message) })
            )
        }
    }

    private fun deleteItem(cascadeToPvr: Boolean) {
        viewModelScope.launch {
            try {
                repository.deleteItem(episodeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deleteEventsChannel.send(DeleteItemEvent.Failed(e.message))
                return@launch
            }
            // The Jellyfin delete already succeeded at this point - a failed PVR cascade is
            // logged, not surfaced as a failure, so the user isn't told the whole action failed
            // when only this best-effort cleanup step didn't.
            if (cascadeToPvr) {
                val episode = _state.value.episode
                val seriesTvdbId = _state.value.seriesTvdbId
                if (episode != null && seriesTvdbId != null) {
                    sonarrSearchRepository
                        .unmonitorEpisode(
                            seriesTvdbId,
                            episode.parentIndexNumber,
                            episode.indexNumber,
                        )
                        .onFailure { Timber.w(it, "Failed to cascade episode delete to Sonarr") }
                }
            }
            // The episode no longer exists on the server - no point leaving an orphaned local
            // download (file + DB rows) pointing at it behind.
            _state.value.episode?.let { clearDownloads(listOf(it), database, downloader) }
            deleteEventsChannel.send(DeleteItemEvent.Deleted)
        }
    }

    private suspend fun getActors(item: JollyfinEpisode): List<JollyfinItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    suspend fun getSeasons(): List<JollyfinSeason> {
        val seriesId = _state.value.episode?.seriesId ?: return emptyList()
        return repository.getSeasons(seriesId)
    }

    /**
     * Count and total primary-source size of [seasonId]'s episodes that would actually be
     * downloaded right now - excludes episodes already downloaded locally, and (if [onlyUnwatched])
     * already-watched ones. Mirrors ShowViewModel/SeasonViewModel's method of the same name for the
     * bulk-selection part of this screen's download-scope dialog.
     */
    suspend fun getUndownloadedEpisodeSize(
        seasonId: UUID,
        onlyUnwatched: Boolean,
    ): DownloadSizeEstimate {
        val seriesId = _state.value.episode?.seriesId ?: return DownloadSizeEstimate()
        val episodes =
            try {
                repository.getEpisodes(
                    seriesId = seriesId,
                    seasonId = seasonId,
                    fields = listOf(ItemFields.MEDIA_SOURCES),
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to fetch episode sizes for season $seasonId")
                return DownloadSizeEstimate()
            }
        return withContext(Dispatchers.IO) {
            val pending =
                episodes
                    .filter { !onlyUnwatched || !it.played }
                    .filter { database.getSources(it.id).isEmpty() }
            DownloadSizeEstimate(
                sizeBytes = pending.sumOf { it.sources.firstOrNull()?.size ?: 0 },
                itemCount = pending.size,
            )
        }
    }

    private suspend fun getExistingScope(seriesId: UUID): ExistingAutoDownloadScope {
        val serverId =
            appPreferences.getValue(appPreferences.currentServer)
                ?: return ExistingAutoDownloadScope()
        val userId = repository.getUserId()
        return autoDownloadRuleRepository
            .getRulesForSeries(serverId, userId, seriesId)
            .toExistingScope()
    }

    suspend fun getOtherDevices(): List<RemoteDeviceInfo> =
        remoteConfigRepository.listOtherDevices()

    private fun downloadWithScope(
        selection: DownloadSelection,
        alsoFollowNew: Boolean,
        onlyUnwatched: Boolean,
        targetDeviceId: String? = null,
    ) {
        val episode = _state.value.episode ?: return
        // Deliberately not viewModelScope - see ShowViewModel.downloadWithScope's kdoc for why:
        // it would otherwise be silently cancelled (truncating the batch) as soon as the user
        // navigates away from this screen while the enqueue loop is still running.
        externalScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return@launch
            val userId = repository.getUserId()

            if (targetDeviceId != null) {
                remoteConfigRepository.pushDownloadWithScope(
                    targetDeviceId = targetDeviceId,
                    serverId = serverId,
                    userId = userId,
                    seriesId = episode.seriesId,
                    seasonIds = selection.seasonIds,
                    alsoFollowNew = alsoFollowNew,
                    alsoFutureSeasons = selection.alsoFutureSeasons,
                    onlyUnwatched = onlyUnwatched,
                )
                return@launch
            }

            for (targetSeasonId in selection.seasonIds) {
                val transientRule =
                    AutoDownloadRuleDto(
                        serverId = serverId,
                        userId = userId,
                        seriesId = episode.seriesId,
                        seasonId = targetSeasonId,
                        enabled = true,
                        createdAt = System.currentTimeMillis(),
                        onlyNewEpisodes = false,
                    )
                evaluator.evaluate(
                    transientRule,
                    database,
                    repository,
                    downloader,
                    appPreferences,
                    onlyUnwatched,
                )
            }

            if (alsoFollowNew || selection.alsoFutureSeasons) {
                autoDownloadRuleRepository.reconcileRules(
                    serverId = serverId,
                    userId = userId,
                    seriesId = episode.seriesId,
                    seasonIds = if (alsoFollowNew) selection.seasonIds else emptySet(),
                    alsoFutureSeasons = selection.alsoFutureSeasons,
                    onlyNewEpisodes = false,
                    onlyUnwatched = onlyUnwatched,
                )
            }
        }
    }

    fun onAction(action: EpisodeAction) {
        when (action) {
            is EpisodeAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(episodeId)
                    loadEpisode(episodeId)
                }
            }
            is EpisodeAction.DownloadWithScope ->
                downloadWithScope(
                    action.selection,
                    action.alsoFollowNew,
                    action.onlyUnwatched,
                    action.targetDeviceId,
                )
            is EpisodeAction.DeleteItem -> deleteItem(action.cascadeToPvr)
            is EpisodeAction.SearchEpisodeAutomatic -> searchEpisodeAutomatic()
            is EpisodeAction.OpenReleasePicker -> openReleasePicker()
            is EpisodeAction.GrabRelease -> grabRelease(action.release)
            is EpisodeAction.DismissReleasePicker ->
                _state.value = _state.value.copy(releasePicker = null)
            is EpisodeAction.ToggleExcludeFromAutoDelete -> toggleExcludeFromAutoDelete()
            else -> Unit
        }
    }

    private fun toggleExcludeFromAutoDelete() {
        val source =
            _state.value.episode?.sources?.firstOrNull { it.type == JollyfinSourceType.LOCAL }
                ?: return
        viewModelScope.launch {
            database.setSourceExcludeFromAutoDelete(source.id, !source.excludeFromAutoDelete)
            loadEpisode(episodeId)
        }
    }
}
