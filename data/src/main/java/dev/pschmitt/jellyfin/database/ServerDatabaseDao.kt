package dev.pschmitt.jellyfin.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.pschmitt.jellyfin.models.AutoDownloadRuleDto
import dev.pschmitt.jellyfin.models.JollyfinEpisodeDto
import dev.pschmitt.jellyfin.models.JollyfinMediaStreamDto
import dev.pschmitt.jellyfin.models.JollyfinMovieDto
import dev.pschmitt.jellyfin.models.JollyfinSeasonDto
import dev.pschmitt.jellyfin.models.JollyfinSegmentDto
import dev.pschmitt.jellyfin.models.JollyfinShowDto
import dev.pschmitt.jellyfin.models.JollyfinSourceDto
import dev.pschmitt.jellyfin.models.JollyfinTrickplayInfoDto
import dev.pschmitt.jellyfin.models.JollyfinUserDataDto
import dev.pschmitt.jellyfin.models.PendingDownloadRequestDto
import dev.pschmitt.jellyfin.models.Profile
import dev.pschmitt.jellyfin.models.ProfileWithUserAndServer
import dev.pschmitt.jellyfin.models.PvrServiceConfig
import dev.pschmitt.jellyfin.models.Server
import dev.pschmitt.jellyfin.models.ServerAddress
import dev.pschmitt.jellyfin.models.ServerWithAddressAndUser
import dev.pschmitt.jellyfin.models.ServerWithAddresses
import dev.pschmitt.jellyfin.models.ServerWithAddressesAndUsers
import dev.pschmitt.jellyfin.models.ServerWithUsers
import dev.pschmitt.jellyfin.models.User
import java.util.UUID
import org.jellyfin.sdk.model.DateTime

@Dao
interface ServerDatabaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertServer(server: Server)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertServerAddress(address: ServerAddress)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertUser(user: User)

    @Update fun update(server: Server)

    @Query("SELECT * FROM servers WHERE id = :id") fun get(id: String): Server?

    @Query("SELECT * FROM users WHERE id = :id") fun getUser(id: UUID): User?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithAddresses(id: String): ServerWithAddresses

    @Query("SELECT * FROM serverAddresses WHERE id = :id") fun getAddress(id: UUID): ServerAddress

    @Query("SELECT * FROM users WHERE serverId = :serverId")
    fun getUsers(serverId: String): List<User>

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithUsers(id: String): ServerWithUsers

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithAddressesAndUsers(id: String): ServerWithAddressesAndUsers?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithAddressAndUser(id: String): ServerWithAddressAndUser?

    @Transaction
    @Query("SELECT * FROM servers")
    fun getServersWithAddresses(): List<ServerWithAddresses>

    @Transaction
    @Query("SELECT * FROM servers")
    fun getAllServersWithAddressesAndUsers(): List<ServerWithAddressesAndUsers>

    @Query("SELECT * FROM autoDownloadRules")
    fun getAllAutoDownloadRules(): List<AutoDownloadRuleDto>

    @Query("DELETE FROM servers") fun clear()

    @Query("SELECT * FROM servers") fun getAllServersSync(): List<Server>

    @Query("SELECT COUNT(*) FROM servers") fun getServersCount(): Int

    @Query("DELETE FROM servers WHERE id = :id") fun delete(id: String)

    @Query("DELETE FROM users WHERE id = :id") fun deleteUser(id: UUID)

    @Query("DELETE FROM serverAddresses WHERE id = :id") fun deleteServerAddress(id: UUID)

    @Query("UPDATE servers SET currentUserId = :userId WHERE id = :serverId")
    fun updateServerCurrentUser(serverId: String, userId: UUID)

    @Query(
        "SELECT * FROM users WHERE id = (SELECT currentUserId FROM servers WHERE id = :serverId)"
    )
    fun getServerCurrentUser(serverId: String): User?

    @Query(
        "SELECT * FROM serverAddresses WHERE id = (SELECT currentServerAddressId FROM servers WHERE id = :serverId)"
    )
    fun getServerCurrentAddress(serverId: String): ServerAddress?

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertMovie(movie: JollyfinMovieDto)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertSource(source: JollyfinSourceDto)

    @Query("SELECT * FROM movies WHERE id = :id") fun getMovie(id: UUID): JollyfinMovieDto

    @Query(
        "SELECT * FROM movies JOIN sources ON movies.id = sources.itemId ORDER BY movies.name ASC"
    )
    fun getMoviesAndSources(): Map<JollyfinMovieDto, List<JollyfinSourceDto>>

    @Query("SELECT * FROM sources WHERE itemId = :itemId")
    fun getSources(itemId: UUID): List<JollyfinSourceDto>

    // Batch variant of getSources, used to avoid an N+1 query pattern when mapping a whole list of
    // rows at once (see toJollyfinMovies/toJollyfinEpisodes in the models package).
    @Query("SELECT * FROM sources WHERE itemId IN (:itemIds)")
    fun getSourcesForItems(itemIds: List<UUID>): List<JollyfinSourceDto>

    @Query("SELECT * FROM sources") fun getAllSources(): List<JollyfinSourceDto>

    @Query("SELECT * FROM sources WHERE downloadId = :downloadId")
    fun getSourceByDownloadId(downloadId: Long): JollyfinSourceDto?

    @Query("UPDATE sources SET downloadId = :downloadId WHERE id = :id")
    fun setSourceDownloadId(id: String, downloadId: Long)

    @Query("UPDATE sources SET checksum = :checksum WHERE id = :id")
    fun setSourceChecksum(id: String, checksum: String)

    @Query("UPDATE sources SET path = :path WHERE id = :id")
    fun setSourcePath(id: String, path: String)

    @Query("UPDATE sources SET pausedByBatterySaver = :paused WHERE id = :id")
    fun setSourcePausedByBatterySaver(id: String, paused: Boolean)

    @Query("UPDATE sources SET excludeFromAutoDelete = :excluded WHERE id = :id")
    fun setSourceExcludeFromAutoDelete(id: String, excluded: Boolean)

    @Query("DELETE FROM sources WHERE id = :id") fun deleteSource(id: String)

    @Query("DELETE FROM movies WHERE id = :id") fun deleteMovie(id: UUID)

    @Query(
        "UPDATE userdata SET playbackPositionTicks = :playbackPositionTicks WHERE itemId = :itemId AND userid = :userId"
    )
    fun setPlaybackPositionTicks(itemId: UUID, userId: UUID, playbackPositionTicks: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMediaStream(mediaStream: JollyfinMediaStreamDto)

    @Query("SELECT * FROM mediastreams") fun getAllMediaStreams(): List<JollyfinMediaStreamDto>

    @Query("SELECT * FROM mediastreams WHERE sourceId = :sourceId")
    fun getMediaStreamsBySourceId(sourceId: String): List<JollyfinMediaStreamDto>

    // Batch variant of getMediaStreamsBySourceId, see getSourcesForItems.
    @Query("SELECT * FROM mediastreams WHERE sourceId IN (:sourceIds)")
    fun getMediaStreamsForSources(sourceIds: List<String>): List<JollyfinMediaStreamDto>

    @Query("SELECT * FROM mediastreams WHERE downloadId = :downloadId")
    fun getMediaStreamByDownloadId(downloadId: Long): JollyfinMediaStreamDto?

    @Query("UPDATE mediastreams SET downloadId = :downloadId WHERE id = :id")
    fun setMediaStreamDownloadId(id: UUID, downloadId: Long)

    @Query("UPDATE mediastreams SET path = :path WHERE id = :id")
    fun setMediaStreamPath(id: UUID, path: String)

    @Query("DELETE FROM mediastreams WHERE id = :id") fun deleteMediaStream(id: UUID)

    @Query("DELETE FROM mediastreams WHERE sourceId = :sourceId")
    fun deleteMediaStreamsBySourceId(sourceId: String)

    @Query("UPDATE userdata SET played = :played WHERE userId = :userId AND itemId = :itemId")
    fun setPlayed(userId: UUID, itemId: UUID, played: Boolean)

    @Query(
        "UPDATE userdata SET lastPlayedDate = :lastPlayedDate WHERE userId = :userId AND itemId = :itemId"
    )
    fun setLastPlayedDate(userId: UUID, itemId: UUID, lastPlayedDate: DateTime?)

    @Query("UPDATE userdata SET favorite = :favorite WHERE userId = :userId AND itemId = :itemId")
    fun setFavorite(userId: UUID, itemId: UUID, favorite: Boolean)

    @Query("SELECT * FROM movies ORDER BY name ASC") fun getMovies(): List<JollyfinMovieDto>

    @Query("SELECT * FROM movies WHERE serverId = :serverId ORDER BY name ASC")
    fun getMoviesByServerId(serverId: String): List<JollyfinMovieDto>

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertShow(show: JollyfinShowDto)

    @Query("SELECT * FROM shows WHERE id = :id") fun getShow(id: UUID): JollyfinShowDto

    @Query("SELECT * FROM shows ORDER BY name ASC") fun getShows(): List<JollyfinShowDto>

    @Query("SELECT * FROM shows WHERE serverId = :serverId ORDER BY name ASC")
    fun getShowsByServerId(serverId: String): List<JollyfinShowDto>

    @Query("DELETE FROM shows WHERE id = :id") fun deleteShow(id: UUID)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertSeason(show: JollyfinSeasonDto)

    @Query("SELECT * FROM seasons WHERE id = :id") fun getSeason(id: UUID): JollyfinSeasonDto

    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY indexNumber ASC")
    fun getSeasonsByShowId(seriesId: UUID): List<JollyfinSeasonDto>

    @Query("DELETE FROM seasons WHERE id = :id") fun deleteSeason(id: UUID)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertEpisode(episode: JollyfinEpisodeDto)

    @Query("SELECT * FROM episodes WHERE id = :id") fun getEpisode(id: UUID): JollyfinEpisodeDto

    @Query(
        "SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY parentIndexNumber ASC, indexNumber ASC"
    )
    fun getEpisodesByShowId(seriesId: UUID): List<JollyfinEpisodeDto>

    @Query("SELECT * FROM episodes WHERE seasonId = :seasonId ORDER BY indexNumber ASC")
    fun getEpisodesBySeasonId(seasonId: UUID): List<JollyfinEpisodeDto>

    @Query(
        "SELECT * FROM episodes WHERE serverId = :serverId ORDER BY seriesName ASC, parentIndexNumber ASC, indexNumber ASC"
    )
    fun getEpisodesByServerId(serverId: String): List<JollyfinEpisodeDto>

    @Query(
        "SELECT episodes.id, episodes.serverId, episodes.seasonId, episodes.seriesId, episodes.name, episodes.seriesName, episodes.overview, episodes.indexNumber, episodes.indexNumberEnd, episodes.parentIndexNumber, episodes.runtimeTicks, episodes.premiereDate, episodes.communityRating, episodes.chapters FROM episodes INNER JOIN userdata ON episodes.id = userdata.itemId WHERE serverId = :serverId AND playbackPositionTicks > 0 ORDER BY episodes.parentIndexNumber ASC, episodes.indexNumber ASC"
    )
    fun getEpisodeResumeItems(serverId: String): List<JollyfinEpisodeDto>

    @Query("DELETE FROM episodes WHERE id = :id") fun deleteEpisode(id: UUID)

    @Query("DELETE FROM episodes WHERE seasonId = :seasonId")
    fun deleteEpisodesBySeasonId(seasonId: UUID)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertSegment(segment: JollyfinSegmentDto)

    @Query("SELECT * FROM segments WHERE itemId = :itemId")
    fun getSegments(itemId: UUID): List<JollyfinSegmentDto>

    @Query("SELECT * FROM seasons") fun getSeasons(): List<JollyfinSeasonDto>

    @Query("SELECT * FROM episodes") fun getEpisodes(): List<JollyfinEpisodeDto>

    @Query("SELECT * FROM userdata WHERE itemId = :itemId AND userId = :userId")
    fun getUserData(itemId: UUID, userId: UUID): JollyfinUserDataDto?

    // Batch variant of getUserData, see getSourcesForItems.
    @Query("SELECT * FROM userdata WHERE itemId IN (:itemIds) AND userId = :userId")
    fun getUserDataForItems(itemIds: List<UUID>, userId: UUID): List<JollyfinUserDataDto>

    @Transaction
    fun getUserDataOrCreateNew(itemId: UUID, userId: UUID): JollyfinUserDataDto {
        var userData = getUserData(itemId, userId)

        // Create user data when there is none
        if (userData == null) {
            userData =
                JollyfinUserDataDto(
                    userId = userId,
                    itemId = itemId,
                    played = false,
                    favorite = false,
                    playbackPositionTicks = 0L,
                )
            insertUserData(userData)
        }

        return userData
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertUserData(userData: JollyfinUserDataDto)

    @Query("DELETE FROM userdata WHERE itemId = :itemId") fun deleteUserData(itemId: UUID)

    @Query("SELECT * FROM userdata WHERE userId = :userId AND itemId = :itemId AND toBeSynced = 1")
    fun getUserDataToBeSynced(userId: UUID, itemId: UUID): JollyfinUserDataDto?

    @Query(
        "UPDATE userdata SET toBeSynced = :toBeSynced WHERE itemId = :itemId AND userId = :userId"
    )
    fun setUserDataToBeSynced(userId: UUID, itemId: UUID, toBeSynced: Boolean)

    @Query("SELECT * FROM movies WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    fun searchMovies(serverId: String, name: String): List<JollyfinMovieDto>

    @Query("SELECT * FROM shows WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    fun searchShows(serverId: String, name: String): List<JollyfinShowDto>

    @Query("SELECT * FROM episodes WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    fun searchEpisodes(serverId: String, name: String): List<JollyfinEpisodeDto>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTrickplayInfo(trickplayInfoDto: JollyfinTrickplayInfoDto)

    @Query("SELECT * FROM trickplayInfos WHERE sourceId = :sourceId")
    fun getTrickplayInfo(sourceId: String): JollyfinTrickplayInfoDto?

    // Batch variant of getTrickplayInfo, see getSourcesForItems.
    @Query("SELECT * FROM trickplayInfos WHERE sourceId IN (:sourceIds)")
    fun getTrickplayInfoForSources(sourceIds: List<String>): List<JollyfinTrickplayInfoDto>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAutoDownloadRule(rule: AutoDownloadRuleDto): Long

    @Update fun updateAutoDownloadRule(rule: AutoDownloadRuleDto)

    @Query(
        "SELECT * FROM autoDownloadRules WHERE serverId = :serverId AND userId = :userId AND seriesId = :seriesId AND seasonId IS NULL"
    )
    fun getShowAutoDownloadRule(
        serverId: String,
        userId: UUID,
        seriesId: UUID,
    ): AutoDownloadRuleDto?

    @Query(
        "SELECT * FROM autoDownloadRules WHERE serverId = :serverId AND userId = :userId AND seriesId = :seriesId AND seasonId = :seasonId"
    )
    fun getSeasonAutoDownloadRule(
        serverId: String,
        userId: UUID,
        seriesId: UUID,
        seasonId: UUID,
    ): AutoDownloadRuleDto?

    @Query(
        "SELECT * FROM autoDownloadRules WHERE serverId = :serverId AND userId = :userId ORDER BY createdAt DESC"
    )
    fun getAutoDownloadRules(serverId: String, userId: UUID): List<AutoDownloadRuleDto>

    @Query(
        "SELECT * FROM autoDownloadRules WHERE serverId = :serverId AND userId = :userId AND seriesId = :seriesId"
    )
    fun getAutoDownloadRulesForShow(
        serverId: String,
        userId: UUID,
        seriesId: UUID,
    ): List<AutoDownloadRuleDto>

    @Query(
        "SELECT * FROM autoDownloadRules WHERE serverId = :serverId AND userId = :userId AND enabled = 1"
    )
    fun getEnabledAutoDownloadRules(serverId: String, userId: UUID): List<AutoDownloadRuleDto>

    @Query("UPDATE autoDownloadRules SET enabled = :enabled WHERE id = :id")
    fun setAutoDownloadRuleEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE autoDownloadRules SET onlyNewEpisodes = :onlyNewEpisodes WHERE id = :id")
    fun setAutoDownloadRuleOnlyNewEpisodes(id: Long, onlyNewEpisodes: Boolean)

    @Query("UPDATE autoDownloadRules SET onlyUnwatched = :onlyUnwatched WHERE id = :id")
    fun setAutoDownloadRuleOnlyUnwatched(id: Long, onlyUnwatched: Boolean)

    @Query("DELETE FROM autoDownloadRules WHERE id = :id") fun deleteAutoDownloadRule(id: Long)

    @Query(
        "DELETE FROM autoDownloadRules WHERE serverId = :serverId AND userId = :userId AND seriesId = :seriesId AND seasonId IS NOT NULL"
    )
    fun deleteSeasonAutoDownloadRulesForShow(serverId: String, userId: UUID, seriesId: UUID)

    @Query(
        "DELETE FROM autoDownloadRules WHERE serverId = :serverId AND userId = :userId AND seriesId = :seriesId"
    )
    fun deleteAutoDownloadRulesForShow(serverId: String, userId: UUID, seriesId: UUID)

    @Query("DELETE FROM autoDownloadRules WHERE serverId = :serverId AND userId = :userId")
    fun deleteAllAutoDownloadRules(serverId: String, userId: UUID)

    // Only touches season-specific rows - the show-level (seasonId IS NULL) "auto-download future
    // seasons" row is managed independently and must not be dropped just because the set of
    // explicitly-selected seasons changed.
    @Query(
        "DELETE FROM autoDownloadRules WHERE serverId = :serverId AND userId = :userId AND seriesId = :seriesId AND seasonId IS NOT NULL AND seasonId NOT IN (:keepSeasonIds)"
    )
    fun deleteSeasonAutoDownloadRulesForShowExceptSeasons(
        serverId: String,
        userId: UUID,
        seriesId: UUID,
        keepSeasonIds: List<UUID>,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPendingDownloadRequest(request: PendingDownloadRequestDto): Long

    @Query("DELETE FROM pending_download_requests WHERE id = :id")
    fun deletePendingDownloadRequest(id: Long)

    // episodeNumber is nullable (null = whole-season request) - the "OR (... IS NULL AND ... IS
    // NULL)" clause is needed because SQL's `= NULL` never matches, even when both sides are NULL.
    @Query(
        "SELECT * FROM pending_download_requests WHERE serverId = :serverId AND userId = :userId AND seriesId = :seriesId AND seasonNumber = :seasonNumber AND (episodeNumber = :episodeNumber OR (episodeNumber IS NULL AND :episodeNumber IS NULL))"
    )
    fun getPendingDownloadRequest(
        serverId: String,
        userId: UUID,
        seriesId: UUID,
        seasonNumber: Int,
        episodeNumber: Int?,
    ): PendingDownloadRequestDto?

    @Query(
        "SELECT * FROM pending_download_requests WHERE serverId = :serverId AND userId = :userId AND seriesId = :seriesId"
    )
    fun getPendingDownloadRequestsForSeries(
        serverId: String,
        userId: UUID,
        seriesId: UUID,
    ): List<PendingDownloadRequestDto>

    @Query(
        "SELECT * FROM pending_download_requests WHERE serverId = :serverId AND userId = :userId"
    )
    fun getPendingDownloadRequests(serverId: String, userId: UUID): List<PendingDownloadRequestDto>

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertProfile(profile: Profile)

    @Update fun updateProfile(profile: Profile)

    @Query("DELETE FROM profiles WHERE id = :id") fun deleteProfile(id: UUID)

    @Query("SELECT * FROM profiles WHERE id = :id") fun getProfile(id: UUID): Profile?

    @Query("SELECT * FROM profiles WHERE isMain = 1 LIMIT 1") fun getMainProfile(): Profile?

    @Query("SELECT * FROM profiles WHERE userId = :userId")
    fun getProfilesForUser(userId: UUID): List<Profile>

    @Query("SELECT * FROM profiles") fun getAllProfiles(): List<Profile>

    @Query("SELECT * FROM users") fun getAllUsers(): List<User>

    @Query(
        "SELECT profiles.*, users.name AS userName, servers.id AS serverId, servers.name AS serverName FROM profiles JOIN users ON users.id = profiles.userId JOIN servers ON servers.id = users.serverId ORDER BY users.name ASC"
    )
    fun getProfilesWithUserAndServer(): List<ProfileWithUserAndServer>

    @Query(
        "SELECT profiles.*, users.name AS userName, servers.id AS serverId, servers.name AS serverName FROM profiles JOIN users ON users.id = profiles.userId JOIN servers ON servers.id = users.serverId WHERE profiles.id = :id"
    )
    fun getProfileWithUserAndServer(id: UUID): ProfileWithUserAndServer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPvrServiceConfig(config: PvrServiceConfig)

    @Update fun updatePvrServiceConfig(config: PvrServiceConfig)

    @Query("DELETE FROM pvrServiceConfigs WHERE id = :id") fun deletePvrServiceConfig(id: UUID)

    @Query("SELECT * FROM pvrServiceConfigs WHERE id = :id")
    fun getPvrServiceConfig(id: UUID): PvrServiceConfig?

    // Used by ProfileMigrationRunner's one-time upgrade backfill: writes every newly-computed
    // Profile/PvrServiceConfig row atomically, so a crash mid-way leaves nothing behind and a
    // retry on next launch starts clean instead of duplicating rows.
    @Transaction
    fun backfillProfiles(profiles: List<Profile>, pvrServiceConfigs: List<PvrServiceConfig>) {
        pvrServiceConfigs.forEach { insertPvrServiceConfig(it) }
        profiles.forEach { insertProfile(it) }
    }
}
