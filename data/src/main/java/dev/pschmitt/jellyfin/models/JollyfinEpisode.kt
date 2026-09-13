package dev.pschmitt.jellyfin.models

import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.PlayAccess

data class JollyfinEpisode(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    val indexNumber: Int,
    val indexNumberEnd: Int?,
    val parentIndexNumber: Int,
    override val sources: List<JollyfinSource>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    val premiereDate: DateTime?,
    val lastPlayedDate: DateTime? = null,
    val seriesId: UUID,
    val seriesName: String,
    val seasonId: UUID,
    val seasonName: String?,
    val communityRating: Float?,
    val people: List<JollyfinItemPerson>,
    override val unplayedItemCount: Int? = null,
    val missing: Boolean = false,
    override val images: JollyfinImages,
    override val chapters: List<JollyfinChapter>,
    override val trickplayInfo: Map<String, JollyfinTrickplayInfo>?,
    override val dateCreated: DateTime? = null,
) : JollyfinItem, JollyfinSources

suspend fun BaseItemDto.toJollyfinEpisode(
    jellyfinRepository: JellyfinRepository,
    database: ServerDatabaseDao? = null,
): JollyfinEpisode? {
    val sources = mutableListOf<JollyfinSource>()
    sources.addAll(mediaSources?.map { it.toJollyfinSource(jellyfinRepository, id) } ?: emptyList())
    if (database != null) {
        sources.addAll(database.getSources(id).map { it.toJollyfinSource(database) })
    }
    return try {
        JollyfinEpisode(
            id = id,
            name = name.orEmpty(),
            originalTitle = originalTitle,
            overview = overview.orEmpty(),
            indexNumber = indexNumber ?: 0,
            indexNumberEnd = indexNumberEnd,
            parentIndexNumber = parentIndexNumber ?: 0,
            sources = sources,
            played = userData?.played == true,
            favorite = userData?.isFavorite == true,
            canPlay = playAccess != PlayAccess.NONE,
            canDownload = canDownload == true,
            runtimeTicks = runTimeTicks ?: 0,
            playbackPositionTicks = userData?.playbackPositionTicks ?: 0L,
            premiereDate = premiereDate,
            lastPlayedDate = userData?.lastPlayedDate,
            seriesId = seriesId!!,
            seriesName = seriesName.orEmpty(),
            seasonId = seasonId!!,
            seasonName = seasonName,
            communityRating = communityRating,
            people = people?.map { it.toJollyfinPerson(jellyfinRepository) } ?: emptyList(),
            missing = locationType == LocationType.VIRTUAL,
            images = toJollyfinImages(jellyfinRepository),
            chapters = toJollyfinChapters(),
            trickplayInfo =
                trickplay
                    ?.mapNotNull { (key, resolutions) ->
                        resolutions?.get(resolutions.keys.max())?.toJollyfinTrickplayInfo()?.let {
                            key to it
                        }
                    }
                    ?.toMap(),
            dateCreated = dateCreated,
        )
    } catch (_: NullPointerException) {
        null
    }
}

fun JollyfinEpisodeDto.toJollyfinEpisode(
    database: ServerDatabaseDao,
    userId: UUID,
): JollyfinEpisode {
    val userData = database.getUserDataOrCreateNew(id, userId)
    val sources = database.getSources(id).map { it.toJollyfinSource(database) }
    val trickplayInfos = mutableMapOf<String, JollyfinTrickplayInfo>()
    for (source in sources) {
        database.getTrickplayInfo(source.id)?.toJollyfinTrickplayInfo()?.let {
            trickplayInfos[source.id] = it
        }
    }
    return JollyfinEpisode(
        id = id,
        name = name,
        originalTitle = "",
        overview = overview,
        indexNumber = indexNumber,
        indexNumberEnd = indexNumberEnd,
        parentIndexNumber = parentIndexNumber,
        sources = sources,
        played = userData.played,
        favorite = userData.favorite,
        canPlay = true,
        canDownload = false,
        runtimeTicks = runtimeTicks,
        playbackPositionTicks = userData.playbackPositionTicks,
        premiereDate = premiereDate,
        lastPlayedDate = userData.lastPlayedDate,
        seriesId = seriesId,
        seriesName = seriesName,
        seasonId = seasonId,
        seasonName = null,
        communityRating = communityRating,
        people = emptyList(),
        images = toLocalJollyfinImages(itemId = id),
        chapters = chapters ?: emptyList(),
        trickplayInfo = trickplayInfos,
    )
}

/**
 * Batch equivalent of [toJollyfinEpisode] for mapping a whole list of DB rows at once - see the
 * kdoc on [toJollyfinMovies][dev.pschmitt.jellyfin.models.toJollyfinMovies] for why this exists
 * (the same N+1 query pattern applies here, one userdata/sources/mediaStreams/trickplay round trip
 * per episode instead of once for the whole batch).
 */
fun List<JollyfinEpisodeDto>.toJollyfinEpisodes(
    database: ServerDatabaseDao,
    userId: UUID,
): List<JollyfinEpisode> {
    if (isEmpty()) return emptyList()
    val itemIds = map { it.id }

    val userDataByItemId =
        database.getUserDataForItems(itemIds, userId).associateBy { it.itemId }.toMutableMap()
    for (itemId in itemIds) {
        if (itemId !in userDataByItemId) {
            val created =
                JollyfinUserDataDto(
                    userId = userId,
                    itemId = itemId,
                    played = false,
                    favorite = false,
                    playbackPositionTicks = 0L,
                )
            database.insertUserData(created)
            userDataByItemId[itemId] = created
        }
    }

    val sourcesByItemId = database.getSourcesForItems(itemIds).groupBy { it.itemId }
    val sourceIds = sourcesByItemId.values.flatten().map { it.id }
    val mediaStreamsBySourceId =
        database.getMediaStreamsForSources(sourceIds).groupBy { it.sourceId }
    val trickplayBySourceId =
        database.getTrickplayInfoForSources(sourceIds).associateBy { it.sourceId }

    return map { dto ->
        val userData = userDataByItemId.getValue(dto.id)
        val sources =
            (sourcesByItemId[dto.id] ?: emptyList()).map { sourceDto ->
                sourceDto.toJollyfinSource(mediaStreamsBySourceId[sourceDto.id] ?: emptyList())
            }
        val trickplayInfo =
            sources
                .mapNotNull { source ->
                    trickplayBySourceId[source.id]?.toJollyfinTrickplayInfo()?.let {
                        source.id to it
                    }
                }
                .toMap()

        JollyfinEpisode(
            id = dto.id,
            name = dto.name,
            originalTitle = "",
            overview = dto.overview,
            indexNumber = dto.indexNumber,
            indexNumberEnd = dto.indexNumberEnd,
            parentIndexNumber = dto.parentIndexNumber,
            sources = sources,
            played = userData.played,
            favorite = userData.favorite,
            canPlay = true,
            canDownload = false,
            runtimeTicks = dto.runtimeTicks,
            playbackPositionTicks = userData.playbackPositionTicks,
            premiereDate = dto.premiereDate,
            seriesId = dto.seriesId,
            seriesName = dto.seriesName,
            seasonId = dto.seasonId,
            seasonName = null,
            communityRating = dto.communityRating,
            people = emptyList(),
            images = dto.toLocalJollyfinImages(itemId = dto.id),
            chapters = dto.chapters ?: emptyList(),
            trickplayInfo = trickplayInfo,
        )
    }
}
