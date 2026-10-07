package dev.pschmitt.jellyfin.setup.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.models.UiText
import dev.pschmitt.jellyfin.setup.R as SetupR
import dev.pschmitt.jellyfin.setup.domain.ProfileRepository
import dev.pschmitt.jellyfin.setup.domain.SetupRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LoginViewModel
@Inject
constructor(
    private val repository: SetupRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<LoginEvent>()
    val events = eventsChannel.receiveAsFlow()

    private var quickConnectJob: Job? = null

    fun loadServer() {
        viewModelScope.launch {
            try {
                val server = repository.getCurrentServer()
                _state.emit(_state.value.copy(serverName = server?.name))
            } catch (_: Exception) {}
        }
    }

    fun loadDisclaimer() {
        viewModelScope.launch {
            try {
                val loginDisclaimer = repository.loadDisclaimer()
                _state.emit(_state.value.copy(disclaimer = loginDisclaimer))
            } catch (_: Exception) {}
        }
    }

    fun loadQuickConnectEnabled() {
        viewModelScope.launch {
            try {
                val isEnabled = repository.getIsQuickConnectEnabled()
                _state.emit(_state.value.copy(quickConnectEnabled = isEnabled))
            } catch (_: Exception) {}
        }
    }

    private fun login(username: String, password: String) {
        viewModelScope.launch {
            try {
                _state.emit(_state.value.copy(isLoading = true, error = null))
                repository.login(username, password)
                ensureFirstProfile()
                _state.emit(_state.value.copy(isLoading = false))
                eventsChannel.send(LoginEvent.Success)
            } catch (e: Exception) {
                val message =
                    if (e.message?.contains("401") == true) {
                        UiText.StringResource(SetupR.string.login_error_wrong_username_password)
                    } else {
                        UiText.StringResource(CoreR.string.unknown_error)
                    }
                _state.emit(_state.value.copy(isLoading = false, error = message))
            }
        }
    }

    private fun quickConnect() {
        if (quickConnectJob?.isActive == true) {
            quickConnectJob?.cancel()
            return
        }
        quickConnectJob = viewModelScope.launch {
            try {
                var quickConnectState = repository.initiateQuickConnect()
                _state.emit(_state.value.copy(quickConnectCode = quickConnectState.code))

                while (!quickConnectState.authenticated) {
                    delay(5000L)
                    quickConnectState = repository.getQuickConnectState(quickConnectState.secret)
                }

                repository.loginWithSecret(quickConnectState.secret)
                ensureFirstProfile()

                _state.emit(_state.value.copy(quickConnectCode = null))
                eventsChannel.send(LoginEvent.Success)
            } catch (_: Exception) {
                _state.emit(_state.value.copy(quickConnectCode = null))
            }
        }
    }

    /**
     * A fresh install's first login has no Profile to land in, and Sonarr/Radarr/Seerr are only
     * configurable per profile - so create the first (main) one here, mirroring what upgrading
     * installs get from ProfileMigrationRunner. Later logins (adding a user, re-logging in) leave
     * profiles alone; those are managed from Settings > Profiles.
     */
    private suspend fun ensureFirstProfile() {
        if (profileRepository.getProfiles().isNotEmpty()) return
        val user = repository.getCurrentUser() ?: return
        val profile = profileRepository.createProfile(user.id)
        profileRepository.setCurrentProfile(profile.id)
    }

    fun onAction(action: LoginAction) {
        when (action) {
            is LoginAction.OnLoginClick -> {
                login(action.username, action.password)
            }
            is LoginAction.OnQuickConnectClick -> {
                quickConnect()
            }
            else -> Unit
        }
    }
}
