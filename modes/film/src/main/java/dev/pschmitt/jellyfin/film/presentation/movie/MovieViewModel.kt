package dev.pschmitt.jellyfin.film.presentation.movie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.api.pvr.PvrRelease
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.presentation.delete.DeleteItemEvent
import dev.pschmitt.jellyfin.core.presentation.search.ReleasePickerState
import dev.pschmitt.jellyfin.core.presentation.search.SearchEvent
import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.film.domain.VideoMetadataParser
import dev.pschmitt.jellyfin.film.presentation.downloads.ManualImportController
import dev.pschmitt.jellyfin.film.presentation.downloads.PendingImportRef
import dev.pschmitt.jellyfin.models.JollyfinItemPerson
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.QueueItemStatus
import dev.pschmitt.jellyfin.models.SeerrMediaType
import dev.pschmitt.jellyfin.pvr.PvrConfiguration
import dev.pschmitt.jellyfin.pvr.PvrWebUiLinks
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import dev.pschmitt.jellyfin.repository.JellyfinRepositoryOfflineImpl
import dev.pschmitt.jellyfin.repository.QueueStatusRepository
import dev.pschmitt.jellyfin.repository.RadarrSearchRepository
import dev.pschmitt.jellyfin.repository.SeerrRepository
import dev.pschmitt.jellyfin.repository.getDownloadedMovieOrNull
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.utils.Downloader
import dev.pschmitt.jellyfin.utils.clearDownloads
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind
import timber.log.Timber

@HiltViewModel
class MovieViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val offlineRepository: JellyfinRepositoryOfflineImpl,
    private val videoMetadataParser: VideoMetadataParser,
    private val appPreferences: AppPreferences,
    private val radarrSearchRepository: RadarrSearchRepository,
    private val queueStatusRepository: QueueStatusRepository,
    private val pvrConfiguration: PvrConfiguration,
    private val pvrWebUiLinks: PvrWebUiLinks,
    private val seerrRepository: SeerrRepository,
    private val database: ServerDatabaseDao,
    private val downloader: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(MovieState())
    val state = _state.asStateFlow()

    private val searchEventsChannel = Channel<SearchEvent>()
    val searchEvents = searchEventsChannel.receiveAsFlow()

    private val deleteEventsChannel = Channel<DeleteItemEvent>()
    val deleteEvents = deleteEventsChannel.receiveAsFlow()

    private var queueStatusJob: Job? = null

    val manualImport = ManualImportController(queueStatusRepository, viewModelScope)

    lateinit var movieId: UUID

    /**
     * Opens the manage-import sheet for this movie's own PVR queue entry (or entries, if it has
     * duplicates - see [MovieState.queueEntries]), if there's a warning/failure to resolve.
     */
    fun openManualImportForCurrentItem() {
        val status = _state.value.queueStatus ?: return
        if (status.status != QueueItemStatus.WARNING && status.status != QueueItemStatus.FAILED)
            return
        val title = _state.value.movie?.name ?: return
        val refs =
            _state.value.queueEntries.mapNotNull { entry ->
                entry.status.downloadId?.let {
                    PendingImportRef(entry.status.source, it, entry.queueItemId)
                }
            }
        if (refs.isEmpty()) return
        manualImport.open(title, refs)
    }

    fun loadMovie(movieId: UUID) {
        this.movieId = movieId
        observeQueueStatus(movieId)
        viewModelScope.launch {
            _state.emit(_state.value.copy(isRefreshing = true))
            // Downloaded: local copy first, server refresh after (see
            // EpisodeViewModel.loadEpisode).
            val localMovie = offlineRepository.getDownloadedMovieOrNull(movieId)
            if (localMovie != null) {
                _state.emit(
                    _state.value.copy(
                        movie = localMovie,
                        videoMetadata = videoMetadataParser.parse(localMovie.sources.first()),
                        actors = getActors(localMovie),
                        director = getDirector(localMovie),
                        writers = getWriters(localMovie),
                        dateFormat = appPreferences.getValue(appPreferences.dateFormat),
                        radarrConfigured = pvrConfiguration.isRadarrConfigured(),
                        seerrConfigured = pvrConfiguration.isSeerrConfigured(),
                        webUiServices =
                            pvrWebUiLinks.availableServices().filter { it != PvrService.SONARR },
                        isRefreshing = false,
                    )
                )
            }
            try {
                val movie = repository.getMovie(movieId)
                val videoMetadata = videoMetadataParser.parse(movie.sources.first())
                val actors = getActors(movie)
                val director = getDirector(movie)
                val writers = getWriters(movie)
                val dateFormat = appPreferences.getValue(appPreferences.dateFormat)
                val canDelete = repository.canDeleteMedia()
                _state.emit(
                    _state.value.copy(
                        movie = movie,
                        videoMetadata = videoMetadata,
                        actors = actors,
                        director = director,
                        writers = writers,
                        dateFormat = dateFormat,
                        radarrConfigured = pvrConfiguration.isRadarrConfigured(),
                        seerrConfigured = pvrConfiguration.isSeerrConfigured(),
                        webUiServices =
                            pvrWebUiLinks.availableServices().filter { it != PvrService.SONARR },
                        canDelete = canDelete,
                        isRefreshing = false,
                    )
                )
            } catch (e: Exception) {
                if (localMovie != null) {
                    Timber.w(e, "Server refresh failed, staying on the downloaded copy")
                    _state.emit(_state.value.copy(isRefreshing = false))
                } else {
                    _state.emit(_state.value.copy(error = e, isRefreshing = false))
                }
            }
        }
    }

    private fun observeQueueStatus(movieId: UUID) {
        if (queueStatusJob != null) return
        queueStatusJob = viewModelScope.launch {
            queueStatusRepository.getQueueStatusFlow(movieId).collect { status ->
                _state.value = _state.value.copy(queueStatus = status)
            }
        }
        viewModelScope.launch {
            queueStatusRepository.getQueueEntriesFlow(movieId).collect { entries ->
                _state.value = _state.value.copy(queueEntries = entries)
            }
        }
    }

    private suspend fun resolveTargetMovieId(): Int? {
        val tmdbId = _state.value.movie?.tmdbId ?: return null
        return radarrSearchRepository.resolveMovieId(tmdbId)
    }

    private fun searchMovieAutomatic() {
        viewModelScope.launch {
            val movieId = resolveTargetMovieId()
            val event =
                if (movieId == null) {
                    SearchEvent.Failed("Could not find this movie in Radarr")
                } else {
                    radarrSearchRepository
                        .searchMovie(movieId)
                        .fold({ SearchEvent.SearchTriggered }, { SearchEvent.Failed(it.message) })
                }
            searchEventsChannel.send(event)
        }
    }

    private fun openReleasePicker() {
        viewModelScope.launch {
            _state.value = _state.value.copy(releasePicker = ReleasePickerState())
            val movieId = resolveTargetMovieId()
            if (movieId == null) {
                _state.value = _state.value.copy(releasePicker = null)
                searchEventsChannel.send(SearchEvent.Failed("Could not find this movie in Radarr"))
                return@launch
            }
            val result = radarrSearchRepository.getReleases(movieId)
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

    private fun deleteItem(cascadeToPvr: Boolean) {
        viewModelScope.launch {
            try {
                repository.deleteItem(movieId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deleteEventsChannel.send(DeleteItemEvent.Failed(e.message))
                return@launch
            }
            // The Jellyfin delete already succeeded at this point - a failed PVR/Seerr cascade is
            // logged, not surfaced as a failure, so the user isn't told the whole action failed
            // when only this best-effort cleanup step didn't.
            if (cascadeToPvr) {
                val tmdbId = _state.value.movie?.tmdbId
                tmdbId?.let { id ->
                    radarrSearchRepository.deleteMovieByTmdbId(id).onFailure {
                        Timber.w(it, "Failed to cascade movie delete to Radarr")
                    }
                }
                tmdbId?.toIntOrNull()?.let { tmdbIdInt ->
                    seerrRepository
                        .getDetails(tmdbIdInt, SeerrMediaType.MOVIE)
                        .onSuccess { detail ->
                            detail.cancellableRequestIds.forEach { requestId ->
                                seerrRepository.cancelRequest(requestId).onFailure {
                                    Timber.w(it, "Failed to cancel Seerr request $requestId")
                                }
                            }
                        }
                        .onFailure {
                            Timber.w(it, "Failed to look up Seerr request for movie delete cascade")
                        }
                }
            }
            // The item no longer exists on the server - no point leaving an orphaned local
            // download (file + DB rows) pointing at it behind.
            _state.value.movie?.let { clearDownloads(listOf(it), database, downloader) }
            deleteEventsChannel.send(DeleteItemEvent.Deleted)
        }
    }

    private fun grabRelease(release: PvrRelease) {
        viewModelScope.launch {
            val result = radarrSearchRepository.grabRelease(release)
            _state.value = _state.value.copy(releasePicker = null)
            searchEventsChannel.send(
                result.fold({ SearchEvent.ReleaseGrabbed }, { SearchEvent.Failed(it.message) })
            )
        }
    }

    private suspend fun getActors(item: JollyfinMovie): List<JollyfinItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    private suspend fun getDirector(item: JollyfinMovie): JollyfinItemPerson? {
        return withContext(Dispatchers.Default) {
            item.people.firstOrNull { it.type == PersonKind.DIRECTOR }
        }
    }

    private suspend fun getWriters(item: JollyfinMovie): List<JollyfinItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.WRITER }
        }
    }

    fun onAction(action: MovieAction) {
        when (action) {
            is MovieAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.DeleteItem -> deleteItem(action.cascadeToPvr)
            is MovieAction.SearchMovieAutomatic -> searchMovieAutomatic()
            is MovieAction.OpenReleasePicker -> openReleasePicker()
            is MovieAction.GrabRelease -> grabRelease(action.release)
            is MovieAction.DismissReleasePicker ->
                _state.value = _state.value.copy(releasePicker = null)
            else -> Unit
        }
    }
}
