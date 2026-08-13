package dev.pschmitt.jellyfin.settings.domain

import android.net.Uri

/** Metadata needed to render a pinned media destination without a network request. */
data class NavigationBarPinnedItem(
    val id: String,
    val type: String,
    val title: String,
    val imageUri: String? = null,
    val showImage: Boolean = true,
) {
    val key: String
        get() = "item:$type:$id"
}

fun navigationBarPinnedItemsToString(items: List<NavigationBarPinnedItem>): String =
    items.joinToString("\n") {
        listOf(
                it.id,
                it.type,
                Uri.encode(it.title),
                it.imageUri?.let { image -> Uri.encode(image) }.orEmpty(),
                it.showImage.toString(),
            )
            .joinToString("|")
    }

fun navigationBarPinnedItemsFromString(value: String?): List<NavigationBarPinnedItem> =
    value.orEmpty().lines().mapNotNull { line ->
        val parts = line.split('|', limit = 5)
        if (parts.size < 3 || parts[0].isBlank() || parts[1].isBlank()) {
            null
        } else {
            NavigationBarPinnedItem(
                id = parts[0],
                type = parts[1],
                title = Uri.decode(parts[2]),
                imageUri = parts.getOrNull(3)?.takeIf(String::isNotBlank)?.let(Uri::decode),
                showImage = parts.getOrNull(4)?.toBooleanStrictOrNull() ?: true,
            )
        }
    }

/** A compact default label for a pinned destination; users can change it later. */
fun defaultNavigationBarLabel(title: String): String {
    val words = Regex("[\\p{L}\\p{N}]+").findAll(title).map { it.value }.toList()
    if (words.size < 2) return title
    return words.joinToString("") { it.first().uppercase() }.take(6)
}
