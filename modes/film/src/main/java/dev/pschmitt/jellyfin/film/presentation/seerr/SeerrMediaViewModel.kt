package dev.pschmitt.jellyfin.film.presentation.seerr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.api.pvr.PvrRelease
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.presentation.search.ReleasePickerState
import dev.pschmitt.jellyfin.film.presentation.downloads.ManualImportController
import dev.pschmitt.jellyfin.film.presentation.downloads.PendingImportRef
import dev.pschmitt.jellyfin.models.PvrSource
import dev.pschmitt.jellyfin.models.QueueItemStatus
import dev.pschmitt.jellyfin.models.SeerrMediaType
import dev.pschmitt.jellyfin.pvr.PvrConfiguration
import dev.pschmitt.jellyfin.pvr.PvrWebUiLinks
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import dev.pschmitt.jellyfin.repository.QueueStatusRepository
import dev.pschmitt.jellyfin.repository.RadarrSearchRepository
import dev.pschmitt.jellyfin.repository.SeerrRepository
import dev.pschmitt.jellyfin.repository.SonarrSearchRepository
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

/** One-shot feedback for the request/cancel actions, shown as a toast. */
sealed interface SeerrMediaEvent {
    data class Requested(val title: String) : SeerrMediaEvent

    data class RequestCancelled(val title: String) : SeerrMediaEvent

    data class SearchTriggered(val source: PvrSource) : SeerrMediaEvent

    data class SearchFailed(val source: PvrSource, val message: String?) : SeerrMediaEvent

    data object ReleaseGrabbed : SeerrMediaEvent

    data class ActionFailed(val message: String?) : SeerrMediaEvent
}

@HiltViewModel
class SeerrMediaViewModel
@Inject
constructor(
    private val seerrRepository: SeerrRepository,
    private val sonarrSearchRepository: SonarrSearchRepository,
    private val radarrSearchRepository: RadarrSearchRepository,
    private val queueStatusRepository: QueueStatusRepository,
    private val pvrConfiguration: PvrConfiguration,
    private val jellyfinRepository: JellyfinRepository,
    private val pvrWebUiLinks: PvrWebUiLinks,
) : ViewModel() {
    private val _state = MutableStateFlow(SeerrMediaState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<SeerrMediaEvent>()
    val events = eventsChannel.receiveAsFlow()

    private var tmdbId: Int = 0
    private lateinit var mediaType: SeerrMediaType
    private var seasonNumber: Int? = null
    private var episodeNumber: Int? = null
    private var sonarrEpisodeId: Int? = null
    private var airDate: LocalDate? = null
    private var airTime: LocalTime? = null
    private var releasePickerSource: PvrSource? = null
    private var queueStatusJob: Job? = null
    private var queueEntriesJob: Job? = null

    val manualImport = ManualImportController(queueStatusRepository, viewModelScope)

    /** Opens the manage-import sheet for this item's own PVR queue entry, if it has one. */
    fun openManualImportForCurrentItem() {
        val status = _state.value.queueStatus ?: return
        if (status.status != QueueItemStatus.WARNING && status.status != QueueItemStatus.FAILED)
            return
        val title = _state.value.detail?.title ?: return
        val refs =
            _state.value.queueEntries.mapNotNull { entry ->
                entry.status.downloadId?.let {
                    PendingImportRef(entry.status.source, it, entry.queueItemId)
                }
            }
        if (refs.isEmpty()) return
        manualImport.open(title, refs)
    }

    fun loadDetail(
        tmdbId: Int,
        mediaType: SeerrMediaType,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        sonarrEpisodeId: Int? = null,
        airDate: LocalDate? = null,
        airTime: LocalTime? = null,
    ) {
        this.tmdbId = tmdbId
        this.mediaType = mediaType
        this.seasonNumber = seasonNumber
        this.episodeNumber = episodeNumber
        this.sonarrEpisodeId = sonarrEpisodeId
        this.airDate = airDate
        this.airTime = airTime
        observeQueueStatus(mediaType, tmdbId, sonarrEpisodeId)
        viewModelScope.launch {
            _state.value =
                _state.value.copy(
                    isLoading = true,
                    error = null,
                    knownAirDate = airDate,
                    knownAirTime = airTime,
                    pvrSearchConfigured =
                        when (mediaType) {
                            SeerrMediaType.MOVIE -> pvrConfiguration.isRadarrConfigured()
                            SeerrMediaType.TV -> pvrConfiguration.isSonarrConfigured()
                        },
                    webUiServices =
                        pvrWebUiLinks.availableServices().filter {
                            it !=
                                when (mediaType) {
                                    SeerrMediaType.MOVIE -> PvrService.SONARR
                                    SeerrMediaType.TV -> PvrService.RADARR
                                }
                        },
                    manualPvrSearchAvailable =
                        when (mediaType) {
                            SeerrMediaType.MOVIE -> pvrConfiguration.isRadarrConfigured()
                            SeerrMediaType.TV ->
                                sonarrEpisodeId != null && pvrConfiguration.isSonarrConfigured()
                        },
                )
            seerrRepository
                .getDetails(tmdbId, mediaType, seasonNumber, episodeNumber)
                .fold(
                    onSuccess = { detail ->
                        val (showId, seasonId, episodeId) = findJellyfinDestination(detail)
                        _state.value =
                            _state.value.copy(
                                isLoading = false,
                                detail = detail,
                                jellyfinShowId = showId,
                                jellyfinSeasonId = seasonId,
                                jellyfinEpisodeId = episodeId,
                            )
                    },
                    onFailure = { e ->
                        _state.value =
                            _state.value.copy(
                                isLoading = false,
                                error = e as? Exception ?: Exception(e),
                            )
                    },
                )
        }
    }

    private suspend fun findJellyfinDestination(
        detail: dev.pschmitt.jellyfin.models.SeerrMediaDetail
    ): Triple<java.util.UUID?, java.util.UUID?, java.util.UUID?> {
        if (detail.mediaType != SeerrMediaType.TV) return Triple(null, null, null)
        return runCatching {
                val show =
                    jellyfinRepository
                        .getItems(includeTypes = listOf(BaseItemKind.SERIES), recursive = true)
                        .filterIsInstance<dev.pschmitt.jellyfin.models.JollyfinShow>()
                        .firstOrNull { it.tmdbId == detail.tmdbId.toString() }
                        ?: return@runCatching Triple(null, null, null)
                val seasonNumber = detail.episode?.seasonNumber ?: detail.season?.seasonNumber
                val seasonId = seasonNumber?.let {
                    jellyfinRepository
                        .getSeasons(show.id)
                        .firstOrNull { season -> season.indexNumber == it }
                        ?.id
                }
                val episode = detail.episode
                val episodeId =
                    if (episode != null && seasonId != null) {
                        jellyfinRepository
                            .getEpisodes(seriesId = show.id, seasonId = seasonId)
                            .firstOrNull { it.indexNumber == episode.episodeNumber }
                            ?.id
                    } else {
                        null
                    }
                Triple(show.id, seasonId, episodeId)
            }
            .getOrDefault(Triple(null, null, null))
    }

    private fun observeQueueStatus(
        mediaType: SeerrMediaType,
        tmdbId: Int,
        sonarrEpisodeId: Int?,
    ) {
        queueStatusJob?.cancel()
        queueStatusJob = viewModelScope.launch {
            when (mediaType) {
                SeerrMediaType.MOVIE ->
                    queueStatusRepository.getRadarrQueueStatusFlow().collect { statuses ->
                        _state.value = _state.value.copy(queueStatus = statuses[tmdbId])
                    }
                SeerrMediaType.TV ->
                    queueStatusRepository.getSonarrQueueStatusFlow().collect { statuses ->
                        _state.value =
                            _state.value.copy(queueStatus = sonarrEpisodeId?.let { statuses[it] })
                    }
            }
        }
        // Only the id that's actually meaningful at this scope - passing the show-level tmdbId
        // for a Sonarr/episode lookup would match every episode of the same show, not just this
        // one, since matchSonarr sets PvrQueueEntry.tmdbId from the series, not the episode.
        queueEntriesJob?.cancel()
        queueEntriesJob = viewModelScope.launch {
            val entriesFlow =
                when (mediaType) {
                    SeerrMediaType.MOVIE ->
                        queueStatusRepository.getQueueEntriesFlow(PvrSource.RADARR, tmdbId, null)
                    SeerrMediaType.TV ->
                        queueStatusRepository.getQueueEntriesFlow(
                            PvrSource.SONARR,
                            null,
                            sonarrEpisodeId,
                        )
                }
            entriesFlow.collect { entries ->
                _state.value = _state.value.copy(queueEntries = entries)
            }
        }
    }

    fun onAction(action: SeerrMediaAction) {
        when (action) {
            is SeerrMediaAction.OnRequest -> request()
            is SeerrMediaAction.OnCancelRequest -> cancelRequests()
            is SeerrMediaAction.OnAutomaticSearchInPvr -> searchInPvr()
            is SeerrMediaAction.OnOpenReleasePicker -> openReleasePicker()
            is SeerrMediaAction.GrabRelease -> grabRelease(action.release)
            is SeerrMediaAction.DismissReleasePicker ->
                _state.value = _state.value.copy(releasePicker = null)
            is SeerrMediaAction.OnRetryClick ->
                loadDetail(
                    tmdbId,
                    mediaType,
                    seasonNumber,
                    episodeNumber,
                    sonarrEpisodeId,
                    airDate,
                    airTime,
                )
            else -> Unit
        }
    }

    private fun searchInPvr() {
        val detail = _state.value.detail ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSubmitting = true)
            val result =
                when (detail.mediaType) {
                    SeerrMediaType.MOVIE ->
                        radarrSearchRepository.searchMovieByTmdbId(detail.tmdbId)
                    SeerrMediaType.TV ->
                        sonarrEpisodeId?.let { sonarrSearchRepository.searchEpisode(it) }
                            ?: sonarrSearchRepository.searchSeriesByTmdbId(detail.tmdbId)
                }
            val source =
                if (detail.mediaType == SeerrMediaType.TV) PvrSource.SONARR else PvrSource.RADARR
            eventsChannel.send(
                result.fold(
                    { SeerrMediaEvent.SearchTriggered(source) },
                    { SeerrMediaEvent.SearchFailed(source, it.message) },
                )
            )
            _state.value = _state.value.copy(isSubmitting = false)
        }
    }

    private fun openReleasePicker() {
        val detail = _state.value.detail ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(releasePicker = ReleasePickerState())
            val source =
                if (detail.mediaType == SeerrMediaType.TV) PvrSource.SONARR else PvrSource.RADARR
            val result: Result<List<PvrRelease>> =
                when (detail.mediaType) {
                    SeerrMediaType.MOVIE -> {
                        val movieId =
                            radarrSearchRepository.resolveMovieId(detail.tmdbId.toString())
                        if (movieId == null) {
                            Result.failure(
                                IllegalStateException("Could not find this movie in Radarr")
                            )
                        } else {
                            radarrSearchRepository.getReleases(movieId)
                        }
                    }
                    SeerrMediaType.TV -> {
                        val episodeId = sonarrEpisodeId
                        if (episodeId == null) {
                            Result.failure(
                                IllegalStateException("Could not find this episode in Sonarr")
                            )
                        } else {
                            sonarrSearchRepository.getReleases(episodeId)
                        }
                    }
                }
            releasePickerSource = source
            _state.value =
                _state.value.copy(
                    releasePicker =
                        result.getOrNull()?.let {
                            ReleasePickerState(isLoading = false, releases = it)
                        }
                )
            result.onFailure {
                eventsChannel.send(SeerrMediaEvent.SearchFailed(source, it.message))
            }
        }
    }

    private fun grabRelease(release: PvrRelease) {
        viewModelScope.launch {
            val result =
                when (releasePickerSource) {
                    PvrSource.RADARR -> radarrSearchRepository.grabRelease(release)
                    else -> sonarrSearchRepository.grabRelease(release)
                }
            _state.value = _state.value.copy(releasePicker = null)
            eventsChannel.send(
                result.fold(
                    { SeerrMediaEvent.ReleaseGrabbed },
                    {
                        SeerrMediaEvent.SearchFailed(
                            releasePickerSource ?: PvrSource.SONARR,
                            it.message,
                        )
                    },
                )
            )
        }
    }

    private fun request() {
        val detail = _state.value.detail ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSubmitting = true)
            seerrRepository
                .request(detail.tmdbId, detail.mediaType, seasonNumber = seasonNumber)
                .fold(
                    onSuccess = {
                        eventsChannel.send(SeerrMediaEvent.Requested(detail.title))
                        // Reload rather than patching the state locally - the new request's id is
                        // needed for a subsequent unrequest, and only the server has it.
                        loadDetail(
                            detail.tmdbId,
                            detail.mediaType,
                            seasonNumber,
                            episodeNumber,
                            sonarrEpisodeId,
                            airDate,
                            airTime,
                        )
                    },
                    onFailure = { e ->
                        eventsChannel.send(SeerrMediaEvent.ActionFailed(e.message))
                    },
                )
            _state.value = _state.value.copy(isSubmitting = false)
        }
    }

    private fun cancelRequests() {
        val detail = _state.value.detail ?: return
        if (detail.cancellableRequestIds.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSubmitting = true)
            // A media can accumulate several open requests (e.g. re-requested seasons);
            // "unrequest" means clearing them all. Stop at the first failure and surface it.
            var failure: Throwable? = null
            for (requestId in detail.cancellableRequestIds) {
                val result = seerrRepository.cancelRequest(requestId)
                if (result.isFailure) {
                    failure = result.exceptionOrNull()
                    break
                }
            }
            if (failure == null) {
                eventsChannel.send(SeerrMediaEvent.RequestCancelled(detail.title))
            } else {
                eventsChannel.send(SeerrMediaEvent.ActionFailed(failure.message))
            }
            loadDetail(
                detail.tmdbId,
                detail.mediaType,
                seasonNumber,
                episodeNumber,
                sonarrEpisodeId,
                airDate,
                airTime,
            )
            _state.value = _state.value.copy(isSubmitting = false)
        }
    }
}
