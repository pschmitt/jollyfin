package dev.pschmitt.jellyfin.localcontrol

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pschmitt.jellyfin.api.pvr.PvrHttpClient
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.api.pvr.RadarrApi
import dev.pschmitt.jellyfin.api.pvr.SeerrApi
import dev.pschmitt.jellyfin.api.pvr.SonarrApi
import dev.pschmitt.jellyfin.models.AutoDownloadRuleDto
import dev.pschmitt.jellyfin.models.JollyfinBoxSet
import dev.pschmitt.jellyfin.models.JollyfinCollection
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinFolder
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.JollyfinShow
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import dev.pschmitt.jellyfin.pvr.PvrConfigResolver
import dev.pschmitt.jellyfin.pvr.PvrConfiguration
import dev.pschmitt.jellyfin.repository.AutoDownloadRuleRepository
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import dev.pschmitt.jellyfin.repository.RemoteConfigRepository
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * Dispatches an already-authenticated request (see [LocalControlServer], which verifies the bearer
 * token before ever calling this) to the actual app state it controls - real [AppPreferences]
 * download settings, the real current download list, a real [Downloader]-triggered download (by id
 * or by name/season), read-only browsing of the Jellyfin library and Sonarr/Radarr/Seerr, or a raw
 * debug request against any of those four services using the app's own stored credentials.
 */
@Singleton
class LocalControlRouter
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    private val pvrConfigResolver: PvrConfigResolver,
    private val jellyfinRepository: JellyfinRepository,
    private val downloader: Downloader,
    private val pvrConfiguration: PvrConfiguration,
    private val autoDownloadRuleRepository: AutoDownloadRuleRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
    private val appVersionInfo: AppVersionInfo,
) {
    suspend fun handle(
        method: String,
        path: String,
        queryParams: Map<String, String>,
        body: JsonElement?,
    ): LocalControlResponse =
        withContext(Dispatchers.IO) {
            try {
                when {
                    method == "GET" && path == "/info" -> getInfo()
                    method == "GET" && path == "/settings/downloads" -> getDownloadSettings()
                    method == "PATCH" && path == "/settings/downloads" ->
                        patchDownloadSettings(body)
                    method == "GET" && path == "/downloads" -> listDownloads(queryParams)
                    method == "POST" && path == "/downloads/trigger" -> triggerDownload(body)
                    method == "POST" && path == "/downloads/trigger-by-name" ->
                        triggerDownloadByName(body)
                    method == "POST" && path == "/downloads/cancel" -> cancelDownload(body)
                    method == "POST" && path == "/downloads/remove" -> removeDownload(body)
                    method == "GET" && path == "/jellyfin/libraries" -> getJellyfinLibraries()
                    method == "GET" && path == "/jellyfin/items" -> getJellyfinItems(queryParams)
                    method == "GET" && path == "/sonarr/series" -> getSonarrSeries()
                    method == "GET" && path == "/radarr/movies" -> getRadarrMovies()
                    method == "GET" && path == "/seerr/requests" -> getSeerrRequests(queryParams)
                    method == "GET" && path.startsWith("/seerr/discover/") ->
                        getSeerrDiscover(path.removePrefix("/seerr/discover/"), queryParams)
                    method == "GET" && path == "/seerr/search" -> getSeerrSearch(queryParams)
                    method == "GET" && path == "/autodownload/rules" -> listAutoDownloadRules()
                    method == "POST" && path == "/autodownload/rules" -> addAutoDownloadRule(body)
                    method == "POST" && path == "/autodownload/rules/remove" ->
                        removeAutoDownloadRule(body)
                    method == "POST" && path == "/app/stop" -> stopApp()
                    method == "POST" && path == "/debug/proxy" -> debugProxy(body)
                    else ->
                        LocalControlResponse(
                            LocalControlStatus.NOT_FOUND,
                            errorBody("Unknown endpoint: $method $path"),
                        )
                }
            } catch (e: Exception) {
                Timber.e(e, "Local control request failed: %s %s", method, path)
                LocalControlResponse(
                    LocalControlStatus.INTERNAL_ERROR,
                    errorBody(e.message ?: "Internal error"),
                )
            }
        }

    /**
     * `jollyfin-cli version`'s app-side half - the app's own build metadata, sourced from
     * [AppVersionInfo] since [LocalControlRouter] (in `core`) can't reference `app/phone`'s own
     * generated `BuildConfig` directly.
     */
    private fun getInfo(): LocalControlResponse =
        LocalControlResponse(
            LocalControlStatus.OK,
            buildJsonObject {
                put("versionName", appVersionInfo.versionName)
                put("versionCode", appVersionInfo.versionCode)
                put("gitRevision", appVersionInfo.gitRevision)
            },
        )

    /**
     * `jollyfin-cli stop` - there's no OS-level way for a plain, unprivileged Termux process to
     * force-stop another app (`android.permission.FORCE_STOP_PACKAGES` is signature/privileged-only
     * - `pm grant` refuses it even as root, the same wall FINDROID-45's original ContentProvider
     *   transport hit for `ACCESS_CONTENT_PROVIDERS_EXTERNALLY`). Asking the app to exit itself
     *   sidesteps that entirely - no special permission needed, since it's just a normal process
     *   calling [Runtime.exit] on itself. The actual exit is delayed slightly on a background
     *   thread so this response has time to reach the client over the socket before the process
     *   dies - killing it synchronously here would race NanoHTTPD's own write and the client would
     *   see nothing but a dropped connection instead of a real 200.
     */
    private fun stopApp(): LocalControlResponse {
        Thread {
                Thread.sleep(300)
                Runtime.getRuntime().exit(0)
            }
            .start()
        return LocalControlResponse(
            LocalControlStatus.OK,
            buildJsonObject { put("stopping", true) },
        )
    }

    private fun getDownloadSettings(): LocalControlResponse =
        LocalControlResponse(LocalControlStatus.OK, DownloadSettingsBridge.toJson(appPreferences))

    private fun patchDownloadSettings(body: JsonElement?): LocalControlResponse {
        val patch = body?.jsonObject
        if (patch == null) {
            return LocalControlResponse(
                LocalControlStatus.BAD_REQUEST,
                errorBody("Expected a JSON object body"),
            )
        }
        DownloadSettingsBridge.applyPatch(appPreferences, patch)
        return LocalControlResponse(
            LocalControlStatus.OK,
            DownloadSettingsBridge.toJson(appPreferences),
        )
    }

    /**
     * Flattens every downloaded (LOCAL) source - movies plus, unlike
     * [JellyfinRepository.getDownloads] itself, every downloaded episode too
     * ([JollyfinShow.sources] is always empty - a show has no media source of its own, only its
     * episodes do - so `getDownloads()` alone silently drops every TV download; expanding each
     * show's seasons/episodes via the `offline = true` DB-backed reads mirrors what
     * [DownloadsViewModel][dev.pschmitt.jellyfin.film.presentation.downloads.DownloadsViewModel]'s
     * own Downloads screen already does). One row per source, with a `status`
     * ("downloading"/"completed", from the `.download` suffix [JollyfinItem.isDownloading] already
     * uses) and, for an in-progress one, a live progress snapshot. `status` query param filters to
     * just one or the other.
     */
    private suspend fun listDownloads(queryParams: Map<String, String>): LocalControlResponse {
        val statusFilter = queryParams["status"]

        val flatItems = mutableListOf<JollyfinItem>()
        jellyfinRepository.getDownloads().forEach { item ->
            if (item is JollyfinShow) {
                jellyfinRepository.getSeasons(item.id, offline = true).forEach { season ->
                    flatItems += jellyfinRepository.getEpisodes(item.id, season.id, offline = true)
                }
            } else {
                flatItems += item
            }
        }

        val body = buildJsonArray {
            flatItems.forEach { item ->
                item.sources
                    .filter { it.type == JollyfinSourceType.LOCAL }
                    .forEach { source ->
                        val status =
                            if (source.path.endsWith(".download")) "downloading" else "completed"
                        if (statusFilter != null && statusFilter != status) return@forEach
                        add(
                            buildJsonObject {
                                put("itemId", item.id.toString())
                                put("name", item.name)
                                if (item is JollyfinEpisode) {
                                    put("seriesName", item.seriesName)
                                    put("seasonNumber", item.parentIndexNumber)
                                    put("episodeNumber", item.indexNumber)
                                }
                                put("sourceId", source.id)
                                put("sourceName", source.name)
                                put("sizeBytes", source.size)
                                put("status", status)
                                source.downloadId?.let { downloadId ->
                                    put("downloadId", downloadId.toString())
                                    if (status == "downloading") {
                                        val progress =
                                            downloader.getProgressFlow(downloadId).first()
                                        put("percent", progress.percent)
                                        put("downloadedBytes", progress.downloadedBytes)
                                        put("totalBytes", progress.totalBytes)
                                        put("speedBytesPerSecond", progress.speedBytesPerSecond)
                                        put("etaSeconds", progress.etaSeconds)
                                    }
                                }
                            }
                        )
                    }
            }
        }
        return LocalControlResponse(LocalControlStatus.OK, body)
    }

    private suspend fun cancelDownload(body: JsonElement?): LocalControlResponse {
        val downloadId =
            body?.jsonObject?.get("downloadId")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: return LocalControlResponse(
                    LocalControlStatus.BAD_REQUEST,
                    errorBody("Missing/invalid \"downloadId\""),
                )
        downloader.cancelDownload(downloadId)
        return LocalControlResponse(
            LocalControlStatus.OK,
            buildJsonObject { put("cancelled", true) },
        )
    }

    /**
     * Deletes one or more already-downloaded items (body: `{"itemId": "..."}` or `{"itemIds":
     * ["...", ...]}`) via [Downloader.deleteItems] - the same cascade the app's own Downloads
     * screen delete action uses. Not for an in-progress download - use [cancelDownload] for that
     * (deleting a still-downloading item isn't itself an error, but it only removes the DB
     * bookkeeping and leaves the transfer running orphaned; cancel first).
     */
    private suspend fun removeDownload(body: JsonElement?): LocalControlResponse {
        val obj = body?.jsonObject
        val singleId = obj?.get("itemId")?.jsonPrimitive?.contentOrNull
        val manyIds =
            obj?.get("itemIds")
                ?.let { el -> el as? JsonArray }
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        val rawIds = manyIds ?: singleId?.let { listOf(it) }
        if (rawIds.isNullOrEmpty()) {
            return LocalControlResponse(
                LocalControlStatus.BAD_REQUEST,
                errorBody("Missing \"itemId\"/\"itemIds\""),
            )
        }
        val uuids = rawIds.map {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: return LocalControlResponse(
                    LocalControlStatus.BAD_REQUEST,
                    errorBody("Invalid item id: $it"),
                )
        }
        downloader.deleteItems(uuids)
        return LocalControlResponse(
            LocalControlStatus.OK,
            buildJsonObject { put("removed", uuids.size) },
        )
    }

    private suspend fun triggerDownload(body: JsonElement?): LocalControlResponse {
        val obj = body?.jsonObject
        val itemId = obj?.get("itemId")?.jsonPrimitive?.contentOrNull
        if (itemId == null) {
            return LocalControlResponse(
                LocalControlStatus.BAD_REQUEST,
                errorBody("Missing \"itemId\""),
            )
        }
        val uuid =
            runCatching { UUID.fromString(itemId) }.getOrNull()
                ?: return LocalControlResponse(
                    LocalControlStatus.BAD_REQUEST,
                    errorBody("Invalid itemId"),
                )
        val item =
            jellyfinRepository.getItem(uuid)
                ?: return LocalControlResponse(
                    LocalControlStatus.NOT_FOUND,
                    errorBody("Item not found"),
                )
        val sourceId =
            obj["sourceId"]?.jsonPrimitive?.contentOrNull ?: item.sources.firstOrNull()?.id
        if (sourceId == null) {
            return LocalControlResponse(
                LocalControlStatus.CONFLICT,
                errorBody("Item has no media sources"),
            )
        }
        val (downloadId, error) =
            downloader.downloadItem(item, sourceId, downloader.resolvePreferredStorageIndex())
        return if (error == null) {
            LocalControlResponse(
                LocalControlStatus.OK,
                buildJsonObject { put("downloadId", downloadId.toString()) },
            )
        } else {
            LocalControlResponse(
                LocalControlStatus.CONFLICT,
                errorBody(error.asString(context.resources)),
            )
        }
    }

    /**
     * Resolves a movie/show by name (case-insensitive exact match, else the sole search result,
     * else an ambiguous-match error listing every candidate) and triggers its download - for a
     * show, resolves season/episode the same way and triggers every matching episode. A bare show
     * name with neither `season` nor `all: true` is rejected rather than silently downloading every
     * season, since that could be a lot of unplanned storage/bandwidth.
     */
    private suspend fun triggerDownloadByName(body: JsonElement?): LocalControlResponse {
        val obj = body?.jsonObject
        val query = obj?.get("query")?.jsonPrimitive?.contentOrNull
        if (query.isNullOrBlank()) {
            return LocalControlResponse(
                LocalControlStatus.BAD_REQUEST,
                errorBody("Missing \"query\""),
            )
        }
        val seasonQuery = obj["season"]?.jsonPrimitive?.contentOrNull
        val episodeQuery = obj["episode"]?.jsonPrimitive?.contentOrNull
        val all = obj["all"]?.jsonPrimitive?.booleanOrNull ?: false

        val candidates =
            jellyfinRepository.getItems(
                includeTypes =
                    listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                recursive = true,
                searchTerm = query,
            )
        if (candidates.isEmpty()) {
            return LocalControlResponse(
                LocalControlStatus.NOT_FOUND,
                errorBody("No movie/show/episode matching \"$query\""),
            )
        }
        val exactMatches = candidates.filter { it.name.equals(query, ignoreCase = true) }
        val match =
            exactMatches.singleOrNull()
                ?: candidates.singleOrNull()
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody(
                        "Ambiguous match for \"$query\": " +
                            candidates.joinToString(", ") { it.describeForAmbiguity() }
                    ),
                )

        return when (match) {
            is JollyfinMovie -> triggerSingleAsResponse(match)
            is JollyfinEpisode -> triggerSingleAsResponse(match)
            is JollyfinShow -> triggerShowDownloadByName(match, seasonQuery, episodeQuery, all)
            else ->
                LocalControlResponse(
                    LocalControlStatus.BAD_REQUEST,
                    errorBody("\"${match.name}\" matched but isn't a movie, show, or episode"),
                )
        }
    }

    /**
     * Human-readable disambiguation label for an ambiguous-match error - a bare title isn't enough
     * to tell apart e.g. two different shows' episodes both called "Pilot", so episodes (and
     * seasons) are qualified with their series name.
     */
    private fun JollyfinItem.describeForAmbiguity(): String =
        when (this) {
            is JollyfinEpisode -> "$name ($seriesName S${parentIndexNumber}E$indexNumber)"
            is JollyfinSeason -> "$name ($seriesName)"
            else -> name
        }

    private suspend fun triggerSingleAsResponse(item: JollyfinItem): LocalControlResponse {
        val (downloadId, error) = triggerSingle(item)
        return LocalControlResponse(
            if (error == null) LocalControlStatus.OK else LocalControlStatus.CONFLICT,
            buildJsonObject {
                put(
                    "triggered",
                    buildJsonArray {
                        add(triggerResultJson(item.id, item.name, downloadId, error))
                    },
                )
            },
        )
    }

    private suspend fun triggerShowDownloadByName(
        show: JollyfinShow,
        seasonQuery: String?,
        episodeQuery: String?,
        all: Boolean,
    ): LocalControlResponse {
        val seasons = jellyfinRepository.getSeasons(show.id)
        if (seasons.isEmpty()) {
            return LocalControlResponse(
                LocalControlStatus.NOT_FOUND,
                errorBody("\"${show.name}\" has no seasons"),
            )
        }

        val targetSeasons =
            if (seasonQuery != null) {
                val matches =
                    matchByNumberOrName(seasonQuery, seasons, { it.indexNumber }, { it.name })
                when {
                    matches.isEmpty() ->
                        return LocalControlResponse(
                            LocalControlStatus.NOT_FOUND,
                            errorBody("No season matching \"$seasonQuery\" in \"${show.name}\""),
                        )
                    matches.size > 1 ->
                        return LocalControlResponse(
                            LocalControlStatus.CONFLICT,
                            errorBody(
                                "Ambiguous season match for \"$seasonQuery\": " +
                                    matches.joinToString(", ") { it.name }
                            ),
                        )
                    else -> matches
                }
            } else if (all) {
                seasons
            } else {
                return LocalControlResponse(
                    LocalControlStatus.BAD_REQUEST,
                    errorBody(
                        "\"${show.name}\" has ${seasons.size} season(s) - specify one, or pass " +
                            "\"all\": true to download every season"
                    ),
                )
            }

        val episodes = mutableListOf<JollyfinEpisode>()
        for (season in targetSeasons) {
            val seasonEpisodes = jellyfinRepository.getEpisodes(show.id, season.id)
            episodes +=
                if (episodeQuery != null) {
                    matchByNumberOrName(
                        episodeQuery,
                        seasonEpisodes,
                        { it.indexNumber },
                        { it.name },
                    )
                } else {
                    seasonEpisodes
                }
        }
        if (episodes.isEmpty()) {
            return LocalControlResponse(
                LocalControlStatus.NOT_FOUND,
                errorBody("No episode matching the given season/episode in \"${show.name}\""),
            )
        }

        val triggered = buildJsonArray {
            episodes.forEach { episode ->
                val (downloadId, error) = triggerSingle(episode)
                add(triggerResultJson(episode.id, episode.name, downloadId, error))
            }
        }
        return LocalControlResponse(
            LocalControlStatus.OK,
            buildJsonObject { put("triggered", triggered) },
        )
    }

    /**
     * Triggers a single item's download exactly like [triggerDownload] (its default source),
     * returning the download id or a human-readable error instead of a [LocalControlResponse] -
     * shared by the single-movie and per-episode paths in [triggerDownloadByName].
     */
    private suspend fun triggerSingle(item: JollyfinItem): Pair<Long?, String?> {
        val sourceId = item.sources.firstOrNull()?.id ?: return null to "No media source"
        val (downloadId, error) =
            downloader.downloadItem(item, sourceId, downloader.resolvePreferredStorageIndex())
        return if (error == null) downloadId to null else null to error.asString(context.resources)
    }

    private fun triggerResultJson(
        itemId: UUID,
        name: String,
        downloadId: Long?,
        error: String?,
    ): JsonObject = buildJsonObject {
        put("itemId", itemId.toString())
        put("name", name)
        // Encoded as a string, not a raw JSON number - a Long can exceed the 53 bits of
        // precision most JSON parsers (jq/JS included) actually preserve for numbers, and a
        // silently-corrupted download id would make later cancel/list-by-id calls fail in
        // confusing ways.
        downloadId?.let { put("downloadId", it.toString()) }
        error?.let { put("error", it) }
    }

    /**
     * Matches [query] against [items] by number first ("Season 3"/"S3"/"3" -> 3, compared via
     * [number]), then falls back to a case-insensitive substring match of [name] - used for both
     * season and episode resolution in [triggerShowDownloadByName].
     */
    private fun <T> matchByNumberOrName(
        query: String,
        items: List<T>,
        number: (T) -> Int,
        name: (T) -> String,
    ): List<T> {
        val parsedNumber =
            Regex("""^\s*(?:season|episode|s|e)?\s*(\d+)\s*$""", RegexOption.IGNORE_CASE)
                .find(query)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        val exact = items.filter {
            name(it).equals(query, ignoreCase = true) ||
                (parsedNumber != null && number(it) == parsedNumber)
        }
        if (exact.isNotEmpty()) return exact
        return items.filter { name(it).contains(query, ignoreCase = true) }
    }

    private suspend fun getJellyfinLibraries(): LocalControlResponse =
        LocalControlResponse(
            LocalControlStatus.OK,
            buildJsonArray {
                jellyfinRepository.getLibraries().forEach { add(jollyfinItemToJson(it)) }
            },
        )

    private suspend fun getJellyfinItems(queryParams: Map<String, String>): LocalControlResponse {
        val parentId =
            queryParams["parentId"]?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
                    ?: return LocalControlResponse(
                        LocalControlStatus.BAD_REQUEST,
                        errorBody("Invalid parentId"),
                    )
            }
        val search = queryParams["search"]
        val items =
            jellyfinRepository.getItems(
                parentId = parentId,
                includeTypes = parseIncludeTypes(queryParams["type"]),
                recursive = search != null,
                startIndex = queryParams["startIndex"]?.toIntOrNull(),
                limit = queryParams["limit"]?.toIntOrNull(),
                searchTerm = search,
            )
        return LocalControlResponse(
            LocalControlStatus.OK,
            buildJsonArray { items.forEach { add(jollyfinItemToJson(it)) } },
        )
    }

    /**
     * Parses a comma-separated `type` query param (e.g. "movie,episode") into the matching
     * [BaseItemKind]s, or `null` (no filter) if it's absent/blank/matches nothing recognized - lets
     * `/jellyfin/items` narrow a search to just episodes, just movies, etc.
     */
    private fun parseIncludeTypes(raw: String?): List<BaseItemKind>? {
        if (raw.isNullOrBlank()) return null
        return raw.split(",")
            .mapNotNull {
                when (it.trim().lowercase()) {
                    "movie" -> BaseItemKind.MOVIE
                    "show",
                    "series" -> BaseItemKind.SERIES
                    "season" -> BaseItemKind.SEASON
                    "episode" -> BaseItemKind.EPISODE
                    "boxset" -> BaseItemKind.BOX_SET
                    "folder" -> BaseItemKind.FOLDER
                    else -> null
                }
            }
            .ifEmpty { null }
    }

    private fun jollyfinItemToJson(item: JollyfinItem): JsonObject = buildJsonObject {
        put("id", item.id.toString())
        put("name", item.name)
        put("type", jollyfinItemType(item))
        when (item) {
            is JollyfinMovie -> item.tmdbId?.let { put("tmdbId", it) }
            is JollyfinShow -> item.tmdbId?.let { put("tmdbId", it) }
            is JollyfinSeason -> {
                put("seasonNumber", item.indexNumber)
                put("seriesId", item.seriesId.toString())
                put("seriesName", item.seriesName)
            }
            is JollyfinEpisode -> {
                put("seasonNumber", item.parentIndexNumber)
                put("episodeNumber", item.indexNumber)
                put("seriesId", item.seriesId.toString())
                put("seriesName", item.seriesName)
            }
            is JollyfinCollection -> put("collectionType", item.type.type)
            else -> Unit
        }
    }

    private fun jollyfinItemType(item: JollyfinItem): String =
        when (item) {
            is JollyfinMovie -> "movie"
            is JollyfinShow -> "show"
            is JollyfinSeason -> "season"
            is JollyfinEpisode -> "episode"
            is JollyfinCollection -> "collection"
            is JollyfinBoxSet -> "boxset"
            is JollyfinFolder -> "folder"
            else -> "unknown"
        }

    private suspend fun getSonarrSeries(): LocalControlResponse {
        if (!pvrConfiguration.isSonarrConfigured()) {
            return LocalControlResponse(
                LocalControlStatus.CONFLICT,
                errorBody("Sonarr is not configured/enabled"),
            )
        }
        val config = pvrConfigResolver.resolveConfig(PvrService.SONARR)
        val baseUrl =
            config?.baseUrl
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody("Sonarr is not configured/enabled"),
                )
        val apiKey =
            config.apiKey
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody("Sonarr is not configured/enabled"),
                )
        val series = SonarrApi(baseUrl, apiKey).getSeries()
        return LocalControlResponse(LocalControlStatus.OK, json.encodeToJsonElement(series))
    }

    private suspend fun getRadarrMovies(): LocalControlResponse {
        if (!pvrConfiguration.isRadarrConfigured()) {
            return LocalControlResponse(
                LocalControlStatus.CONFLICT,
                errorBody("Radarr is not configured/enabled"),
            )
        }
        val config = pvrConfigResolver.resolveConfig(PvrService.RADARR)
        val baseUrl =
            config?.baseUrl
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody("Radarr is not configured/enabled"),
                )
        val apiKey =
            config.apiKey
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody("Radarr is not configured/enabled"),
                )
        val movies = RadarrApi(baseUrl, apiKey).getMovie()
        return LocalControlResponse(LocalControlStatus.OK, json.encodeToJsonElement(movies))
    }

    private fun resolveSeerrApi(): SeerrApi? {
        if (!pvrConfiguration.isSeerrConfigured()) return null
        val config = pvrConfigResolver.resolveConfig(PvrService.SEERR) ?: return null
        val baseUrl = config.baseUrl ?: return null
        val apiKey = config.apiKey ?: return null
        return SeerrApi(baseUrl, apiKey)
    }

    private suspend fun getSeerrRequests(queryParams: Map<String, String>): LocalControlResponse {
        val seerr =
            resolveSeerrApi()
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody("Seerr is not configured/enabled"),
                )
        val take = queryParams["take"]?.toIntOrNull() ?: 20
        val response = seerr.getRequests(take)
        return LocalControlResponse(LocalControlStatus.OK, json.encodeToJsonElement(response))
    }

    private suspend fun getSeerrDiscover(
        discoverPath: String,
        queryParams: Map<String, String>,
    ): LocalControlResponse {
        val seerr =
            resolveSeerrApi()
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody("Seerr is not configured/enabled"),
                )
        val page = queryParams["page"]?.toIntOrNull() ?: 1
        val response = seerr.discover(discoverPath, page)
        return LocalControlResponse(LocalControlStatus.OK, json.encodeToJsonElement(response))
    }

    private suspend fun getSeerrSearch(queryParams: Map<String, String>): LocalControlResponse {
        val query =
            queryParams["q"]
                ?: return LocalControlResponse(
                    LocalControlStatus.BAD_REQUEST,
                    errorBody("Missing \"q\""),
                )
        val seerr =
            resolveSeerrApi()
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody("Seerr is not configured/enabled"),
                )
        val page = queryParams["page"]?.toIntOrNull() ?: 1
        val response = seerr.search(query, page)
        return LocalControlResponse(LocalControlStatus.OK, json.encodeToJsonElement(response))
    }

    /**
     * Resolves a show by direct [seriesIdParam] (a UUID string) or, failing that, by [queryParam] -
     * case-insensitive exact match, else the sole search result, else an ambiguous-match error -
     * scoped to [BaseItemKind.SERIES] only, since an auto-download rule only ever applies to a
     * show. Mirrors [triggerDownloadByName]'s own by-name resolution template. Exactly one of the
     * two should be non-blank; if both are blank this reports the missing-parameter error.
     */
    private suspend fun resolveSeries(
        seriesIdParam: String?,
        queryParam: String?,
    ): Pair<JollyfinShow?, LocalControlResponse?> {
        if (!seriesIdParam.isNullOrBlank()) {
            val uuid =
                runCatching { UUID.fromString(seriesIdParam) }.getOrNull()
                    ?: return null to
                        LocalControlResponse(
                            LocalControlStatus.BAD_REQUEST,
                            errorBody("Invalid \"seriesId\""),
                        )
            val show =
                runCatching { jellyfinRepository.getShow(uuid) }.getOrNull()
                    ?: return null to
                        LocalControlResponse(
                            LocalControlStatus.NOT_FOUND,
                            errorBody("Show not found"),
                        )
            return show to null
        }
        if (queryParam.isNullOrBlank()) {
            return null to
                LocalControlResponse(
                    LocalControlStatus.BAD_REQUEST,
                    errorBody("Missing \"seriesId\" or \"query\""),
                )
        }
        val candidates =
            jellyfinRepository.getItems(
                includeTypes = listOf(BaseItemKind.SERIES),
                recursive = true,
                searchTerm = queryParam,
            )
        if (candidates.isEmpty()) {
            return null to
                LocalControlResponse(
                    LocalControlStatus.NOT_FOUND,
                    errorBody("No show matching \"$queryParam\""),
                )
        }
        val exactMatches = candidates.filter { it.name.equals(queryParam, ignoreCase = true) }
        val match =
            exactMatches.singleOrNull()
                ?: candidates.singleOrNull()
                ?: return null to
                    LocalControlResponse(
                        LocalControlStatus.CONFLICT,
                        errorBody(
                            "Ambiguous match for \"$queryParam\": " +
                                candidates.joinToString(", ") { it.describeForAmbiguity() }
                        ),
                    )
        val show =
            match as? JollyfinShow
                ?: return null to
                    LocalControlResponse(
                        LocalControlStatus.BAD_REQUEST,
                        errorBody("\"${match.name}\" isn't a show"),
                    )
        return show to null
    }

    /**
     * Converts a persisted rule row to the JSON shape shared by `GET /autodownload/rules` and the
     * `POST` add response - resolving `seriesId`/`seasonId` to a readable show name/season number
     * so the CLI doesn't need a second lookup round-trip. [seriesNameCache] avoids re-resolving the
     * same show for every one of its rules in a single listing.
     */
    private suspend fun autoDownloadRuleToJson(
        rule: AutoDownloadRuleDto,
        seriesNameCache: MutableMap<UUID, String>,
    ): JsonObject {
        val seriesName =
            seriesNameCache.getOrPut(rule.seriesId) {
                runCatching { jellyfinRepository.getShow(rule.seriesId).name }
                    .getOrDefault("Unknown")
            }
        val seasonNumber =
            rule.seasonId?.let { seasonId ->
                runCatching { jellyfinRepository.getSeason(seasonId).indexNumber }.getOrNull()
            }
        return buildJsonObject {
            // String-encoded, not a raw JSON number - same reasoning as downloadId elsewhere: a
            // Long can exceed the 53 bits of precision most JSON parsers (jq/JS included) actually
            // preserve.
            put("id", rule.id.toString())
            put("seriesId", rule.seriesId.toString())
            put("seriesName", seriesName)
            rule.seasonId?.let { put("seasonId", it.toString()) }
            seasonNumber?.let { put("seasonNumber", it) }
            put("enabled", rule.enabled)
            put("onlyNewEpisodes", rule.onlyNewEpisodes)
            put("onlyUnwatched", rule.onlyUnwatched)
            put("createdAt", rule.createdAt)
        }
    }

    private suspend fun listAutoDownloadRules(): LocalControlResponse {
        val serverId =
            appPreferences.getValue(appPreferences.currentServer)
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody("No current server"),
                )
        val userId = jellyfinRepository.getUserId()
        val rules = autoDownloadRuleRepository.getRules(serverId, userId)
        val seriesNameCache = mutableMapOf<UUID, String>()
        return LocalControlResponse(
            LocalControlStatus.OK,
            buildJsonArray { rules.forEach { add(autoDownloadRuleToJson(it, seriesNameCache)) } },
        )
    }

    /**
     * Resolves a show (by `seriesId` or `query`) and a season scope - a specific season/list of
     * seasons by number or name (`season`/`seasons`), `"all": true` for every existing season, or
     * neither for a future-seasons-only rule - then persists exactly that scope via
     * [AutoDownloadRuleRepository.reconcileRules], same as `AutoDownloadRulesScreen`'s own "add
     * rule" dialog. Republishes the active-rules summary afterwards (see
     * [republishActiveRulesSummary]) so other devices' "Remote devices" screens see the change
     * promptly instead of waiting on the next periodic sync - this is purely local rule management;
     * it never touches [RemoteConfigRepository.pushRuleUpdate]'s cross-device push path.
     */
    private suspend fun addAutoDownloadRule(body: JsonElement?): LocalControlResponse {
        val obj =
            body?.jsonObject
                ?: return LocalControlResponse(
                    LocalControlStatus.BAD_REQUEST,
                    errorBody("Expected a JSON object body"),
                )

        val (show, showError) =
            resolveSeries(
                obj["seriesId"]?.jsonPrimitive?.contentOrNull,
                obj["query"]?.jsonPrimitive?.contentOrNull,
            )
        if (showError != null) return showError
        val resolvedShow = show!!

        val seasons = jellyfinRepository.getSeasons(resolvedShow.id)
        val all = obj["all"]?.jsonPrimitive?.booleanOrNull ?: false
        val seasonQueries =
            (obj["seasons"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: obj["season"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) }
                ?: emptyList()

        val seasonIds: Set<UUID> =
            when {
                all -> seasons.map { it.id }.toSet()
                seasonQueries.isNotEmpty() -> {
                    val resolvedIds = mutableSetOf<UUID>()
                    for (seasonQuery in seasonQueries) {
                        val matches =
                            matchByNumberOrName(
                                seasonQuery,
                                seasons,
                                { it.indexNumber },
                                { it.name },
                            )
                        when {
                            matches.isEmpty() ->
                                return LocalControlResponse(
                                    LocalControlStatus.NOT_FOUND,
                                    errorBody(
                                        "No season matching \"$seasonQuery\" in \"${resolvedShow.name}\""
                                    ),
                                )
                            matches.size > 1 ->
                                return LocalControlResponse(
                                    LocalControlStatus.CONFLICT,
                                    errorBody(
                                        "Ambiguous season match for \"$seasonQuery\": " +
                                            matches.joinToString(", ") { it.name }
                                    ),
                                )
                            else -> resolvedIds += matches.single().id
                        }
                    }
                    resolvedIds
                }
                else -> emptySet()
            }

        // No explicit season scope at all (no "season"/"seasons"/"all") means "future seasons
        // only" - the same thing the show-level "Auto-download future seasons" toggle alone
        // represents when AutoDownloadRulesScreen's own dialog has no season selected. An
        // explicit "alsoFutureSeasons" in the body always wins over that default.
        val alsoFutureSeasons =
            obj["alsoFutureSeasons"]?.jsonPrimitive?.booleanOrNull ?: seasonIds.isEmpty()
        val onlyNewEpisodes = obj["onlyNewEpisodes"]?.jsonPrimitive?.booleanOrNull ?: false
        val onlyUnwatched = obj["onlyUnwatched"]?.jsonPrimitive?.booleanOrNull ?: false

        if (seasonIds.isEmpty() && !alsoFutureSeasons) {
            return LocalControlResponse(
                LocalControlStatus.BAD_REQUEST,
                errorBody(
                    "Nothing to do for \"${resolvedShow.name}\" - specify \"season\"/\"seasons\", " +
                        "\"all\": true, or \"alsoFutureSeasons\": true"
                ),
            )
        }

        val serverId =
            appPreferences.getValue(appPreferences.currentServer)
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody("No current server"),
                )
        val userId = jellyfinRepository.getUserId()

        val rules =
            autoDownloadRuleRepository.reconcileRules(
                serverId = serverId,
                userId = userId,
                seriesId = resolvedShow.id,
                seasonIds = seasonIds,
                alsoFutureSeasons = alsoFutureSeasons,
                onlyNewEpisodes = onlyNewEpisodes,
                onlyUnwatched = onlyUnwatched,
            )
        republishActiveRulesSummary()

        val seriesNameCache = mutableMapOf(resolvedShow.id to resolvedShow.name)
        return LocalControlResponse(
            LocalControlStatus.OK,
            buildJsonArray { rules.forEach { add(autoDownloadRuleToJson(it, seriesNameCache)) } },
        )
    }

    /**
     * Removes an auto-download rule - either a single rule by numeric `id` (from `GET
     * /autodownload/rules`), or, by show (`seriesId`/`query`), every rule for that series at once
     * (mirroring `AutoDownloadRulesScreen`'s own delete action: `show.ruleIds.forEach {
     * ruleRepository.deleteRule(it) }` - deleting "the rule for a show" clears every row for that
     * series, not just one).
     */
    private suspend fun removeAutoDownloadRule(body: JsonElement?): LocalControlResponse {
        val obj =
            body?.jsonObject
                ?: return LocalControlResponse(
                    LocalControlStatus.BAD_REQUEST,
                    errorBody("Expected a JSON object body"),
                )

        val idParam = obj["id"]?.jsonPrimitive?.contentOrNull
        if (idParam != null) {
            val id =
                idParam.toLongOrNull()
                    ?: return LocalControlResponse(
                        LocalControlStatus.BAD_REQUEST,
                        errorBody("Invalid \"id\""),
                    )
            autoDownloadRuleRepository.deleteRule(id)
            republishActiveRulesSummary()
            return LocalControlResponse(
                LocalControlStatus.OK,
                buildJsonObject { put("removed", 1) },
            )
        }

        val (show, showError) =
            resolveSeries(
                obj["seriesId"]?.jsonPrimitive?.contentOrNull,
                obj["query"]?.jsonPrimitive?.contentOrNull,
            )
        if (showError != null) return showError
        val resolvedShow = show!!

        val serverId =
            appPreferences.getValue(appPreferences.currentServer)
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody("No current server"),
                )
        val userId = jellyfinRepository.getUserId()

        val existing =
            autoDownloadRuleRepository.getRulesForSeries(serverId, userId, resolvedShow.id)
        if (existing.isEmpty()) {
            return LocalControlResponse(
                LocalControlStatus.NOT_FOUND,
                errorBody("No auto-download rule for \"${resolvedShow.name}\""),
            )
        }
        autoDownloadRuleRepository.deleteRulesForShow(serverId, userId, resolvedShow.id)
        republishActiveRulesSummary()
        return LocalControlResponse(
            LocalControlStatus.OK,
            buildJsonObject { put("removed", existing.size) },
        )
    }

    // Local rule mutations (add/remove, above) only ever touch this device's own Room database -
    // without this, the "active rules" summary this device publishes to the shared registry (what
    // other devices' Remote devices screens read) is only as fresh as RemoteConfigWorker's next
    // periodic 15-minute cycle. Mirrors AutoDownloadRulesViewModel.republishActiveRulesSummary():
    // fire-and-forget, non-fatal - a failure here (offline, server hiccup) shouldn't roll back a
    // mutation that already succeeded locally.
    private suspend fun republishActiveRulesSummary() {
        try {
            remoteConfigRepository.syncNow()
        } catch (e: Exception) {
            Timber.w(e, "Failed to republish active-rules summary after a jollyfin-cli rule change")
        }
    }

    private fun debugProxy(body: JsonElement?): LocalControlResponse {
        val obj = body?.jsonObject
        val service = obj?.get("service")?.jsonPrimitive?.contentOrNull
        val method = obj?.get("method")?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val path = obj?.get("path")?.jsonPrimitive?.contentOrNull
        if (service == null || path == null) {
            return LocalControlResponse(
                LocalControlStatus.BAD_REQUEST,
                errorBody("Expected \"service\" and \"path\""),
            )
        }
        val query =
            obj["query"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val requestBody = obj["body"]?.let { if (it is JsonNull) null else it.toString() }

        val (baseUrl, client) =
            resolveProxyClient(service)
                ?: return LocalControlResponse(
                    LocalControlStatus.CONFLICT,
                    errorBody("\"$service\" is not configured/enabled on this device"),
                )

        val url =
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .apply {
                    path.trim('/').split('/').forEach { segment ->
                        if (segment.isNotEmpty()) addPathSegment(segment)
                    }
                    query.forEach { (key, value) -> addQueryParameter(key, value) }
                }
                .build()

        val request =
            Request.Builder()
                .url(url)
                .apply {
                    when (method) {
                        "DELETE" -> delete()
                        "PUT" ->
                            put(
                                requestBody
                                    .orEmpty()
                                    .toRequestBody("application/json".toMediaType())
                            )
                        "POST" ->
                            post(
                                requestBody
                                    .orEmpty()
                                    .toRequestBody("application/json".toMediaType())
                            )
                        else -> get()
                    }
                }
                .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            return LocalControlResponse(
                response.code,
                buildJsonObject { put("body", JsonPrimitive(responseBody)) },
            )
        }
    }

    /**
     * Base URL + an authenticated [OkHttpClient] for [service], or `null` if it isn't
     * configured/enabled - mirrors the same "is this usable" gate [PvrConfiguration] already
     * enforces for the app's own PVR UI, so the debug proxy can't be used to probe a service the
     * user hasn't actually set up.
     */
    private fun resolveProxyClient(service: String): Pair<String, OkHttpClient>? =
        when (service) {
            "jellyfin" -> {
                val baseUrl = jellyfinRepository.getBaseUrl().ifBlank { null } ?: return null
                val token = jellyfinRepository.getAccessToken() ?: return null
                baseUrl to
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder().header("X-Emby-Token", token).build()
                            )
                        }
                        .build()
            }
            "sonarr" -> {
                if (!pvrConfiguration.isSonarrConfigured()) return null
                val config = pvrConfigResolver.resolveConfig(PvrService.SONARR) ?: return null
                val baseUrl = config.baseUrl ?: return null
                val apiKey = config.apiKey ?: return null
                baseUrl to PvrHttpClient.create(apiKey, PvrService.SONARR)
            }
            "radarr" -> {
                if (!pvrConfiguration.isRadarrConfigured()) return null
                val config = pvrConfigResolver.resolveConfig(PvrService.RADARR) ?: return null
                val baseUrl = config.baseUrl ?: return null
                val apiKey = config.apiKey ?: return null
                baseUrl to PvrHttpClient.create(apiKey, PvrService.RADARR)
            }
            "seerr" -> {
                if (!pvrConfiguration.isSeerrConfigured()) return null
                val config = pvrConfigResolver.resolveConfig(PvrService.SEERR) ?: return null
                val baseUrl = config.baseUrl ?: return null
                val apiKey = config.apiKey ?: return null
                baseUrl to PvrHttpClient.create(apiKey, PvrService.SEERR)
            }
            else -> null
        }

    private fun errorBody(message: String): JsonObject = buildJsonObject { put("error", message) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
