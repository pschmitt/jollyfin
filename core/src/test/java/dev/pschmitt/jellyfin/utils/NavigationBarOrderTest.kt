package dev.pschmitt.jellyfin.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationBarOrderTest {
    @Test
    fun emptyPreferenceKeepsNaturalOrderAndHidesOptionalItems() {
        assertEquals(
            listOf("home", "downloads"),
            resolveNavigationBarOrder(
                natural = listOf("home", "downloads", "favorites"),
                persisted = emptyList(),
                hidden = setOf("favorites"),
            ),
        )
    }

    @Test
    fun persistedOrderDropsMissingItemsAndAppendsNewItems() {
        assertEquals(
            listOf("downloads", "home", "calendar"),
            resolveNavigationBarOrder(
                natural = listOf("home", "downloads", "calendar"),
                persisted = listOf("downloads", "removed", "home"),
                hidden = emptySet(),
            ),
        )
    }

    @Test
    fun webUiTabsAreHiddenUntilOptedIn() {
        val natural = listOf("home", "webui:sonarr", "webui:radarr")
        assertEquals(
            setOf("webui:sonarr", "webui:radarr"),
            effectiveNavigationBarHidden(natural, hidden = emptyList(), optedIn = emptyList()),
        )
        assertEquals(
            setOf("webui:radarr"),
            effectiveNavigationBarHidden(
                natural,
                hidden = emptyList(),
                optedIn = listOf("webui:sonarr"),
            ),
        )
        assertEquals(
            setOf("favorites", "webui:radarr"),
            effectiveNavigationBarHidden(
                natural,
                hidden = listOf("favorites"),
                optedIn = listOf("webui:sonarr"),
            ),
        )
    }
}
