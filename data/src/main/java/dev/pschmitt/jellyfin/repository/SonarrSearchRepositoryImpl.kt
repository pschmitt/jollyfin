package dev.pschmitt.jellyfin.repository

import dev.pschmitt.jellyfin.api.pvr.PvrClientConfig
import dev.pschmitt.jellyfin.api.pvr.PvrRelease
import dev.pschmitt.jellyfin.api.pvr.SonarrApi
import dev.pschmitt.jellyfin.models.AutomaticSearchOutcome
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * [resolveConfig] resolves the active profile's effective Sonarr config (enabled/baseUrl/apiKey) -
 * passed in as a plain lambda (rather than depending on `core`'s `PvrConfigResolver` directly)
 * because that type lives in `core`, which depends on `data`, not the other way around. Same
 * pattern as [SeasonEpisodesRepositoryImpl]/[CalendarRepositoryImpl]/`QueueStatusRepositoryImpl`.
 *
 * [scheduleCompletionCheck] enqueues `dev.pschmitt.jellyfin.work.AutomaticSearchWorker` - also a
 * lambda, for the same reason: WorkManager/notifications are `core`-layer concerns `data` can't
 * depend on directly.
 *
 * Constructed via `dev.pschmitt.jellyfin.di.SonarrSearchModule` (a Hilt `@Provides`) rather than an
 * `@Inject` constructor, since `data` has no Hilt plugin.
 */
class SonarrSearchRepositoryImpl(
    private val appPreferences: AppPreferences,
    private val resolveConfig: () -> PvrClientConfig?,
    private val scheduleCompletionCheck: (episodeId: Int, commandId: Int) -> Unit,
) : SonarrSearchRepository {
    // Keyed by Sonarr episode id. This repository is a Hilt singleton (see SonarrSearchModule),
    // so the cache lives for the app process, for as long as AppPreferences.pvrReleaseCacheMinutes
    // (Settings > Integrations) says - long enough to avoid re-hitting a slow indexer chain when
    // the user briefly re-opens the release picker for the same episode.
    private val releaseCache = ConcurrentHashMap<Int, CachedReleases>()

    private data class CachedReleases(val releases: List<PvrRelease>, val fetchedAtMs: Long)

    override suspend fun searchSeriesByTmdbId(tmdbId: Int): Result<Unit> {
        val result = runAction { api ->
            val seriesId =
                api.getSeries().firstOrNull { it.tmdbId == tmdbId }?.id
                    ?: throw IllegalArgumentException("Could not find this show in Sonarr")
            api.searchSeries(seriesId)
        }
        result.onSuccess {
            // A series search can change any episode's available releases.
            releaseCache.clear()
        }
        return result.map {}
    }

    override suspend fun resolveEpisodeId(
        seriesTvdbId: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ): Int? {
        val api = api() ?: return null
        val seriesId =
            api.getSeries().firstOrNull { it.tvdbId.toString() == seriesTvdbId }?.id ?: return null
        return api.getEpisodes(seriesId)
            .firstOrNull { it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber }
            ?.id
    }

    override suspend fun searchEpisode(episodeId: Int): Result<Unit> {
        val result = runAction { it.searchEpisode(episodeId) }
        result.onSuccess { commandId ->
            // An automatic search may grab something new - drop any cached manual-search results
            // for this episode so a follow-up manual search reflects that.
            releaseCache.remove(episodeId)
            scheduleCompletionCheck(episodeId, commandId)
        }
        return result.map {}
    }

    override suspend fun awaitAutomaticSearchResult(
        episodeId: Int,
        commandId: Int,
    ): Result<AutomaticSearchOutcome> = runAction { api ->
        var status = api.getCommandStatus(commandId)
        val deadline = System.currentTimeMillis() + PVR_COMMAND_AWAIT_TIMEOUT_MS
        while (
            status.status !in PVR_TERMINAL_COMMAND_STATUSES && System.currentTimeMillis() < deadline
        ) {
            delay(PVR_COMMAND_POLL_INTERVAL_MS)
            status = api.getCommandStatus(commandId)
        }
        val episode = api.getEpisodeById(episodeId)
        val episodeCode = "S%02dE%02d".format(episode.seasonNumber, episode.episodeNumber)
        AutomaticSearchOutcome(
            succeeded = status.status == "completed",
            title = episode.series?.title?.let { "$it $episodeCode" } ?: episodeCode,
        )
    }

    override suspend fun getReleases(episodeId: Int): Result<List<PvrRelease>> {
        val cacheTtlMs = appPreferences.getValue(appPreferences.pvrReleaseCacheMinutes) * 60_000L
        releaseCache[episodeId]?.let { cached ->
            if (System.currentTimeMillis() - cached.fetchedAtMs < cacheTtlMs) {
                return Result.success(cached.releases)
            }
        }
        val timeoutMs = appPreferences.getValue(appPreferences.pvrSearchTimeout)
        return runAction { it.getReleases(episodeId, readTimeoutMs = timeoutMs) }
            .onSuccess { releases ->
                releaseCache[episodeId] = CachedReleases(releases, System.currentTimeMillis())
            }
    }

    override suspend fun grabRelease(release: PvrRelease): Result<Unit> =
        runAction {
                it.grabRelease(release.guid, release.indexerId)
            }
            .onSuccess {
                // Don't know which episode this release belonged to from here - clear everything
                // rather than risk showing a stale list that still offers an already-grabbed
                // release.
                releaseCache.clear()
            }

    override suspend fun deleteSeriesByTvdbId(tvdbId: String): Result<Unit> = runAction { api ->
        val seriesId =
            api.getSeries().firstOrNull { it.tvdbId.toString() == tvdbId }?.id
                ?: throw IllegalArgumentException("Could not find this show in Sonarr")
        api.deleteSeries(seriesId, deleteFiles = true, addImportListExclusion = true)
    }

    override suspend fun unmonitorEpisode(
        seriesTvdbId: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ): Result<Unit> = runAction { api ->
        val seriesId =
            api.getSeries().firstOrNull { it.tvdbId.toString() == seriesTvdbId }?.id
                ?: throw IllegalArgumentException("Could not find this show in Sonarr")
        val episodeId =
            api.getEpisodes(seriesId)
                .firstOrNull {
                    it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber
                }
                ?.id ?: throw IllegalArgumentException("Could not find this episode in Sonarr")
        api.setEpisodesMonitored(listOf(episodeId), monitored = false)
    }

    private suspend fun <T> runAction(block: suspend (SonarrApi) -> T): Result<T> {
        val api = api() ?: return Result.failure(IllegalStateException("Sonarr is not configured"))
        return try {
            Result.success(block(api))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Sonarr search action failed")
            Result.failure(mapPvrSearchError("Sonarr", e))
        }
    }

    private fun api(): SonarrApi? {
        val config = resolveConfig() ?: return null
        if (!config.enabled) return null
        val baseUrl = config.baseUrl
        val apiKey = config.apiKey
        if (baseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return SonarrApi(baseUrl, apiKey)
    }
}
