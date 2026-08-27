package dev.pschmitt.jellyfin.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.pschmitt.jellyfin.api.JellyfinApi
import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.models.JollyfinCollection
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.JollyfinPerson
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.JollyfinSegment
import dev.pschmitt.jellyfin.models.JollyfinShow
import dev.pschmitt.jellyfin.models.JollyfinSource
import dev.pschmitt.jellyfin.models.SortBy
import dev.pschmitt.jellyfin.models.SortOrder
import dev.pschmitt.jellyfin.models.toJollyfinCollection
import dev.pschmitt.jellyfin.models.toJollyfinEpisode
import dev.pschmitt.jellyfin.models.toJollyfinItem
import dev.pschmitt.jellyfin.models.toJollyfinMovie
import dev.pschmitt.jellyfin.models.toJollyfinPerson
import dev.pschmitt.jellyfin.models.toJollyfinSeason
import dev.pschmitt.jellyfin.models.toJollyfinSegment
import dev.pschmitt.jellyfin.models.toJollyfinShow
import dev.pschmitt.jellyfin.models.toJollyfinSource
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceOptionsDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder as ItemSortOrder
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.UserConfiguration
import timber.log.Timber

class JellyfinRepositoryImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : JellyfinRepository {
    override suspend fun getPublicSystemInfo(): PublicSystemInfo =
        withContext(Dispatchers.IO) { jellyfinApi.systemApi.getPublicSystemInfo().content }

    override suspend fun getUserViews(): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            jellyfinApi.viewsApi.getUserViews(jellyfinApi.userId!!).content.items
        }

    override suspend fun getEpisode(itemId: UUID): JollyfinEpisode =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toJollyfinEpisode(this@JellyfinRepositoryImpl, database)!!
        }

    override suspend fun getMovie(itemId: UUID): JollyfinMovie =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toJollyfinMovie(this@JellyfinRepositoryImpl, database)
        }

    override suspend fun getShow(itemId: UUID): JollyfinShow =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toJollyfinShow(this@JellyfinRepositoryImpl, database)
        }

    override suspend fun getSeason(itemId: UUID): JollyfinSeason =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toJollyfinSeason(this@JellyfinRepositoryImpl)
        }

    override suspend fun getLibraries(): List<JollyfinCollection> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi.getItems(jellyfinApi.userId!!).content.items.mapNotNull {
                it.toJollyfinCollection(this@JellyfinRepositoryImpl)
            }
        }

    override suspend fun getItem(itemId: UUID): JollyfinItem? =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId = itemId, userId = jellyfinApi.userId!!)
                .content
                .toJollyfinItem(this@JellyfinRepositoryImpl)
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
    ): List<JollyfinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    parentId = parentId,
                    includeItemTypes = includeTypes,
                    recursive = recursive,
                    sortBy = listOf(ItemSortBy.fromName(sortBy.sortString)),
                    sortOrder = listOf(ItemSortOrder.fromName(sortOrder.sortString)),
                    startIndex = startIndex,
                    limit = limit,
                    searchTerm = searchTerm,
                    fields = listOf(ItemFields.PROVIDER_IDS),
                )
                .content
                .items
                .mapNotNull { it.toJollyfinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        searchTerm: String?,
    ): Flow<PagingData<JollyfinItem>> {
        return Pager(
                config = PagingConfig(pageSize = 10, enablePlaceholders = false),
                pagingSourceFactory = {
                    ItemsPagingSource(
                        this,
                        parentId,
                        includeTypes,
                        recursive,
                        sortBy,
                        sortOrder,
                        searchTerm,
                    )
                },
            )
            .flow
    }

    override suspend fun getPerson(personId: UUID): JollyfinPerson =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(personId, jellyfinApi.userId!!)
                .content
                .toJollyfinPerson(this@JellyfinRepositoryImpl)
        }

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<JollyfinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    personIds = personIds,
                    includeItemTypes = includeTypes,
                    recursive = recursive,
                    fields = listOf(ItemFields.PROVIDER_IDS),
                )
                .content
                .items
                .mapNotNull { it.toJollyfinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getFavoriteItems(): List<JollyfinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    filters = listOf(ItemFilter.IS_FAVORITE),
                    includeItemTypes =
                        listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                    recursive = true,
                    fields = listOf(ItemFields.PROVIDER_IDS),
                )
                .content
                .items
                .mapNotNull { it.toJollyfinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getSearchItems(query: String): List<JollyfinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    searchTerm = query,
                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                    recursive = true,
                    fields = listOf(ItemFields.PROVIDER_IDS),
                )
                .content
                .items
                .mapNotNull { it.toJollyfinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getSuggestions(): List<JollyfinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.suggestionsApi
                .getSuggestions(
                    jellyfinApi.userId!!,
                    limit = 6,
                    type = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                )
                .content
                .items
                .mapNotNull { it.toJollyfinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getResumeItems(): List<JollyfinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getResumeItems(
                    jellyfinApi.userId!!,
                    limit = 12,
                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
                    fields = listOf(ItemFields.PROVIDER_IDS),
                )
                .content
                .items
                .mapNotNull { it.toJollyfinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getLatestMedia(parentId: UUID): List<JollyfinItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getLatestMedia(
                    jellyfinApi.userId!!,
                    parentId = parentId,
                    limit = 16,
                    // DateCreated isn't in the server's default field set - without asking for it
                    // explicitly, every item comes back with a null dateCreated, so
                    // JollyfinItem.isRecentlyAdded() (the Home "NEW" badge) always reads false.
                    fields = listOf(ItemFields.PROVIDER_IDS, ItemFields.DATE_CREATED),
                )
                .content
                .mapNotNull { it.toJollyfinItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<JollyfinSeason> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                jellyfinApi.showsApi.getSeasons(seriesId, jellyfinApi.userId!!).content.items.map {
                    it.toJollyfinSeason(this@JellyfinRepositoryImpl)
                }
            } else {
                database.getSeasonsByShowId(seriesId).map {
                    it.toJollyfinSeason(database, jellyfinApi.userId!!)
                }
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<JollyfinEpisode> =
        withContext(Dispatchers.IO) {
            jellyfinApi.showsApi
                .getNextUp(
                    jellyfinApi.userId!!,
                    limit = 24,
                    seriesId = seriesId,
                    enableResumable = false,
                )
                .content
                .items
                .mapNotNull { it.toJollyfinEpisode(this@JellyfinRepositoryImpl, database) }
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
            if (!offline) {
                jellyfinApi.showsApi
                    .getEpisodes(
                        seriesId,
                        jellyfinApi.userId!!,
                        seasonId = seasonId,
                        fields = fields,
                        startItemId = startItemId,
                        limit = limit,
                    )
                    .content
                    .items
                    .mapNotNull { it.toJollyfinEpisode(this@JellyfinRepositoryImpl, database) }
            } else {
                database.getEpisodesBySeasonId(seasonId).map {
                    it.toJollyfinEpisode(database, jellyfinApi.userId!!)
                }
            }
        }

    override suspend fun getMediaSources(itemId: UUID, includePath: Boolean): List<JollyfinSource> =
        withContext(Dispatchers.IO) {
            val sources = mutableListOf<JollyfinSource>()
            sources.addAll(
                jellyfinApi.mediaInfoApi
                    .getPostedPlaybackInfo(
                        itemId,
                        PlaybackInfoDto(
                            userId = jellyfinApi.userId!!,
                            deviceProfile =
                                DeviceProfile(
                                    name = "Direct play all",
                                    maxStaticBitrate = 1_000_000_000,
                                    maxStreamingBitrate = 1_000_000_000,
                                    codecProfiles = emptyList(),
                                    containerProfiles = emptyList(),
                                    directPlayProfiles = emptyList(),
                                    transcodingProfiles = emptyList(),
                                    subtitleProfiles =
                                        listOf(
                                            SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
                                            SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
                                        ),
                                ),
                            maxStreamingBitrate = 1_000_000_000,
                        ),
                    )
                    .content
                    .mediaSources
                    .map { it.toJollyfinSource(this@JellyfinRepositoryImpl, itemId, includePath) }
            )
            sources.addAll(database.getSources(itemId).map { it.toJollyfinSource(database) })
            sources
        }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.videosApi.getVideoStreamUrl(
                    itemId,
                    static = true,
                    mediaSourceId = mediaSourceId,
                )
            } catch (e: Exception) {
                Timber.e(e)
                ""
            }
        }

    override suspend fun getSegments(itemId: UUID): List<JollyfinSegment> =
        withContext(Dispatchers.IO) {
            val databaseSegments = database.getSegments(itemId).map { it.toJollyfinSegment() }

            if (databaseSegments.isNotEmpty()) {
                return@withContext databaseSegments
            }

            try {
                val apiSegments =
                    jellyfinApi.mediaSegmentsApi.getItemSegments(itemId).content.items.map {
                        it.toJollyfinSegment()
                    }

                return@withContext apiSegments
            } catch (e: Exception) {
                Timber.e(e)
                return@withContext emptyList()
            }
        }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                try {
                    val sources = File(context.filesDir, "trickplay/$itemId").listFiles()
                    if (sources != null) {
                        return@withContext File(sources.first(), index.toString()).readBytes()
                    }
                } catch (_: Exception) {}

                return@withContext jellyfinApi.trickplayApi
                    .getTrickplayTileImage(itemId, width, index)
                    .content
            } catch (_: Exception) {
                return@withContext null
            }
        }

    override suspend fun postCapabilities() {
        Timber.d("Sending capabilities")
        withContext(Dispatchers.IO) {
            jellyfinApi.sessionApi.postCapabilities(
                playableMediaTypes = listOf(MediaType.VIDEO),
                supportedCommands =
                    listOf(
                        GeneralCommandType.VOLUME_UP,
                        GeneralCommandType.VOLUME_DOWN,
                        GeneralCommandType.TOGGLE_MUTE,
                        GeneralCommandType.SET_AUDIO_STREAM_INDEX,
                        GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
                        GeneralCommandType.MUTE,
                        GeneralCommandType.UNMUTE,
                        GeneralCommandType.SET_VOLUME,
                        GeneralCommandType.DISPLAY_MESSAGE,
                        GeneralCommandType.PLAY,
                        GeneralCommandType.PLAY_STATE,
                        GeneralCommandType.PLAY_NEXT,
                        GeneralCommandType.PLAY_MEDIA_SOURCE,
                    ),
                supportsMediaControl = true,
            )
        }
    }

    override suspend fun postPlaybackStart(itemId: UUID) {
        Timber.d("Sending start $itemId")
        withContext(Dispatchers.IO) {
            jellyfinApi.playStateApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = itemId,
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                )
            )
        }
    }

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    ) {
        Timber.d("Sending stop $itemId")
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
            try {
                jellyfinApi.playStateApi.reportPlaybackStopped(
                    PlaybackStopInfo(itemId = itemId, positionTicks = positionTicks, failed = false)
                )
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        Timber.d("Posting progress of $itemId, position: $positionTicks")
        withContext(Dispatchers.IO) {
            database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
            try {
                jellyfinApi.playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = itemId,
                        canSeek = true,
                        isPaused = isPaused,
                        isMuted = false,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        positionTicks = positionTicks,
                    )
                )
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, true)
            try {
                jellyfinApi.userLibraryApi.markFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, false)
            try {
                jellyfinApi.userLibraryApi.unmarkFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, true)
            database.setLastPlayedDate(jellyfinApi.userId!!, itemId, DateTime.now())
            try {
                jellyfinApi.playStateApi.markPlayedItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, false)
            database.setLastPlayedDate(jellyfinApi.userId!!, itemId, null)
            try {
                jellyfinApi.playStateApi.markUnplayedItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun deleteItem(itemId: UUID) {
        withContext(Dispatchers.IO) { jellyfinApi.libraryApi.deleteItem(itemId) }
    }

    override suspend fun refreshLibrary() {
        withContext(Dispatchers.IO) { jellyfinApi.libraryApi.refreshLibrary() }
    }

    override fun getBaseUrl() = jellyfinApi.api.baseUrl.orEmpty()

    override fun getAccessToken() = jellyfinApi.api.accessToken

    override suspend fun updateDeviceName(name: String) {
        withContext(Dispatchers.IO) {
            jellyfinApi.jellyfin.deviceInfo?.id?.let { id ->
                jellyfinApi.devicesApi.updateDeviceOptions(
                    id,
                    DeviceOptionsDto(0, customName = name),
                )
            }
        }
    }

    override suspend fun getUserConfiguration(): UserConfiguration =
        withContext(Dispatchers.IO) { jellyfinApi.userApi.getCurrentUser().content.configuration!! }

    override suspend fun canDeleteMedia(): Boolean =
        withContext(Dispatchers.IO) {
            jellyfinApi.userApi.getCurrentUser().content.policy?.enableContentDeletion == true
        }

    override suspend fun isCurrentUserAdministrator(): Boolean =
        withContext(Dispatchers.IO) {
            jellyfinApi.userApi.getCurrentUser().content.policy?.isAdministrator == true
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
    ): DisplayPreferencesDto =
        withContext(Dispatchers.IO) {
            jellyfinApi.displayPreferencesApi
                .getDisplayPreferences(displayPreferencesId, jellyfinApi.userId!!, client)
                .content
        }

    override suspend fun updateDisplayPreferences(
        displayPreferencesId: String,
        client: String,
        data: DisplayPreferencesDto,
    ) {
        withContext(Dispatchers.IO) {
            jellyfinApi.displayPreferencesApi.updateDisplayPreferences(
                displayPreferencesId,
                jellyfinApi.userId!!,
                client,
                data,
            )
        }
    }
}
