package dev.pschmitt.jellyfin.setup.presentation.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.setup.domain.ProfileRepository
import dev.pschmitt.jellyfin.setup.domain.SetupRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ProfilesViewModel
@Inject
constructor(
    private val repository: ProfileRepository,
    private val setupRepository: SetupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfilesState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<ProfilesEvent>()
    val events = eventsChannel.receiveAsFlow()

    fun loadProfiles() {
        viewModelScope.launch {
            val profiles = repository.getProfiles()
            val currentProfileId = repository.getCurrentProfile()?.profile?.id
            val serverBaseUrls =
                try {
                    setupRepository.getServers().associate { serverWithAddresses ->
                        val server = serverWithAddresses.server
                        val address =
                            serverWithAddresses.addresses
                                .firstOrNull { it.id == server.currentServerAddressId }
                                ?.address ?: serverWithAddresses.addresses.firstOrNull()?.address
                        server.id to address.orEmpty()
                    }
                } catch (_: Exception) {
                    emptyMap()
                }
            _state.emit(
                ProfilesState(
                    profiles = profiles,
                    currentProfileId = currentProfileId,
                    serverBaseUrls = serverBaseUrls,
                )
            )
        }
    }

    private fun setCurrentProfile(profileId: UUID) {
        viewModelScope.launch {
            repository.setCurrentProfile(profileId)
            eventsChannel.send(ProfilesEvent.ProfileChanged)
        }
    }

    fun onAction(action: ProfilesAction) {
        when (action) {
            is ProfilesAction.OnProfileClick -> setCurrentProfile(action.profileId)
            is ProfilesAction.DeleteProfile -> {
                viewModelScope.launch {
                    repository.deleteProfile(action.profileId)
                    loadProfiles()
                }
            }
            is ProfilesAction.SetMainProfile -> {
                viewModelScope.launch {
                    repository.setMainProfile(action.profileId)
                    loadProfiles()
                }
            }
            is ProfilesAction.RenameProfile -> {
                viewModelScope.launch {
                    repository.renameProfile(action.profileId, action.name)
                    loadProfiles()
                }
            }
            else -> Unit
        }
    }
}
