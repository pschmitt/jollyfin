package dev.pschmitt.jellyfin.models

import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayAccess

data class JollyfinShow(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<JollyfinSource>,
    val seasons: List<JollyfinSeason>,
    // Whether any episode of this show has a completed local download - unlike [sources], which
    // stays empty for a show (no single file backs a whole series), this drives the "available
    // offline" badge on show posters without changing isDownloaded()/isDownloading() semantics
    // that ItemButtonsBar and friends rely on for the show-level bulk download UI.
    val hasDownloadedEpisodes: Boolean = false,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    val genres: List<String>,
    val people: List<JollyfinItemPerson>,
    override val runtimeTicks: Long,
    val communityRating: Float?,
    val officialRating: String?,
    val status: String,
    val productionYear: Int?,
    val endDate: DateTime?,
    val trailer: String?,
    override val images: JollyfinImages,
    override val chapters: List<JollyfinChapter> = emptyList(),
    val tvdbId: String? = null,
    val tmdbId: String? = null,
    override val dateCreated: DateTime? = null,
) : JollyfinItem

fun BaseItemDto.toJollyfinShow(
    jellyfinRepository: JellyfinRepository,
    database: ServerDatabaseDao? = null,
): JollyfinShow {
    return JollyfinShow(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        unplayedItemCount = userData?.unplayedItemCount,
        sources = emptyList(),
        hasDownloadedEpisodes = database?.let { hasDownloadedEpisode(it, id) } ?: false,
        seasons = emptyList(),
        genres = genres ?: emptyList(),
        people = people?.map { it.toJollyfinPerson(jellyfinRepository) } ?: emptyList(),
        runtimeTicks = runTimeTicks ?: 0,
        communityRating = communityRating,
        officialRating = officialRating,
        status = status ?: "Ended",
        productionYear = productionYear,
        endDate = endDate,
        trailer = remoteTrailers?.getOrNull(0)?.url,
        images = toJollyfinImages(jellyfinRepository),
        tvdbId =
            providerIds?.entries?.firstOrNull { it.key.equals("Tvdb", ignoreCase = true) }?.value,
        tmdbId =
            providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value,
        dateCreated = dateCreated,
    )
}

fun JollyfinShowDto.toJollyfinShow(database: ServerDatabaseDao, userId: UUID): JollyfinShow {
    val userData = database.getUserDataOrCreateNew(id, userId)
    return JollyfinShow(
        id = id,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        played = userData.played,
        favorite = userData.favorite,
        canPlay = true,
        canDownload = false,
        unplayedItemCount = null,
        sources = emptyList(),
        hasDownloadedEpisodes = hasDownloadedEpisode(database, id),
        seasons = emptyList(),
        genres = emptyList(),
        people = emptyList(),
        runtimeTicks = runtimeTicks,
        communityRating = communityRating,
        officialRating = officialRating,
        status = status,
        productionYear = productionYear,
        endDate = endDate,
        trailer = null,
        images = toLocalJollyfinImages(itemId = id),
        tvdbId = tvdbId,
    )
}

// Batch-checks via getSourcesForItems (see its kdoc) instead of calling getSources per episode -
// a show can have many locally-known episodes once auto-download rules or manual downloads pile
// up over time.
private fun hasDownloadedEpisode(database: ServerDatabaseDao, seriesId: UUID): Boolean {
    val episodeIds = database.getEpisodesByShowId(seriesId).map { it.id }
    if (episodeIds.isEmpty()) return false
    return database.getSourcesForItems(episodeIds).any {
        it.type == JollyfinSourceType.LOCAL && !it.path.endsWith(".download")
    }
}
