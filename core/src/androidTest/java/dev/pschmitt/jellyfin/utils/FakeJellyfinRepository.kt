package dev.pschmitt.jellyfin.utils

import androidx.paging.PagingData
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
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserConfiguration

/** Minimal test double covering only what [AutoDownloadRuleEvaluator] exercises. */
class FakeJellyfinRepository(
    private val seasons: List<JollyfinSeason> = emptyList(),
    private val episodesBySeasonId: Map<UUID, List<JollyfinEpisode>> = emptyMap(),
    private val userId: UUID = UUID.randomUUID(),
) : JellyfinRepository {
    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<JollyfinSeason> =
        seasons

    override suspend fun getSeason(itemId: UUID): JollyfinSeason = seasons.first { it.id == itemId }

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<JollyfinEpisode> = episodesBySeasonId[seasonId].orEmpty()

    override fun getUserId(): UUID = userId

    override suspend fun getPublicSystemInfo(): PublicSystemInfo = error("not used in test")

    override suspend fun getUserViews(): List<BaseItemDto> = error("not used in test")

    override suspend fun getEpisode(itemId: UUID): JollyfinEpisode = error("not used in test")

    override suspend fun getMovie(itemId: UUID): JollyfinMovie = error("not used in test")

    override suspend fun getShow(itemId: UUID): JollyfinShow = error("not used in test")

    override suspend fun getLibraries(): List<JollyfinCollection> = error("not used in test")

    override suspend fun getItem(itemId: UUID): JollyfinItem? = error("not used in test")

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        startIndex: Int?,
        limit: Int?,
        searchTerm: String?,
    ): List<JollyfinItem> = error("not used in test")

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        searchTerm: String?,
    ): Flow<PagingData<JollyfinItem>> = error("not used in test")

    override suspend fun getPerson(personId: UUID): JollyfinPerson = error("not used in test")

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<JollyfinItem> = error("not used in test")

    override suspend fun getFavoriteItems(): List<JollyfinItem> = error("not used in test")

    override suspend fun getSearchItems(query: String): List<JollyfinItem> =
        error("not used in test")

    override suspend fun getSuggestions(): List<JollyfinItem> = error("not used in test")

    override suspend fun getResumeItems(): List<JollyfinItem> = error("not used in test")

    override suspend fun getLatestMedia(parentId: UUID): List<JollyfinItem> =
        error("not used in test")

    override suspend fun getNextUp(seriesId: UUID?): List<JollyfinEpisode> =
        error("not used in test")

    override suspend fun getMediaSources(
        itemId: UUID,
        includePath: Boolean,
    ): List<JollyfinSource> = error("not used in test")

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String =
        error("not used in test")

    override suspend fun getSegments(itemId: UUID): List<JollyfinSegment> =
        error("not used in test")

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        error("not used in test")

    override suspend fun postCapabilities() = error("not used in test")

    override suspend fun postPlaybackStart(itemId: UUID) = error("not used in test")

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    ) = error("not used in test")

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
    ) = error("not used in test")

    override suspend fun markAsFavorite(itemId: UUID) = error("not used in test")

    override suspend fun unmarkAsFavorite(itemId: UUID) = error("not used in test")

    override suspend fun markAsPlayed(itemId: UUID) = error("not used in test")

    override suspend fun markAsUnplayed(itemId: UUID) = error("not used in test")

    override suspend fun refreshLibrary() = error("not used in test")

    override suspend fun deleteItem(itemId: UUID) = error("not used in test")

    override suspend fun canDeleteMedia(): Boolean = error("not used in test")

    override suspend fun isCurrentUserAdministrator(): Boolean = error("not used in test")

    override fun getBaseUrl(): String = error("not used in test")

    override suspend fun updateDeviceName(name: String) = error("not used in test")

    override suspend fun getUserConfiguration(): UserConfiguration? = error("not used in test")

    override suspend fun getDownloads(): List<JollyfinItem> = error("not used in test")
}
