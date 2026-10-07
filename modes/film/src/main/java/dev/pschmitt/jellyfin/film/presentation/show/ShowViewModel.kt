package dev.pschmitt.jellyfin.film.presentation.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.presentation.delete.DeleteItemEvent
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSelection
import dev.pschmitt.jellyfin.core.presentation.downloader.DownloadSizeEstimate
import dev.pschmitt.jellyfin.core.presentation.search.SearchEvent
import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.di.ApplicationScope
import dev.pschmitt.jellyfin.models.AutoDownloadRuleDto
import dev.pschmitt.jellyfin.models.CalendarEntry
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinItemPerson
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.JollyfinShow
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import dev.pschmitt.jellyfin.models.RemoteDeviceInfo
import dev.pschmitt.jellyfin.models.SeerrMediaType
import dev.pschmitt.jellyfin.models.toJollyfinEpisode
import dev.pschmitt.jellyfin.pvr.PvrConfiguration
import dev.pschmitt.jellyfin.pvr.PvrWebUiLinks
import dev.pschmitt.jellyfin.repository.AutoDownloadRuleRepository
import dev.pschmitt.jellyfin.repository.CalendarRepository
import dev.pschmitt.jellyfin.repository.ExistingAutoDownloadScope
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import dev.pschmitt.jellyfin.repository.PendingDownloadRequestRepository
import dev.pschmitt.jellyfin.repository.RemoteConfigRepository
import dev.pschmitt.jellyfin.repository.SeasonEpisodesRepository
import dev.pschmitt.jellyfin.repository.SeerrRepository
import dev.pschmitt.jellyfin.repository.SonarrSearchRepository
import dev.pschmitt.jellyfin.repository.toExistingScope
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.utils.AutoDownloadRuleEvaluator
import dev.pschmitt.jellyfin.utils.Downloader
import dev.pschmitt.jellyfin.utils.clearDownloads
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
class ShowViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val database: ServerDatabaseDao,
    private val downloader: Downloader,
    private val autoDownloadRuleRepository: AutoDownloadRuleRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
    private val appPreferences: AppPreferences,
    private val calendarRepository: CalendarRepository,
    private val seasonEpisodesRepository: SeasonEpisodesRepository,
    private val seerrRepository: SeerrRepository,
    private val pendingDownloadRequestRepository: PendingDownloadRequestRepository,
    private val sonarrSearchRepository: SonarrSearchRepository,
    private val pvrConfiguration: PvrConfiguration,
    private val pvrWebUiLinks: PvrWebUiLinks,
    @ApplicationScope private val externalScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(ShowState())
    val state = _state.asStateFlow()

    private val deleteEventsChannel = Channel<DeleteItemEvent>()
    val deleteEvents = deleteEventsChannel.receiveAsFlow()

    private val searchEventsChannel = Channel<SearchEvent>()
    val searchEvents = searchEventsChannel.receiveAsFlow()

    private val evaluator = AutoDownloadRuleEvaluator()

    lateinit var showId: UUID

    fun loadShow(showId: UUID) {
        this.showId = showId
        viewModelScope.launch {
            _state.emit(_state.value.copy(isRefreshing = true))
            try {
                val show = repository.getShow(showId)
                val nextUp = getNextUp(showId)
                val nextAiring = if (nextUp == null) getNextAiring(showId) else null
                val seasons = repository.getSeasons(showId)
                val actors = getActors(show)
                val director = getDirector(show)
                val writers = getWriters(show)
                val autoDownloadEnabled = isAutoDownloadEnabled(showId)
                val existingScope = getExistingScope(showId)
                val downloadsSizeBytes = downloadsSizeBytes(showId)
                val episodeCount = totalEpisodeCount(showId, seasons)
                val canDelete = repository.canDeleteMedia()
                _state.emit(
                    _state.value.copy(
                        show = show,
                        nextUp = nextUp,
                        nextAiring = nextAiring,
                        seasons = seasons,
                        actors = actors,
                        director = director,
                        writers = writers,
                        autoDownloadEnabled = autoDownloadEnabled,
                        existingScope = existingScope,
                        hasDownloads = downloadsSizeBytes > 0,
                        downloadsSizeBytes = downloadsSizeBytes,
                        episodeCount = episodeCount,
                        seriesTvdbId = show.tvdbId,
                        seriesTmdbId = show.tmdbId?.toIntOrNull(),
                        sonarrConfigured = pvrConfiguration.isSonarrConfigured(),
                        seerrConfigured = pvrConfiguration.isSeerrConfigured(),
                        webUiServices =
                            pvrWebUiLinks.availableServices().filter { it != PvrService.RADARR },
                        canDelete = canDelete,
                        isRefreshing = false,
                    )
                )
                // Fired after the main state emit rather than blocking it - the real show/season
                // data is already on screen by the time this (possibly slow, Sonarr-dependent)
                // round trip resolves, same pattern as SeasonViewModel.loadUpcomingEpisodes.
                loadMissingSeasons(show.tvdbId, show.tmdbId?.toIntOrNull(), seasons)
                loadQueuedSeasons(showId)
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e, isRefreshing = false))
            }
        }
    }

    private suspend fun loadMissingSeasons(
        seriesTvdbId: String?,
        seriesTmdbId: Int?,
        knownSeasons: List<JollyfinSeason>,
    ) {
        val missing =
            if (!pvrConfiguration.isSonarrConfigured() || seriesTvdbId == null) {
                emptyList()
            } else {
                try {
                    seasonEpisodesRepository.getMissingSeasons(
                        seriesTvdbId = seriesTvdbId,
                        knownSeasonNumbers = knownSeasons.map { it.indexNumber }.toSet(),
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to load missing seasons for show $showId")
                    emptyList()
                }
            }
        _state.emit(_state.value.copy(missingSeasons = missing))
        if (missing.isEmpty() || seriesTmdbId == null) return
        if (!pvrConfiguration.isSeerrConfigured()) return
        // Separate round trip after missingSeasons is already on screen - poster art is a nice-
        // to-have, not something worth delaying the placeholder cards themselves for.
        seerrRepository
            .getSeasonPosterUrls(seriesTmdbId, missing.map { it.seasonNumber })
            .onSuccess { posterUrls ->
                val withPosters = missing.map { it.copy(posterUrl = posterUrls[it.seasonNumber]) }
                _state.emit(_state.value.copy(missingSeasons = withPosters))
            }
            .onFailure { e ->
                Timber.w(e, "Failed to load missing-season posters for show $showId")
            }
    }

    private suspend fun loadQueuedSeasons(showId: UUID) {
        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return
        val userId = repository.getUserId()
        val queued =
            pendingDownloadRequestRepository
                .getQueuedForSeries(serverId, userId, showId)
                .filter { it.episodeNumber == null }
                .map { it.seasonNumber }
                .toSet()
        _state.emit(_state.value.copy(queuedSeasonNumbers = queued))
    }

    private fun toggleSeasonQueued(seasonNumber: Int) {
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return@launch
            val userId = repository.getUserId()
            val alreadyQueued = _state.value.queuedSeasonNumbers.contains(seasonNumber)
            if (alreadyQueued) {
                pendingDownloadRequestRepository.cancel(
                    serverId,
                    userId,
                    showId,
                    seasonNumber,
                    episodeNumber = null,
                )
            } else {
                pendingDownloadRequestRepository.queue(
                    serverId,
                    userId,
                    showId,
                    seasonNumber,
                    episodeNumber = null,
                    sonarrEpisodeId = null,
                )
            }
            loadQueuedSeasons(showId)
        }
    }

    private suspend fun isAutoDownloadEnabled(showId: UUID): Boolean {
        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return false
        val userId = repository.getUserId()
        return autoDownloadRuleRepository.isShowRuleEnabled(serverId, userId, showId)
    }

    private suspend fun getExistingScope(showId: UUID): ExistingAutoDownloadScope {
        val serverId =
            appPreferences.getValue(appPreferences.currentServer)
                ?: return ExistingAutoDownloadScope()
        val userId = repository.getUserId()
        return autoDownloadRuleRepository
            .getRulesForSeries(serverId, userId, showId)
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
        // Deliberately not viewModelScope: enqueuing a full show/season can take a while (one
        // network round trip per episode), and viewModelScope is cancelled the instant this
        // screen is popped off the back stack (e.g. the user taps another tab to check on
        // progress) - which silently truncated the batch partway through. externalScope survives
        // that navigation so every episode gets enqueued regardless of what the user does next.
        externalScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return@launch
            val userId = repository.getUserId()

            if (targetDeviceId != null) {
                remoteConfigRepository.pushDownloadWithScope(
                    targetDeviceId = targetDeviceId,
                    serverId = serverId,
                    userId = userId,
                    seriesId = showId,
                    seasonIds = selection.seasonIds,
                    alsoFollowNew = alsoFollowNew,
                    alsoFutureSeasons = selection.alsoFutureSeasons,
                    onlyUnwatched = onlyUnwatched,
                )
                return@launch
            }

            // Only the explicitly-picked seasons are downloaded immediately - "auto-download
            // future seasons" is a no-op today by definition, it only matters once persisted.
            for (seasonId in selection.seasonIds) {
                val transientRule =
                    AutoDownloadRuleDto(
                        serverId = serverId,
                        userId = userId,
                        seriesId = showId,
                        seasonId = seasonId,
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
                    seriesId = showId,
                    seasonIds = if (alsoFollowNew) selection.seasonIds else emptySet(),
                    alsoFutureSeasons = selection.alsoFutureSeasons,
                    onlyNewEpisodes = false,
                    onlyUnwatched = onlyUnwatched,
                )
            }
            loadShow(showId)
        }
    }

    /**
     * Count and total primary-source size of [seasonId]'s episodes that would actually be
     * downloaded right now - excludes episodes already downloaded locally, and (if [onlyUnwatched])
     * already-watched ones, matching the scope the "only unwatched" toggle would apply to the real
     * download.
     */
    suspend fun getUndownloadedEpisodeSize(
        seasonId: UUID,
        onlyUnwatched: Boolean,
    ): DownloadSizeEstimate {
        val episodes =
            try {
                repository.getEpisodes(
                    seriesId = showId,
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

    /**
     * Downloaded size only - the local Room cache only ever knows about episodes that were
     * downloaded (or otherwise separately cached), so it's the right source for "how much of this
     * show is on disk" but not for [totalEpisodeCount].
     */
    private suspend fun downloadsSizeBytes(showId: UUID): Long =
        withContext(Dispatchers.IO) {
            database.getEpisodesByShowId(showId).sumOf { episode ->
                database
                    .getSources(episode.id)
                    .filter { it.type == JollyfinSourceType.LOCAL }
                    .sumOf { File(it.path).length() }
            }
        }

    /**
     * Real per-season episode counts from the server - the local Room cache only has episodes that
     * were downloaded or individually visited, not the show's true total.
     */
    private suspend fun totalEpisodeCount(showId: UUID, seasons: List<JollyfinSeason>): Int =
        withContext(Dispatchers.IO) {
            seasons.sumOf { season -> repository.getEpisodes(showId, season.id).size }
        }

    private fun deleteShowDownloads(alsoRemoveRules: Boolean) {
        viewModelScope.launch {
            val userId = repository.getUserId()
            val episodes =
                withContext(Dispatchers.IO) {
                    database.getEpisodesByShowId(showId).map {
                        it.toJollyfinEpisode(database, userId)
                    }
                }
            clearDownloads(episodes, database, downloader)

            if (alsoRemoveRules) {
                appPreferences.getValue(appPreferences.currentServer)?.let { serverId ->
                    autoDownloadRuleRepository.deleteRulesForShow(serverId, userId, showId)
                }
            }

            loadShow(showId)
        }
    }

    private fun deleteItem(cascadeToPvr: Boolean) {
        viewModelScope.launch {
            try {
                repository.deleteItem(showId)
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
                _state.value.seriesTvdbId?.let { tvdbId ->
                    sonarrSearchRepository.deleteSeriesByTvdbId(tvdbId).onFailure {
                        Timber.w(it, "Failed to cascade show delete to Sonarr")
                    }
                }
                _state.value.seriesTmdbId?.let { tmdbId ->
                    seerrRepository
                        .getDetails(tmdbId, SeerrMediaType.TV)
                        .onSuccess { detail ->
                            detail.cancellableRequestIds.forEach { requestId ->
                                seerrRepository.cancelRequest(requestId).onFailure {
                                    Timber.w(it, "Failed to cancel Seerr request $requestId")
                                }
                            }
                        }
                        .onFailure {
                            Timber.w(it, "Failed to look up Seerr request for show delete cascade")
                        }
                }
            }
            // The show no longer exists on the server - no point leaving orphaned local
            // downloads (files + DB rows) pointing at its episodes behind.
            val userId = repository.getUserId()
            val episodes =
                withContext(Dispatchers.IO) {
                    database.getEpisodesByShowId(showId).map {
                        it.toJollyfinEpisode(database, userId)
                    }
                }
            clearDownloads(episodes, database, downloader)
            deleteEventsChannel.send(DeleteItemEvent.Deleted)
        }
    }

    private fun searchSeriesAutomatic() {
        viewModelScope.launch {
            val tmdbId = _state.value.seriesTmdbId
            val event =
                if (tmdbId == null) {
                    SearchEvent.Failed("Could not find this show in Sonarr")
                } else {
                    sonarrSearchRepository
                        .searchSeriesByTmdbId(tmdbId)
                        .fold({ SearchEvent.SearchTriggered }, { SearchEvent.Failed(it.message) })
                }
            searchEventsChannel.send(event)
        }
    }

    private suspend fun getNextUp(showId: UUID): JollyfinEpisode? {
        val nextUpItems = repository.getNextUp(showId)
        return nextUpItems.getOrNull(0)
    }

    private suspend fun getNextAiring(showId: UUID): CalendarEntry? {
        return try {
            calendarRepository
                .getUpcoming()
                .entries
                .filter { it.itemId == showId }
                .minByOrNull { it.date }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getActors(item: JollyfinShow): List<JollyfinItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    private suspend fun getDirector(item: JollyfinShow): JollyfinItemPerson? {
        return withContext(Dispatchers.Default) {
            item.people.firstOrNull { it.type == PersonKind.DIRECTOR }
        }
    }

    private suspend fun getWriters(item: JollyfinShow): List<JollyfinItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.WRITER }
        }
    }

    fun onAction(action: ShowAction) {
        when (action) {
            is ShowAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.DownloadWithScope ->
                downloadWithScope(
                    action.selection,
                    action.alsoFollowNew,
                    action.onlyUnwatched,
                    action.targetDeviceId,
                )
            is ShowAction.DeleteShowDownloads -> deleteShowDownloads(action.alsoRemoveRules)
            is ShowAction.DeleteItem -> deleteItem(action.cascadeToPvr)
            is ShowAction.SearchSeriesAutomatic -> searchSeriesAutomatic()
            is ShowAction.ToggleSeasonQueued -> toggleSeasonQueued(action.seasonNumber)
            else -> Unit
        }
    }
}
