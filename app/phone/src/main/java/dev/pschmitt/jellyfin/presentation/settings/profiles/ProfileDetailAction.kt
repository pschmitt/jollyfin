package dev.pschmitt.jellyfin.presentation.settings.profiles

import dev.pschmitt.jellyfin.api.pvr.PvrService
import java.util.UUID

sealed interface ProfileDetailAction {
    data object OnBackClick : ProfileDetailAction

    data class OnRenameConfirmed(val name: String) : ProfileDetailAction

    data object OnSetAsMainClick : ProfileDetailAction

    data object OnDeleteClick : ProfileDetailAction

    data class OnToggleInherit(val service: PvrService, val inherit: Boolean) : ProfileDetailAction

    data class OnEnabledChanged(val service: PvrService, val enabled: Boolean) : ProfileDetailAction

    data class OnBaseUrlChanged(val service: PvrService, val baseUrl: String) : ProfileDetailAction

    data class OnApiKeyChanged(val service: PvrService, val apiKey: String) : ProfileDetailAction

    data class OnAdvancedSettingsChanged(
        val service: PvrService,
        val headers: String,
        val basicAuthUsername: String,
        val basicAuthPassword: String,
    ) : ProfileDetailAction

    data class OnTestConnectionClick(val service: PvrService) : ProfileDetailAction

    /** Tapping an existing address makes it this server's current address. */
    data class OnAddressSelected(val addressId: UUID) : ProfileDetailAction

    /** Adds (or, if the server already exists, attaches) [address] and makes it current. */
    data class OnAddAddressClick(val address: String) : ProfileDetailAction

    /** Reassigns this profile to an existing Jellyfin login on the same server. */
    data class OnUserSelected(val userId: UUID) : ProfileDetailAction

    /** Logs in as a brand-new Jellyfin user on this profile's server and reassigns to it. */
    data class OnLoginClick(val username: String, val password: String) : ProfileDetailAction

    data object OnQuickConnectClick : ProfileDetailAction

    /** Triggers Jellyfin's "Scan All Libraries" task on this profile's server. */
    data object OnScanLibraryClick : ProfileDetailAction
}
