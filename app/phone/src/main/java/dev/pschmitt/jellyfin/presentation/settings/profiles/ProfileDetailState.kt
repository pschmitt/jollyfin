package dev.pschmitt.jellyfin.presentation.settings.profiles

import dev.pschmitt.jellyfin.models.ServerAddress
import dev.pschmitt.jellyfin.models.UiText
import dev.pschmitt.jellyfin.models.User
import dev.pschmitt.jellyfin.presentation.settings.pvr.PvrTestState
import java.util.UUID

/** One Sonarr/Radarr/Seerr card's worth of state - either an inherited preview or an override. */
data class PvrSectionState(
    val inheriting: Boolean = true,
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    // The API key as it was resolved when this section last loaded (or was last successfully
    // tested/saved) - snapshotted separately from [apiKey] so "Test connection" and the enabled
    // check can fall back to the last-known-good key even if the visible field has been cleared
    // (e.g. the user is mid-edit, about to paste a new one).
    val storedApiKey: String = "",
    val httpHeaders: String = "",
    val basicAuthUsername: String = "",
    val basicAuthPassword: String = "",
    val testState: PvrTestState = PvrTestState.Idle,
)

data class ProfileDetailState(
    val loading: Boolean = true,
    val name: String = "",
    val isMain: Boolean = false,
    val sonarr: PvrSectionState = PvrSectionState(),
    val radarr: PvrSectionState = PvrSectionState(),
    val seerr: PvrSectionState = PvrSectionState(),
    // Set once the profile has been deleted - the screen observes this to navigate back.
    val deleted: Boolean = false,
    // Server/address section.
    val serverId: String = "",
    val serverName: String = "",
    val addresses: List<ServerAddress> = emptyList(),
    val currentAddressId: UUID? = null,
    val addressOperationInProgress: Boolean = false,
    val addAddressError: UiText? = null,
    // Jellyfin user section.
    val currentUserId: UUID? = null,
    val currentUserName: String = "",
    val otherUsers: List<User> = emptyList(),
    val userOperationInProgress: Boolean = false,
    val loginError: UiText? = null,
    val quickConnectEnabled: Boolean = false,
    val quickConnectCode: String? = null,
    // Scan-library section. isAdministrator is only ever resolved (and the action only ever
    // allowed) while this profile is the active one - see refreshServerAndUserSection's comment.
    val isActiveProfile: Boolean = false,
    val isAdministrator: Boolean = false,
    val scanningLibrary: Boolean = false,
    val scanLibraryMessage: UiText? = null,
)
