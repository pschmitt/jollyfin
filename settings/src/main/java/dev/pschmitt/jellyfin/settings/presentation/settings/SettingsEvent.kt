package dev.pschmitt.jellyfin.settings.presentation.settings

import android.content.Intent

sealed interface SettingsEvent {
    data object NavigateToUsers : SettingsEvent

    data object NavigateToServers : SettingsEvent

    data object NavigateToBackupSettings : SettingsEvent

    data object NavigateToQrExport : SettingsEvent

    data object NavigateToConnections : SettingsEvent

    data object NavigateToProfiles : SettingsEvent

    data class NavigateToProfileDetail(val profileId: String) : SettingsEvent

    data object NavigateToHomeLayout : SettingsEvent

    data object NavigateToNavigationBar : SettingsEvent

    data object NavigateToAbout : SettingsEvent

    data object NavigateToAutoDownloadRules : SettingsEvent

    data object NavigateToLocalAccess : SettingsEvent

    data object NavigateToScanLibraries : SettingsEvent

    data class NavigateToSettings(val indexes: IntArray) : SettingsEvent

    data class NavigateToSettingsFileEdit(val filePath: String) : SettingsEvent

    data class UpdateTheme(val theme: String) : SettingsEvent

    data class LaunchIntent(val intent: Intent) : SettingsEvent

    data object RestartActivity : SettingsEvent

    data class DownloadLocationChanged(val from: String, val to: String) : SettingsEvent
}
