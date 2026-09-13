package dev.pschmitt.jellyfin.models

import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayAccess

data class JollyfinMovie(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<JollyfinSource>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    val premiereDate: LocalDateTime?,
    val people: List<JollyfinItemPerson>,
    val genres: List<String>,
    val communityRating: Float?,
    val officialRating: String?,
    val status: String,
    val productionYear: Int?,
    val endDate: LocalDateTime?,
    val trailer: String?,
    override val unplayedItemCount: Int? = null,
    override val images: JollyfinImages,
    override val chapters: List<JollyfinChapter>,
    override val trickplayInfo: Map<String, JollyfinTrickplayInfo>?,
    val tmdbId: String? = null,
    override val dateCreated: LocalDateTime? = null,
) : JollyfinItem, JollyfinSources

suspend fun BaseItemDto.toJollyfinMovie(
    jellyfinRepository: JellyfinRepository,
    serverDatabase: ServerDatabaseDao? = null,
): JollyfinMovie {
    val sources = mutableListOf<JollyfinSource>()
    sources.addAll(mediaSources?.map { it.toJollyfinSource(jellyfinRepository, id) } ?: emptyList())
    if (serverDatabase != null) {
        sources.addAll(serverDatabase.getSources(id).map { it.toJollyfinSource(serverDatabase) })
    }
    return JollyfinMovie(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        sources = sources,
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        runtimeTicks = runTimeTicks ?: 0,
        playbackPositionTicks = userData?.playbackPositionTicks ?: 0,
        premiereDate = premiereDate,
        communityRating = communityRating,
        genres = genres ?: emptyList(),
        people = people?.map { it.toJollyfinPerson(jellyfinRepository) } ?: emptyList(),
        officialRating = officialRating,
        status = status ?: "Ended",
        productionYear = productionYear,
        endDate = endDate,
        trailer = remoteTrailers?.getOrNull(0)?.url,
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
        tmdbId =
            providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value,
        dateCreated = dateCreated,
    )
}

fun JollyfinMovieDto.toJollyfinMovie(database: ServerDatabaseDao, userId: UUID): JollyfinMovie {
    val userData = database.getUserDataOrCreateNew(id, userId)
    // NOTE: `sources` was previously recomputed a second time (with an identical, redundant
    // `database.getSources(id)` call) inline in the JollyfinMovie(...) constructor call below,
    // discarding this one - doubling the getSources query, the per-source getMediaStreamsBySourceId
    // query and the per-source File(path).length() stat for every movie mapped this way. Reusing
    // the already-computed `sources` fixes that.
    val sources = database.getSources(id).map { it.toJollyfinSource(database) }
    val trickplayInfos = mutableMapOf<String, JollyfinTrickplayInfo>()
    for (source in sources) {
        database.getTrickplayInfo(source.id)?.toJollyfinTrickplayInfo()?.let {
            trickplayInfos[source.id] = it
        }
    }
    return JollyfinMovie(
        id = id,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        played = userData.played,
        favorite = userData.favorite,
        runtimeTicks = runtimeTicks,
        playbackPositionTicks = userData.playbackPositionTicks,
        premiereDate = premiereDate,
        genres = emptyList(),
        people = emptyList(),
        communityRating = communityRating,
        officialRating = officialRating,
        status = status,
        productionYear = productionYear,
        endDate = endDate,
        canDownload = false,
        canPlay = true,
        sources = sources,
        trailer = null,
        images = toLocalJollyfinImages(itemId = id),
        chapters = chapters ?: emptyList(),
        trickplayInfo = trickplayInfos,
        tmdbId = tmdbId,
    )
}

/**
 * Batch equivalent of [toJollyfinMovie] for mapping a whole list of DB rows at once. The singular
 * function above does one `getUserDataOrCreateNew`/`getSources`/`getTrickplayInfo` (plus one
 * `getMediaStreamsBySourceId` per source) round trip *per movie*, which is an N+1 query pattern -
 * fine for a single item, but for a list of M movies it's roughly 3+ DB queries times M, run
 * sequentially. This does the same fetches once for the whole list (a handful of queries total,
 * each with an `IN (...)` clause) and maps in memory, which is what
 * [dev.pschmitt.jellyfin.film.presentation.downloads.DownloadsViewModel] uses to load the Downloads
 * screen's local list without a per-row DB round trip.
 */
fun List<JollyfinMovieDto>.toJollyfinMovies(
    database: ServerDatabaseDao,
    userId: UUID,
): List<JollyfinMovie> {
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

        JollyfinMovie(
            id = dto.id,
            name = dto.name,
            originalTitle = dto.originalTitle,
            overview = dto.overview,
            played = userData.played,
            favorite = userData.favorite,
            runtimeTicks = dto.runtimeTicks,
            playbackPositionTicks = userData.playbackPositionTicks,
            premiereDate = dto.premiereDate,
            genres = emptyList(),
            people = emptyList(),
            communityRating = dto.communityRating,
            officialRating = dto.officialRating,
            status = dto.status,
            productionYear = dto.productionYear,
            endDate = dto.endDate,
            canDownload = false,
            canPlay = true,
            sources = sources,
            trailer = null,
            images = dto.toLocalJollyfinImages(itemId = dto.id),
            chapters = dto.chapters ?: emptyList(),
            trickplayInfo = trickplayInfo,
            tmdbId = dto.tmdbId,
        )
    }
}
