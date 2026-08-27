package dev.pschmitt.jellyfin.presentation.settings.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.models.CollectionType
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.JollyfinShow
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import dev.pschmitt.jellyfin.settings.R as SettingsR
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.settings.domain.NavigationBarPinnedItem
import dev.pschmitt.jellyfin.settings.domain.defaultNavigationBarLabel
import dev.pschmitt.jellyfin.settings.domain.navigationBarPinnedItemsFromString
import dev.pschmitt.jellyfin.settings.domain.navigationBarPinnedItemsToString
import dev.pschmitt.jellyfin.utils.NavigationBarItemKeys
import dev.pschmitt.jellyfin.utils.navigationBarOrderFromString
import dev.pschmitt.jellyfin.utils.navigationBarOrderToString
import dev.pschmitt.jellyfin.utils.resolveNavigationBarOrder
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NavigationBarRow(
    val key: String,
    @StringRes val title: Int = 0,
    val titleText: String? = null,
    @DrawableRes val icon: Int,
    val imageUri: String? = null,
    val showImage: Boolean = false,
)

data class NavigationBarSettingsState(
    val rows: List<NavigationBarRow> = emptyList(),
    val hiddenRows: List<NavigationBarRow> = emptyList(),
    val searchResults: List<JollyfinItem> = emptyList(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class NavigationBarSettingsViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(NavigationBarSettingsState())
    val state = _state.asStateFlow()

    /** Resolves persisted keys against the currently available tabs in NavigationRoot. */
    fun resolveVisibleItems(
        items: List<dev.pschmitt.jellyfin.TabBarItem>
    ): List<dev.pschmitt.jellyfin.TabBarItem> {
        val keys =
            resolveNavigationBarOrder(
                natural = items.map { it.key },
                persisted =
                    navigationBarOrderFromString(
                        appPreferences.getValue(appPreferences.navigationBarOrder)
                    ),
                hidden = currentHidden().toSet(),
            )
        val byKey = items.associateBy { it.key }
        return keys.mapNotNull(byKey::get)
    }

    fun pinnedItems(): List<NavigationBarPinnedItem> =
        navigationBarPinnedItemsFromString(
            appPreferences.getValue(appPreferences.navigationBarPinnedItems)
        )

    private var cachedRows: LinkedHashMap<String, NavigationBarRow> = linkedMapOf()
    private var searchJob: Job? = null
    private var artworkRefreshJob: Job? = null

    private val _pinnedItemsVersion = MutableStateFlow(0)
    val pinnedItemsVersion = _pinnedItemsVersion.asStateFlow()

    fun load() {
        viewModelScope.launch {
            refreshPinnedArtwork().join()
            _state.emit(_state.value.copy(isLoading = true))

            val libraries = runCatching { repository.getLibraries() }.getOrDefault(emptyList())
            val rows = LinkedHashMap<String, NavigationBarRow>()
            fun add(row: NavigationBarRow) {
                rows[row.key] = row
            }

            add(
                NavigationBarRow(
                    NavigationBarItemKeys.HOME,
                    CoreR.string.title_home,
                    icon = CoreR.drawable.ic_home,
                )
            )
            if (
                libraries.any {
                    it.type == CollectionType.Movies || it.type == CollectionType.TvShows
                }
            ) {
                add(
                    NavigationBarRow(
                        NavigationBarItemKeys.MEDIA,
                        CoreR.string.title_media_tab,
                        icon = CoreR.drawable.ic_library,
                    )
                )
            }
            libraries
                .filterNot { it.type == CollectionType.Movies || it.type == CollectionType.TvShows }
                .forEach { library ->
                    add(
                        NavigationBarRow(
                            key = NavigationBarItemKeys.library(library.id.toString()),
                            titleText = library.name,
                            icon = CoreR.drawable.ic_library,
                        )
                    )
                }
            add(
                NavigationBarRow(
                    NavigationBarItemKeys.DOWNLOADS,
                    CoreR.string.title_download,
                    icon = CoreR.drawable.ic_download,
                )
            )
            add(
                NavigationBarRow(
                    NavigationBarItemKeys.CALENDAR,
                    CoreR.string.title_calendar,
                    icon = CoreR.drawable.ic_calendar,
                )
            )
            add(
                NavigationBarRow(
                    NavigationBarItemKeys.FAVORITES,
                    CoreR.string.title_favorite,
                    icon = SettingsR.drawable.ic_heart,
                )
            )
            add(
                NavigationBarRow(
                    NavigationBarItemKeys.NEXT_UP,
                    CoreR.string.next_up,
                    icon = SettingsR.drawable.ic_skip_forward,
                )
            )
            add(
                NavigationBarRow(
                    NavigationBarItemKeys.SETTINGS,
                    CoreR.string.title_settings,
                    icon = SettingsR.drawable.ic_settings,
                )
            )
            pinnedItems().forEach { item ->
                add(
                    NavigationBarRow(
                        item.key,
                        titleText = item.title,
                        icon = CoreR.drawable.ic_star,
                        imageUri = item.imageUri,
                        showImage = item.showImage,
                    )
                )
            }

            cachedRows = rows
            recomputeRows()
            _state.emit(_state.value.copy(isLoading = false))
        }
    }

    /** Backfills artwork for pins written before image URIs were persisted. */
    fun refreshPinnedArtwork(): Job {
        artworkRefreshJob
            ?.takeIf { it.isActive }
            ?.let {
                return it
            }
        return viewModelScope
            .launch {
                val current = pinnedItems()
                val refreshed = current.map { pinned ->
                    if (!pinned.imageUri.isNullOrBlank()) return@map pinned
                    val item =
                        runCatching {
                                repository.getItem(UUID.fromString(pinned.id))
                            }
                            .getOrNull()
                    val resolved = item?.let(::pinnedItemFor)
                    if (resolved?.type == pinned.type && !resolved.imageUri.isNullOrBlank()) {
                        pinned.copy(imageUri = resolved.imageUri)
                    } else {
                        pinned
                    }
                }
                if (refreshed != current) savePinnedItems(refreshed)
            }
            .also { artworkRefreshJob = it }
    }

    fun move(from: Int, to: Int) {
        val rows = _state.value.rows
        if (from !in rows.indices || to !in rows.indices) return
        val reordered = rows.toMutableList().apply { add(to, removeAt(from)) }
        appPreferences.setValue(
            appPreferences.navigationBarOrder,
            navigationBarOrderToString(reordered.map { it.key }),
        )
        recomputeRows()
    }

    fun hide(key: String) {
        if (key == NavigationBarItemKeys.HOME) return
        val hidden = currentHidden().toMutableList()
        if (key !in hidden) hidden.add(key)
        appPreferences.setValue(
            appPreferences.navigationBarHiddenItems,
            navigationBarOrderToString(hidden),
        )
        recomputeRows()
    }

    fun restore(key: String) {
        val hidden = currentHidden().toMutableList().apply { remove(key) }
        appPreferences.setValue(
            appPreferences.navigationBarHiddenItems,
            navigationBarOrderToString(hidden),
        )
        recomputeRows()
    }

    fun reset() {
        appPreferences.setValue(appPreferences.navigationBarOrder, null)
        val pinnedKeys = pinnedItems().map { it.key }
        appPreferences.setValue(
            appPreferences.navigationBarHiddenItems,
            navigationBarOrderToString(
                listOf(
                    NavigationBarItemKeys.FAVORITES,
                    NavigationBarItemKeys.NEXT_UP,
                    NavigationBarItemKeys.SETTINGS,
                ) + pinnedKeys
            ),
        )
        recomputeRows()
    }

    fun search(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val trimmedQuery = query.trim()
            if (trimmedQuery.isBlank()) {
                _state.emit(_state.value.copy(searchResults = emptyList(), isLoading = false))
                return@launch
            }

            _state.emit(_state.value.copy(searchResults = emptyList(), isLoading = true))
            val results =
                runCatching {
                        repository.getSearchItems(trimmedQuery)
                    }
                    .getOrDefault(emptyList())
                    .filter {
                        it is JollyfinMovie ||
                            it is JollyfinShow ||
                            it is JollyfinEpisode ||
                            it is JollyfinSeason
                    }
            _state.emit(_state.value.copy(searchResults = results, isLoading = false))
        }
    }

    fun updatePinnedItem(key: String, title: String, showImage: Boolean) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return
        val pinned = pinnedItems().toMutableList()
        val index = pinned.indexOfFirst { it.key == key }
        if (index < 0) return
        pinned[index] = pinned[index].copy(title = trimmedTitle, showImage = showImage)
        savePinnedItems(pinned)
        cachedRows[key]?.let {
            cachedRows[key] = it.copy(titleText = trimmedTitle, showImage = showImage)
        }
        recomputeRows()
    }

    private fun pinnedItemFor(item: JollyfinItem): NavigationBarPinnedItem {
        val type =
            when (item) {
                is JollyfinMovie -> "movie"
                is JollyfinShow -> "show"
                is JollyfinEpisode -> "episode"
                is JollyfinSeason -> "season"
                else -> error("Unsupported pinned item type: ${item::class.simpleName}")
            }
        return NavigationBarPinnedItem(
            id = item.id.toString(),
            type = type,
            title = defaultNavigationBarLabel(item.name),
            imageUri = (item.images.primary ?: item.images.showPrimary)?.toString(),
            showImage = true,
        )
    }

    fun add(item: JollyfinItem) {
        if (
            item !is JollyfinMovie &&
                item !is JollyfinShow &&
                item !is JollyfinEpisode &&
                item !is JollyfinSeason
        ) {
            return
        }
        val pinned = pinnedItems().toMutableList()
        val newItem = pinnedItemFor(item)
        if (pinned.none { it.key == newItem.key }) pinned.add(newItem)
        savePinnedItems(pinned)
        val hidden = currentHidden().toMutableList().apply { remove(newItem.key) }
        appPreferences.setValue(
            appPreferences.navigationBarHiddenItems,
            navigationBarOrderToString(hidden),
        )
        cachedRows[newItem.key] =
            NavigationBarRow(
                newItem.key,
                titleText = newItem.title,
                icon = CoreR.drawable.ic_star,
                imageUri = newItem.imageUri,
                showImage = newItem.showImage,
            )
        _state.value = _state.value.copy(searchResults = emptyList())
        recomputeRows()
    }

    private fun savePinnedItems(items: List<NavigationBarPinnedItem>) {
        appPreferences.setValue(
            appPreferences.navigationBarPinnedItems,
            navigationBarPinnedItemsToString(items),
        )
        _pinnedItemsVersion.update { it + 1 }
    }

    private fun currentHidden(): List<String> =
        navigationBarOrderFromString(
            appPreferences.getValue(appPreferences.navigationBarHiddenItems)
        )

    private fun recomputeRows() {
        val natural = cachedRows.keys.toList()
        val hidden = currentHidden().toSet()
        val order =
            resolveNavigationBarOrder(
                natural,
                navigationBarOrderFromString(
                    appPreferences.getValue(appPreferences.navigationBarOrder)
                ),
                hidden,
            )
        _state.value =
            _state.value.copy(
                rows = order.mapNotNull(cachedRows::get),
                hiddenRows = natural.filter { it in hidden }.mapNotNull(cachedRows::get),
            )
    }
}
