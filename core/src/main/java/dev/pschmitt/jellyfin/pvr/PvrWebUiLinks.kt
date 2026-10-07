package dev.pschmitt.jellyfin.pvr

import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.api.pvr.RadarrApi
import dev.pschmitt.jellyfin.api.pvr.SonarrApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

/**
 * What a web UI link should point at. Every field is optional - with nothing set (or nothing the
 * service can resolve) the link falls back to the service's start page.
 *
 * @property titleSlug Sonarr's/Radarr's own web UI path segment for a series/movie, when already
 *   known (e.g. from a queue entry) - skips the API lookup entirely.
 * @property isMovie Only consulted for Seerr, whose detail pages are `/movie/<tmdbId>` vs
 *   `/tv/<tmdbId>` - Sonarr only knows shows and Radarr only movies.
 */
data class PvrWebUiTarget(
    val tvdbId: Int? = null,
    val tmdbId: Int? = null,
    val titleSlug: String? = null,
    val isMovie: Boolean = false,
)

/**
 * Builds links into Sonarr's/Radarr's/Seerr's own web UIs (JF-94), shared by the in-app web UI
 * screen, the "Open in Sonarr/Radarr/Seerr" menu entries and the local control API's `/pvr/webui`
 * endpoint.
 *
 * Only an enabled service with a base URL counts as available - unlike [PvrConfiguration], no API
 * key is required, since the web UI has its own login. The key is only used, when present, to
 * resolve a series'/movie's page via the service's API.
 */
@Singleton
class PvrWebUiLinks @Inject constructor(private val resolver: PvrConfigResolver) {
    fun baseUrl(service: PvrService): String? {
        val config = resolver.resolveConfig(service) ?: return null
        if (!config.enabled) return null
        val baseUrl = config.baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return baseUrl.takeIf { it.toHttpUrlOrNull() != null }
    }

    fun isAvailable(service: PvrService): Boolean = baseUrl(service) != null

    fun availableServices(): List<PvrService> = PvrService.entries.filter(::isAvailable)

    /**
     * Resolves [target] to an absolute URL in [service]'s web UI, or `null` if [service] isn't
     * available. A series/movie the service doesn't know yet links to its "add new" search for it
     * instead, so the link is still useful for adding it.
     */
    suspend fun resolve(service: PvrService, target: PvrWebUiTarget = PvrWebUiTarget()): String? {
        val baseUrl = baseUrl(service) ?: return null
        val slug = target.titleSlug?.takeIf { it.isNotBlank() }
        return when (service) {
            PvrService.SONARR -> {
                val titleSlug =
                    slug
                        ?: lookup(service) { api ->
                            SonarrApi(baseUrl, api)
                                .getSeries()
                                .firstOrNull {
                                    (target.tvdbId != null && it.tvdbId == target.tvdbId) ||
                                        (target.tmdbId != null && it.tmdbId == target.tmdbId)
                                }
                                ?.titleSlug
                        }
                when {
                    titleSlug != null -> buildUrl(baseUrl, "series/$titleSlug")
                    target.tvdbId != null ->
                        buildUrl(baseUrl, "add/new", "term" to "tvdb:${target.tvdbId}")
                    target.tmdbId != null ->
                        buildUrl(baseUrl, "add/new", "term" to "tmdb:${target.tmdbId}")
                    else -> buildUrl(baseUrl)
                }
            }
            PvrService.RADARR -> {
                val titleSlug =
                    slug
                        ?: target.tmdbId?.let { tmdbId ->
                            lookup(service) { api ->
                                RadarrApi(baseUrl, api)
                                    .getMovie()
                                    .firstOrNull { it.tmdbId == tmdbId }
                                    ?.titleSlug
                            }
                        }
                when {
                    titleSlug != null -> buildUrl(baseUrl, "movie/$titleSlug")
                    target.tmdbId != null ->
                        buildUrl(baseUrl, "add/new", "term" to "tmdb:${target.tmdbId}")
                    else -> buildUrl(baseUrl)
                }
            }
            PvrService.SEERR ->
                when (val tmdbId = target.tmdbId) {
                    null -> buildUrl(baseUrl)
                    else -> buildUrl(baseUrl, "${if (target.isMovie) "movie" else "tv"}/$tmdbId")
                }
        }
    }

    /**
     * Runs an API lookup with [service]'s API key - `null` without a key or when the lookup fails,
     * so an unreachable API just degrades the link to the "add new"/start page instead of failing.
     */
    private suspend fun lookup(service: PvrService, block: suspend (apiKey: String) -> String?) =
        resolver
            .resolveConfig(service)
            ?.apiKey
            ?.takeIf { it.isNotBlank() }
            ?.let { apiKey ->
                try {
                    block(apiKey)?.takeIf { it.isNotBlank() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Web UI link lookup against %s failed", service)
                    null
                }
            }

    private fun buildUrl(
        baseUrl: String,
        path: String? = null,
        vararg query: Pair<String, String>,
    ): String {
        val builder = baseUrl.toHttpUrlOrNull()?.newBuilder() ?: return baseUrl
        path?.let { builder.addPathSegments(it) }
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build().toString()
    }
}
