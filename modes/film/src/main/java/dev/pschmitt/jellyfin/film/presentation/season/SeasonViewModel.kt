package dev.pschmitt.jellyfin.film.presentation.season

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.api.pvr.PvrRelease
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSelection
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSizeEstimate
import dev.pschmitt.jellyfin.core.presentation.search.ReleasePickerState
import dev.pschmitt.jellyfin.core.presentation.search.SearchEvent
import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.di.ApplicationScope
import dev.pschmitt.jellyfin.models.AutoDownloadRuleDto
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import dev.pschmitt.jellyfin.models.RemoteDeviceInfo
import dev.pschmitt.jellyfin.models.isDownloading
import dev.pschmitt.jellyfin.models.toJollyfinEpisode
import dev.pschmitt.jellyfin.pvr.PvrConfiguration
import dev.pschmitt.jellyfin.pvr.PvrWebUiLinks
import dev.pschmitt.jellyfin.repository.AutoDownloadRuleRepository
import dev.pschmitt.jellyfin.repository.ExistingAutoDownloadScope
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import dev.pschmitt.jellyfin.repository.PendingDownloadRequestRepository
import dev.pschmitt.jellyfin.repository.QueueStatusRepository
import dev.pschmitt.jellyfin.repository.RemoteConfigRepository
import dev.pschmitt.jellyfin.repository.SeasonEpisodesRepository
import dev.pschmitt.jellyfin.repository.SonarrSearchRepository
import dev.pschmitt.jellyfin.repository.toExistingScope
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.utils.AutoDownloadRuleEvaluator
import dev.pschmitt.jellyfin.utils.Downloader
import dev.pschmitt.jellyfin.utils.clearDownloads
import java.io.File
import java.util.UUID
import javax.inject.Inject
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
import timber.log.Timber

@HiltViewModel
class SeasonViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val database: ServerDatabaseDao,
    private val downloader: Downloader,
    private val autoDownloadRuleRepository: AutoDownloadRuleRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
    private val appPreferences: AppPreferences,
    private val queueStatusRepository: QueueStatusRepository,
    private val seasonEpisodesRepository: SeasonEpisodesRepository,
    private val sonarrSearchRepository: SonarrSearchRepository,
    private val pvrConfiguration: PvrConfiguration,
    private val pvrWebUiLinks: PvrWebUiLinks,
    private val pendingDownloadRequestRepository: PendingDownloadRequestRepository,
    @ApplicationScope private val externalScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(SeasonState())
    val state = _state.asStateFlow()

    private val searchEventsChannel = Channel<SearchEvent>()
    val searchEvents = searchEventsChannel.receiveAsFlow()

    private val evaluator = AutoDownloadRuleEvaluator()

    private val downloadIdsByEpisode = mutableMapOf<UUID, Long>()
    private val progressJobs = mutableMapOf<UUID, Job>()
    private var queueStatusJob: Job? = null

    lateinit var seasonId: UUID
    private var seriesId: UUID? = null

    fun loadSeason(seasonId: UUID) {
        this.seasonId = seasonId
        viewModelScope.launch {
            _state.emit(_state.value.copy(isRefreshing = true))
            try {
                val season = repository.getSeason(seasonId)
                seriesId = season.seriesId
                val episodes =
                    repository.getEpisodes(
                        seriesId = season.seriesId,
                        seasonId = seasonId,
                        fields = listOf(ItemFields.OVERVIEW),
                    )
                val autoDownloadEnabled = isAutoDownloadEnabled(season.seriesId, seasonId)
                val existingScope = getExistingScope(season.seriesId)
                val downloadsSizeBytes = downloadsSizeBytes(seasonId)
                val series = repository.getShow(season.seriesId)
                val seriesTvdbId = series.tvdbId
                _state.emit(
                    _state.value.copy(
                        season = season,
                        episodes = episodes,
                        autoDownloadEnabled = autoDownloadEnabled,
                        existingScope = existingScope,
                        hasDownloads = downloadsSizeBytes > 0,
                        downloadsSizeBytes = downloadsSizeBytes,
                        seriesTvdbId = seriesTvdbId,
                        seriesTmdbId = series.tmdbId?.toIntOrNull(),
                        sonarrConfigured = pvrConfiguration.isSonarrConfigured(),
                        webUiServices =
                            pvrWebUiLinks.availableServices().filter { it != PvrService.RADARR },
                        autoDeleteWatchedEnabled =
                            appPreferences.getValue(appPreferences.autoDeleteWatched),
                        autoDeleteWatchedHours =
                            appPreferences.getValue(appPreferences.autoDeleteWatchedHours),
                        isRefreshing = false,
                    )
                )
                reconcileDownloadProgress(episodes)
                observeQueueStatus(episodes)
                loadUpcomingEpisodes(seriesTvdbId, season.indexNumber, episodes)
                loadQueuedEpisodes(season.seriesId, season.indexNumber)
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e, isRefreshing = false))
            }
        }
    }

    private suspend fun loadUpcomingEpisodes(
        seriesTvdbId: String?,
        seasonNumber: Int,
        knownEpisodes: List<JollyfinEpisode>,
    ) {
        val upcoming =
            if (!pvrConfiguration.isSonarrConfigured() || seriesTvdbId == null) {
                emptyList()
            } else {
                try {
                    seasonEpisodesRepository.getUpcomingEpisodes(
                        seriesTvdbId = seriesTvdbId,
                        seasonNumber = seasonNumber,
                        knownEpisodeNumbers = knownEpisodes.map { it.indexNumber }.toSet(),
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to load upcoming episodes for season $seasonNumber")
                    emptyList()
                }
            }
        _state.emit(_state.value.copy(upcomingEpisodes = upcoming))
    }

    private suspend fun loadQueuedEpisodes(seriesId: UUID, seasonNumber: Int) {
        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return
        val userId = repository.getUserId()
        val queued =
            pendingDownloadRequestRepository
                .getQueuedForSeries(serverId, userId, seriesId)
                .filter { it.seasonNumber == seasonNumber && it.episodeNumber != null }
                .mapNotNull { it.episodeNumber }
                .toSet()
        _state.emit(_state.value.copy(queuedEpisodeNumbers = queued))
    }

    private fun toggleEpisodeQueued(episodeNumber: Int, sonarrEpisodeId: Int) {
        val seriesId = seriesId ?: return
        val seasonNumber = _state.value.season?.indexNumber ?: return
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return@launch
            val userId = repository.getUserId()
            val alreadyQueued = _state.value.queuedEpisodeNumbers.contains(episodeNumber)
            if (alreadyQueued) {
                pendingDownloadRequestRepository.cancel(
                    serverId,
                    userId,
                    seriesId,
                    seasonNumber,
                    episodeNumber,
                )
            } else {
                pendingDownloadRequestRepository.queue(
                    serverId,
                    userId,
                    seriesId,
                    seasonNumber,
                    episodeNumber,
                    sonarrEpisodeId,
                )
            }
            loadQueuedEpisodes(seriesId, seasonNumber)
        }
    }

    /**
     * Resolves the Sonarr episode id to act on - already known for upcoming-episode rows
     * ([knownEpisodeId]), otherwise resolved from [SeasonState.seriesTvdbId].
     */
    private suspend fun resolveTargetEpisodeId(episodeNumber: Int, knownEpisodeId: Int?): Int? {
        if (knownEpisodeId != null) return knownEpisodeId
        val seriesTvdbId = _state.value.seriesTvdbId ?: return null
        val seasonNumber = _state.value.season?.indexNumber ?: return null
        return sonarrSearchRepository.resolveEpisodeId(seriesTvdbId, seasonNumber, episodeNumber)
    }

    private fun searchEpisodeAutomatic(episodeNumber: Int, knownEpisodeId: Int?) {
        viewModelScope.launch {
            val episodeId = resolveTargetEpisodeId(episodeNumber, knownEpisodeId)
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

    private fun openReleasePicker(episodeNumber: Int, knownEpisodeId: Int?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(releasePicker = ReleasePickerState())
            val episodeId = resolveTargetEpisodeId(episodeNumber, knownEpisodeId)
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

    private fun observeQueueStatus(episodes: List<JollyfinEpisode>) {
        val episodeIds = episodes.map { it.id }.toSet()
        queueStatusJob?.cancel()
        queueStatusJob = viewModelScope.launch {
            queueStatusRepository.getQueueStatusFlow().collect { queueStatusByItemId ->
                _state.value =
                    _state.value.copy(
                        queueStatus = queueStatusByItemId.filterKeys { it in episodeIds }
                    )
            }
        }
    }

    private fun reconcileDownloadProgress(episodes: List<JollyfinEpisode>) {
        val trackedEpisodes = episodes.filter { it.isDownloading() }
        val desiredIds = trackedEpisodes.map { it.id }.toSet()

        (progressJobs.keys - desiredIds).forEach { id ->
            progressJobs.remove(id)?.cancel()
            downloadIdsByEpisode.remove(id)
            _state.value = _state.value.copy(downloadProgress = _state.value.downloadProgress - id)
        }

        trackedEpisodes.forEach { episode ->
            if (progressJobs.containsKey(episode.id)) return@forEach
            val downloadId =
                episode.sources.firstOrNull { it.type == JollyfinSourceType.LOCAL }?.downloadId
                    ?: return@forEach
            downloadIdsByEpisode[episode.id] = downloadId
            progressJobs[episode.id] = viewModelScope.launch {
                downloader.getProgressFlow(downloadId).collect { progress ->
                    _state.value =
                        _state.value.copy(
                            downloadProgress =
                                _state.value.downloadProgress + (episode.id to progress)
                        )
                }
            }
        }
    }

    private suspend fun isAutoDownloadEnabled(seriesId: UUID, seasonId: UUID): Boolean {
        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return false
        val userId = repository.getUserId()
        return autoDownloadRuleRepository.isSeasonRuleEnabled(serverId, userId, seriesId, seasonId)
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

    suspend fun getSeasons(): List<JollyfinSeason> {
        val seriesId = seriesId ?: return emptyList()
        return repository.getSeasons(seriesId)
    }

    /**
     * Count and total primary-source size of [targetSeasonId]'s episodes that would actually be
     * downloaded right now - excludes episodes already downloaded locally, and (if [onlyUnwatched])
     * already-watched ones, matching the scope the "only unwatched" toggle would apply to the real
     * download.
     */
    suspend fun getUndownloadedEpisodeSize(
        targetSeasonId: UUID,
        onlyUnwatched: Boolean,
    ): DownloadSizeEstimate {
        val seriesId = seriesId ?: return DownloadSizeEstimate()
        val episodes =
            try {
                repository.getEpisodes(
                    seriesId = seriesId,
                    seasonId = targetSeasonId,
                    fields = listOf(ItemFields.MEDIA_SOURCES),
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to fetch episode sizes for season $targetSeasonId")
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

    suspend fun getOtherDevices(): List<RemoteDeviceInfo> =
        remoteConfigRepository.listOtherDevices()

    private fun downloadWithScope(
        selection: DownloadSelection,
        alsoFollowNew: Boolean,
        onlyUnwatched: Boolean,
        targetDeviceId: String? = null,
    ) {
        val seriesId = seriesId ?: return
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
                    seriesId = seriesId,
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
                        seriesId = seriesId,
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
                    seriesId = seriesId,
                    seasonIds = if (alsoFollowNew) selection.seasonIds else emptySet(),
                    alsoFutureSeasons = selection.alsoFutureSeasons,
                    onlyNewEpisodes = false,
                    onlyUnwatched = onlyUnwatched,
                )
            }
            loadSeason(seasonId)
        }
    }

    private suspend fun downloadsSizeBytes(seasonId: UUID): Long =
        withContext(Dispatchers.IO) {
            database.getEpisodesBySeasonId(seasonId).sumOf { episode ->
                database
                    .getSources(episode.id)
                    .filter { it.type == JollyfinSourceType.LOCAL }
                    .sumOf { File(it.path).length() }
            }
        }

    private fun deleteSeasonDownloads(alsoRemoveRules: Boolean) {
        val seriesId = seriesId ?: return
        viewModelScope.launch {
            val userId = repository.getUserId()
            val episodes =
                withContext(Dispatchers.IO) {
                    database.getEpisodesBySeasonId(seasonId).map {
                        it.toJollyfinEpisode(database, userId)
                    }
                }
            clearDownloads(episodes, database, downloader)

            if (alsoRemoveRules) {
                appPreferences.getValue(appPreferences.currentServer)?.let { serverId ->
                    autoDownloadRuleRepository.deleteSeasonRule(
                        serverId,
                        userId,
                        seriesId,
                        seasonId,
                    )
                }
            }

            loadSeason(seasonId)
        }
    }

    fun onAction(action: SeasonAction) {
        when (action) {
            is SeasonAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.DownloadWithScope ->
                downloadWithScope(
                    action.selection,
                    action.alsoFollowNew,
                    action.onlyUnwatched,
                    action.targetDeviceId,
                )
            is SeasonAction.DeleteSeasonDownloads -> deleteSeasonDownloads(action.alsoRemoveRules)
            is SeasonAction.SearchEpisodeAutomatic ->
                searchEpisodeAutomatic(action.episodeNumber, action.knownEpisodeId)
            is SeasonAction.OpenReleasePicker ->
                openReleasePicker(action.episodeNumber, action.knownEpisodeId)
            is SeasonAction.GrabRelease -> grabRelease(action.release)
            is SeasonAction.DismissReleasePicker ->
                _state.value = _state.value.copy(releasePicker = null)
            is SeasonAction.ToggleEpisodeQueued ->
                toggleEpisodeQueued(action.episodeNumber, action.sonarrEpisodeId)
            else -> Unit
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressJobs.values.forEach { it.cancel() }
        queueStatusJob?.cancel()
    }
}
