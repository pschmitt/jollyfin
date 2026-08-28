package dev.pschmitt.jellyfin.setup.presentation.profiles

import dev.pschmitt.jellyfin.models.ProfileWithUserAndServer
import java.util.UUID

data class ProfilesState(
    val profiles: List<ProfileWithUserAndServer> = emptyList(),
    val currentProfileId: UUID? = null,
    // serverId -> that server's current base URL, used to build each profile's Jellyfin user
    // avatar image URL (/users/{id}/Images/Primary). Resolved separately from [profiles] itself
    // since ProfileWithUserAndServer doesn't carry an address.
    val serverBaseUrls: Map<String, String> = emptyMap(),
) {
    val currentProfile: ProfileWithUserAndServer?
        get() = profiles.firstOrNull { it.profile.id == currentProfileId }
}
