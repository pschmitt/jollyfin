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

    private const val WEB_UI_PREFIX = "webui:"

    /** A Sonarr/Radarr/Seerr web UI tab, keyed by the lowercase service name. */
    fun webUi(service: String): String = "$WEB_UI_PREFIX${service.lowercase()}"

    /**
     * Tabs that stay hidden until the user explicitly enables them (see
     * `AppPreferences.navigationBarOptInItems`), rather than appearing for everyone as soon as they
     * become available - e.g. the web UI tabs, which would otherwise show up for every user with
     * Sonarr/Radarr/Seerr configured.
     */
    fun isHiddenByDefault(key: String): Boolean = key.startsWith(WEB_UI_PREFIX)
}

/**
 * The navbar keys to hide: everything the user explicitly hid, plus any hidden-by-default item
 * among [natural] they haven't opted into.
 */
fun effectiveNavigationBarHidden(
    natural: Collection<String>,
    hidden: Collection<String>,
    optedIn: Collection<String>,
): Set<String> =
    hidden.toSet() +
        natural.filter { NavigationBarItemKeys.isHiddenByDefault(it) && it !in optedIn }

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
