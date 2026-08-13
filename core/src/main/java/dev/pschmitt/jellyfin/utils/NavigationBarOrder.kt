package dev.pschmitt.jellyfin.utils

/** Stable keys used to persist the user's navigation-bar order. */
object NavigationBarItemKeys {
    const val HOME = "home"
    const val MEDIA = "media"
    const val DOWNLOADS = "downloads"
    const val CALENDAR = "calendar"
    const val FAVORITES = "favorites"
    const val NEXT_UP = "next_up"
    const val SETTINGS = "settings"

    fun library(id: String): String = "library:$id"
}

fun navigationBarOrderToString(order: List<String>): String = order.joinToString(",")

fun navigationBarOrderFromString(value: String?): List<String> =
    value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).distinct()

/**
 * Applies a persisted order to the currently available items. Items that disappeared are dropped,
 * and newly available items are appended in their natural/default order. This means an empty
 * preference keeps the existing default navbar layout without needing a migration value.
 */
fun resolveNavigationBarOrder(
    natural: List<String>,
    persisted: List<String>,
    hidden: Set<String>,
): List<String> {
    val available = natural.toSet()
    val ordered = buildList {
        addAll(persisted.filter { it in available })
        addAll(natural.filter { it !in persisted })
    }
    return ordered.filterNot { it in hidden }.distinct()
}
