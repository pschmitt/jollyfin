package dev.pschmitt.jellyfin.presentation.pvr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.pvr.PvrWebUiLinks
import dev.pschmitt.jellyfin.pvr.PvrWebUiTarget
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PvrWebUiState(
    val isLoading: Boolean = true,
    // The service's configured base URL - the in-app view stays on its origin. Null once loading
    // finished means the service isn't available (disabled or no base URL).
    val baseUrl: String? = null,
    val initialUrl: String? = null,
)

@HiltViewModel
class PvrWebUiViewModel @Inject constructor(private val links: PvrWebUiLinks) : ViewModel() {
    private val _state = MutableStateFlow(PvrWebUiState())
    val state = _state.asStateFlow()

    private var loaded = false

    fun load(service: PvrService, target: PvrWebUiTarget, isTab: Boolean) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val baseUrl = links.baseUrl(service)
            val initialUrl =
                when {
                    baseUrl == null -> null
                    // A tab tap pops back to the tab's root, so remember where its web UI was
                    // to not throw the user back to the start page on every tab switch.
                    isTab -> lastTabUrls[service]?.takeIf { it.first == baseUrl }?.second
                    else -> null
                } ?: links.resolve(service, target)
            _state.value =
                PvrWebUiState(isLoading = false, baseUrl = baseUrl, initialUrl = initialUrl)
        }
    }

    fun onUrlVisited(service: PvrService, isTab: Boolean, url: String) {
        val baseUrl = _state.value.baseUrl ?: return
        if (isTab) lastTabUrls[service] = baseUrl to url
    }

    private companion object {
        // Process-wide (not per ViewModel), since tab switches destroy the tab's back stack entry.
        private val lastTabUrls = ConcurrentHashMap<PvrService, Pair<String, String>>()
    }
}
