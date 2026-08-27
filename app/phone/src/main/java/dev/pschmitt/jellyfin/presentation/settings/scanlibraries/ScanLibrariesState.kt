package dev.pschmitt.jellyfin.presentation.settings.scanlibraries

data class ScanLibrariesState(
    val loading: Boolean = true,
    val isAdministrator: Boolean = false,
    val scanning: Boolean = false,
)

sealed interface ScanLibrariesAction {
    data object OnBackClick : ScanLibrariesAction

    data object OnScanClick : ScanLibrariesAction
}
