package dev.pschmitt.jellyfin.setup.domain

import dev.pschmitt.jellyfin.models.Server
import dev.pschmitt.jellyfin.models.ServerWithAddresses
import dev.pschmitt.jellyfin.models.User
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo

interface SetupRepository {
    fun discoverServers(): Flow<ServerDiscoveryInfo>

    suspend fun getServers(): List<ServerWithAddresses>

    /** Returns the cached public Jellyfin version, fetching it once if no cached value exists. */
    suspend fun getServerVersion(serverId: String): String?

    suspend fun getCurrentServer(): Server?

    suspend fun deleteServer(serverId: String)

    suspend fun getIsQuickConnectEnabled(): Boolean

    suspend fun initiateQuickConnect(): QuickConnectResult

    suspend fun getQuickConnectState(secret: String): QuickConnectResult

    suspend fun setCurrentServer(serverId: String)

    suspend fun addServer(address: String): Server

    suspend fun loadDisclaimer(): String?

    suspend fun login(username: String, password: String)

    suspend fun loginWithSecret(secret: String)

    suspend fun getUsers(serverId: String): List<User>

    suspend fun getPublicUsers(serverId: String): List<User>

    suspend fun getCurrentUser(): User?

    suspend fun deleteUser(userId: UUID)

    suspend fun setCurrentUser(userId: UUID)

    suspend fun setCurrentAddress(addressId: UUID)
}
