package dev.pschmitt.jellyfin.presentation.settings.scanlibraries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface ScanLibrariesEvent {
    data object ScanStarted : ScanLibrariesEvent

    data class ScanFailed(val message: String?) : ScanLibrariesEvent
}

@HiltViewModel
class ScanLibrariesViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow(ScanLibrariesState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<ScanLibrariesEvent>()
    val events = eventsChannel.receiveAsFlow()

    fun load() {
        viewModelScope.launch {
            val isAdministrator = repository.isCurrentUserAdministrator()
            _state.value = _state.value.copy(loading = false, isAdministrator = isAdministrator)
        }
    }

    fun onAction(action: ScanLibrariesAction) {
        when (action) {
            is ScanLibrariesAction.OnBackClick -> Unit
            is ScanLibrariesAction.OnScanClick -> scan()
        }
    }

    private fun scan() {
        if (_state.value.scanning || !_state.value.isAdministrator) return
        viewModelScope.launch {
            _state.value = _state.value.copy(scanning = true)
            try {
                repository.refreshLibrary()
                eventsChannel.send(ScanLibrariesEvent.ScanStarted)
            } catch (e: Exception) {
                eventsChannel.send(ScanLibrariesEvent.ScanFailed(e.message))
            } finally {
                _state.value = _state.value.copy(scanning = false)
            }
        }
    }
}
