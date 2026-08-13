package dev.pschmitt.jellyfin.presentation.film

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class MediaItemsKind {
    FAVORITES,
    NEXT_UP,
}

data class MediaItemsState(
    val items: List<JollyfinItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: Exception? = null,
)

@HiltViewModel
class MediaItemsViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow(MediaItemsState())
    val state = _state.asStateFlow()

    fun load(kind: MediaItemsKind) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                val items =
                    when (kind) {
                        MediaItemsKind.FAVORITES -> repository.getFavoriteItems()
                        MediaItemsKind.NEXT_UP -> repository.getNextUp()
                    }
                _state.emit(_state.value.copy(items = items, isLoading = false))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(isLoading = false, error = e))
            }
        }
    }
}
