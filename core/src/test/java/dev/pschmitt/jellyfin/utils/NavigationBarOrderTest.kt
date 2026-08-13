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
}
