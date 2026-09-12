package dev.pschmitt.jellyfin.film.presentation.home

import dev.pschmitt.jellyfin.models.JollyfinCollection
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.SeerrSearchItem

sealed interface HomeAction {
    data class OnItemClick(val item: JollyfinItem) : HomeAction

    /** A discovery-row item - not in the library, opens the Seerr media detail view. */
    data class OnSeerrItemClick(val item: SeerrSearchItem) : HomeAction

    data class OnLibraryClick(val library: JollyfinCollection) : HomeAction

    /** The "Pending downloads" header's arrow, same pattern as a library shelf's own. */
    data object OnDownloadsClick : HomeAction

    data object OnRetryClick : HomeAction

    data object OnEnableOfflineMode : HomeAction

    data object OnSettingsClick : HomeAction

    data object OnManageServers : HomeAction

    /** Long-press drag reorder of a Home section, straight from the Home screen itself. */
    data class OnReorderSections(val fromIndex: Int, val toIndex: Int) : HomeAction
}
