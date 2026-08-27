package dev.pschmitt.jellyfin.repository

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
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserConfiguration

interface JellyfinRepository {
    suspend fun getPublicSystemInfo(): PublicSystemInfo

    suspend fun getUserViews(): List<BaseItemDto>

    suspend fun getEpisode(itemId: UUID): JollyfinEpisode

    suspend fun getMovie(itemId: UUID): JollyfinMovie

    suspend fun getShow(itemId: UUID): JollyfinShow

    suspend fun getSeason(itemId: UUID): JollyfinSeason

    suspend fun getLibraries(): List<JollyfinCollection>

    suspend fun getItem(itemId: UUID): JollyfinItem?

    suspend fun getItems(
        parentId: UUID? = null,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        startIndex: Int? = null,
        limit: Int? = null,
        searchTerm: String? = null,
    ): List<JollyfinItem>

    suspend fun getItemsPaging(
        parentId: UUID? = null,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        searchTerm: String? = null,
    ): Flow<PagingData<JollyfinItem>>

    suspend fun getPerson(personId: UUID): JollyfinPerson

    suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = true,
    ): List<JollyfinItem>

    suspend fun getFavoriteItems(): List<JollyfinItem>

    suspend fun getSearchItems(query: String): List<JollyfinItem>

    suspend fun getSuggestions(): List<JollyfinItem>

    suspend fun getResumeItems(): List<JollyfinItem>

    suspend fun getLatestMedia(parentId: UUID): List<JollyfinItem>

    suspend fun getSeasons(seriesId: UUID, offline: Boolean = false): List<JollyfinSeason>

    suspend fun getNextUp(seriesId: UUID? = null): List<JollyfinEpisode>

    suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>? = null,
        startItemId: UUID? = null,
        limit: Int? = null,
        offline: Boolean = false,
    ): List<JollyfinEpisode>

    suspend fun getMediaSources(itemId: UUID, includePath: Boolean = false): List<JollyfinSource>

    suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String

    suspend fun getSegments(itemId: UUID): List<JollyfinSegment>

    suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray?

    suspend fun postCapabilities()

    suspend fun postPlaybackStart(itemId: UUID)

    suspend fun postPlaybackStop(itemId: UUID, positionTicks: Long, playedPercentage: Int)

    suspend fun postPlaybackProgress(itemId: UUID, positionTicks: Long, isPaused: Boolean)

    suspend fun markAsFavorite(itemId: UUID)

    suspend fun unmarkAsFavorite(itemId: UUID)

    suspend fun markAsPlayed(itemId: UUID)

    suspend fun markAsUnplayed(itemId: UUID)

    // Unlike markAsFavorite/markAsPlayed, this is not offline-tolerant: a delete has no sensible
    // "retry later" semantics, so a failure here must propagate to the caller rather than being
    // swallowed into a background sync flag.
    suspend fun deleteItem(itemId: UUID)

    /**
     * Kicks off a full library scan (Jellyfin's own "Scan All Libraries" task) - used after a
     * Sonarr/Radarr manual import finishes so the newly-placed file shows up without waiting for
     * Jellyfin's own scheduled scan. Fire-and-forget from the server's point of view; this just
     * requests the scan, it doesn't wait for it to finish.
     */
    suspend fun refreshLibrary()

    /**
     * Whether the current Jellyfin user's server-side policy allows deleting media at all ("Allow
     * this user to delete media" in Jellyfin's admin UI) - gates whether the "Delete from Jellyfin"
     * action is even shown, rather than offering it and having [deleteItem] fail with a permissions
     * error.
     */
    suspend fun canDeleteMedia(): Boolean

    /**
     * Whether the current Jellyfin user is a server administrator - [refreshLibrary] (and
     * Jellyfin's scheduled tasks in general) requires admin rights, so this gates the "Scan all
     * libraries" action rather than offering it and having the request fail with a permissions
     * error.
     */
    suspend fun isCurrentUserAdministrator(): Boolean

    fun getBaseUrl(): String

    /**
     * The current user's raw Jellyfin access token - only needed for the local control API's
     * debug-proxy endpoint (`core/.../localcontrol/LocalControlRouter.kt`), which forwards ad hoc
     * requests using the app's own already-stored credentials rather than exposing them to the
     * caller.
     */
    fun getAccessToken(): String?

    suspend fun updateDeviceName(name: String)

    suspend fun getUserConfiguration(): UserConfiguration?

    suspend fun getDownloads(): List<JollyfinItem>

    fun getUserId(): UUID

    /**
     * Reads the shared per-user [DisplayPreferencesDto] bucket identified by
     * [displayPreferencesId]/[client] - used as a zero-infrastructure transport for cross-device
     * remote config (see [dev.pschmitt.jellyfin.repository.RemoteConfigRepository]), since every
     * instance already talks to this same Jellyfin account continuously.
     */
    suspend fun getDisplayPreferences(
        displayPreferencesId: String,
        client: String,
    ): DisplayPreferencesDto

    /** Writes back a [DisplayPreferencesDto] previously obtained from [getDisplayPreferences]. */
    suspend fun updateDisplayPreferences(
        displayPreferencesId: String,
        client: String,
        data: DisplayPreferencesDto,
    )
}
