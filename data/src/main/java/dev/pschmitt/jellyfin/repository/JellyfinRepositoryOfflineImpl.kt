package dev.pschmitt.jellyfin.repository

import android.content.Context
import androidx.paging.PagingData
import dev.pschmitt.jellyfin.api.JellyfinApi
import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.models.JollyfinCollection
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinImages
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.JollyfinPerson
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.JollyfinSegment
import dev.pschmitt.jellyfin.models.JollyfinShow
import dev.pschmitt.jellyfin.models.JollyfinSource
import dev.pschmitt.jellyfin.models.SortBy
import dev.pschmitt.jellyfin.models.SortOrder
import dev.pschmitt.jellyfin.models.toJollyfinEpisode
import dev.pschmitt.jellyfin.models.toJollyfinMovie
import dev.pschmitt.jellyfin.models.toJollyfinSeason
import dev.pschmitt.jellyfin.models.toJollyfinSegment
import dev.pschmitt.jellyfin.models.toJollyfinShow
import dev.pschmitt.jellyfin.models.toJollyfinSource
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserConfiguration

class JellyfinRepositoryOfflineImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : JellyfinRepository {

    override suspend fun getPublicSystemInfo(): PublicSystemInfo {
        throw Exception("System info not available in offline mode")
    }

    override suspend fun getUserViews(): List<BaseItemDto> {
        return emptyList()
    }

    override suspend fun getMovie(itemId: UUID): JollyfinMovie =
        withContext(Dispatchers.IO) {
            database.getMovie(itemId).toJollyfinMovie(database, jellyfinApi.userId!!)
        }

    override suspend fun getShow(itemId: UUID): JollyfinShow =
        withContext(Dispatchers.IO) {
            database.getShow(itemId).toJollyfinShow(database, jellyfinApi.userId!!)
        }

    override suspend fun getSeason(itemId: UUID): JollyfinSeason =
        withContext(Dispatchers.IO) {
            database.getSeason(itemId).toJollyfinSeason(database, jellyfinApi.userId!!)
        }

    override suspend fun getEpisode(itemId: UUID): JollyfinEpisode =
        withContext(Dispatchers.IO) {
            database.getEpisode(itemId).toJollyfinEpisode(database, jellyfinApi.userId!!)
        }

    override suspend fun getLibraries(): List<JollyfinCollection> {
        return emptyList()
    }

    override suspend fun getItem(itemId: UUID): JollyfinItem? {
        return null
    }

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        startIndex: Int?,
        limit: Int?,
        searchTerm: String?,
    ): List<JollyfinItem> {
        return emptyList()
    }

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        searchTerm: String?,
    ): Flow<PagingData<JollyfinItem>> {
        // No paged local query backs this in offline mode (see getItems() above) - fail soft
        // instead of crashing the caller (JF-88).
        return flowOf(PagingData.empty())
    }

    override suspend fun getPerson(personId: UUID): JollyfinPerson {
        // Cast/crew details aren't synced for offline use - return a stub rather than crash
        // (JF-88).
        return JollyfinPerson(id = personId, name = "", overview = "", images = JollyfinImages())
    }

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<JollyfinItem> {
        return emptyList()
    }

    override suspend fun getFavoriteItems(): List<JollyfinItem> {
        // HomeViewModel.loadFavoritesItems() calls this unconditionally on every Home load, so a
        // TODO() here crash-looped the whole app in Offline Mode (JF-88). Mirrors getResumeItems()
        // below: pull movies/shows/episodes for the current server and filter on the `favorite`
        // flag that markAsFavorite()/unmarkAsFavorite() already maintain locally.
        return withContext(Dispatchers.IO) {
            val movies =
                database
                    .getMoviesByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toJollyfinMovie(database, jellyfinApi.userId!!) }
                    .filter { it.favorite }
            val shows =
                database
                    .getShowsByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toJollyfinShow(database, jellyfinApi.userId!!) }
                    .filter { it.favorite }
            val episodes =
                database
                    .getEpisodesByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toJollyfinEpisode(database, jellyfinApi.userId!!) }
                    .filter { it.favorite }
            movies + shows + episodes
        }
    }

    override suspend fun getSearchItems(query: String): List<JollyfinItem> {
        return withContext(Dispatchers.IO) {
            val movies =
                database
                    .searchMovies(appPreferences.getValue(appPreferences.currentServer)!!, query)
                    .map { it.toJollyfinMovie(database, jellyfinApi.userId!!) }
            val shows =
                database
                    .searchShows(appPreferences.getValue(appPreferences.currentServer)!!, query)
                    .map { it.toJollyfinShow(database, jellyfinApi.userId!!) }
            val episodes =
                database
                    .searchEpisodes(appPreferences.getValue(appPreferences.currentServer)!!, query)
                    .map { it.toJollyfinEpisode(database, jellyfinApi.userId!!) }
            movies + shows + episodes
        }
    }

    override suspend fun getSuggestions(): List<JollyfinItem> {
        return emptyList()
    }

    override suspend fun getResumeItems(): List<JollyfinItem> {
        return withContext(Dispatchers.IO) {
            val movies =
                database
                    .getMoviesByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toJollyfinMovie(database, jellyfinApi.userId!!) }
                    .filter { it.playbackPositionTicks > 0 }
            val episodes =
                database
                    .getEpisodesByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toJollyfinEpisode(database, jellyfinApi.userId!!) }
                    .filter { it.playbackPositionTicks > 0 }
            movies + episodes
        }
    }

    override suspend fun getLatestMedia(parentId: UUID): List<JollyfinItem> {
        return emptyList()
    }

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<JollyfinSeason> =
        withContext(Dispatchers.IO) {
            database.getSeasonsByShowId(seriesId).map {
                it.toJollyfinSeason(database, jellyfinApi.userId!!)
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<JollyfinEpisode> {
        return withContext(Dispatchers.IO) {
            val result = mutableListOf<JollyfinEpisode>()
            val shows =
                database
                    .getShowsByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .filter { if (seriesId != null) it.id == seriesId else true }
            for (show in shows) {
                val episodes =
                    database.getEpisodesByShowId(show.id).map {
                        it.toJollyfinEpisode(database, jellyfinApi.userId!!)
                    }
                val indexOfLastPlayed = episodes.indexOfLast { it.played }
                if (indexOfLastPlayed == -1) {
                    result.add(episodes.first())
                } else {
                    episodes.getOrNull(indexOfLastPlayed + 1)?.let { result.add(it) }
                }
            }
            result.filter { it.playbackPositionTicks == 0L }
        }
    }

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<JollyfinEpisode> =
        withContext(Dispatchers.IO) {
            val items =
                database.getEpisodesBySeasonId(seasonId).map {
                    it.toJollyfinEpisode(database, jellyfinApi.userId!!)
                }
            if (startItemId != null) return@withContext items.dropWhile { it.id != startItemId }
            items
        }

    override suspend fun getMediaSources(itemId: UUID, includePath: Boolean): List<JollyfinSource> =
        withContext(Dispatchers.IO) {
            database.getSources(itemId).map { it.toJollyfinSource(database) }
        }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String {
        // Local downloads resolve their path via JollyfinSourceDto.toJollyfinSource(database)
        // instead, so this is unreachable in practice - fail soft rather than crash (JF-88).
        return ""
    }

    override suspend fun getSegments(itemId: UUID): List<JollyfinSegment> =
        withContext(Dispatchers.IO) { database.getSegments(itemId).map { it.toJollyfinSegment() } }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val sources =
                    File(context.filesDir, "trickplay/$itemId").listFiles()
                        ?: return@withContext null
                File(sources.first(), index.toString()).readBytes()
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun postCapabilities() {}

    override suspend fun postPlaybackStart(itemId: UUID) {}

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    ) {
        withContext(Dispatchers.IO) {
            when {
                playedPercentage < 10 -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
                    database.setPlayed(jellyfinApi.userId!!, itemId, false)
                    database.setLastPlayedDate(jellyfinApi.userId!!, itemId, null)
                }
                playedPercentage > 90 -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
                    database.setPlayed(jellyfinApi.userId!!, itemId, true)
                    database.setLastPlayedDate(jellyfinApi.userId!!, itemId, DateTime.now())
                }
                else -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
                    database.setPlayed(jellyfinApi.userId!!, itemId, false)
                    database.setLastPlayedDate(jellyfinApi.userId!!, itemId, null)
                }
            }
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, true)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, false)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, true)
            database.setLastPlayedDate(jellyfinApi.userId!!, itemId, DateTime.now())
            database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, false)
            database.setLastPlayedDate(jellyfinApi.userId!!, itemId, null)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    // Unlike markAsFavorite/markAsPlayed above, there's no "set a local flag and sync later"
    // equivalent for a delete - it must actually reach the server.
    override suspend fun deleteItem(itemId: UUID) {
        throw Exception("Deleting an item is not available in offline mode")
    }

    // Nothing to scan without a server - best-effort/fire-and-forget by design, so a no-op here
    // rather than throwing.
    override suspend fun refreshLibrary() {}

    override suspend fun canDeleteMedia(): Boolean = false

    override fun getBaseUrl(): String {
        return ""
    }

    override fun getAccessToken(): String? = null

    override suspend fun updateDeviceName(name: String) {
        // No-op offline: nothing to sync the device name to (JF-88).
    }

    override suspend fun getUserConfiguration(): UserConfiguration? {
        return null
    }

    override suspend fun getDownloads(): List<JollyfinItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<JollyfinItem>()
            items.addAll(
                database
                    .getMoviesByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toJollyfinMovie(database, jellyfinApi.userId!!) }
            )
            items.addAll(
                database
                    .getShowsByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toJollyfinShow(database, jellyfinApi.userId!!) }
            )
            items
        }

    override fun getUserId(): UUID {
        return jellyfinApi.userId!!
    }

    override suspend fun getDisplayPreferences(
        displayPreferencesId: String,
        client: String,
    ): DisplayPreferencesDto {
        throw Exception("Remote config is not available in offline mode")
    }

    override suspend fun updateDisplayPreferences(
        displayPreferencesId: String,
        client: String,
        data: DisplayPreferencesDto,
    ) {
        throw Exception("Remote config is not available in offline mode")
    }
}
