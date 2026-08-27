package dev.pschmitt.jellyfin.presentation.settings.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.api.pvr.RadarrApi
import dev.pschmitt.jellyfin.api.pvr.SeerrApi
import dev.pschmitt.jellyfin.api.pvr.SonarrApi
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.models.ExceptionUiText
import dev.pschmitt.jellyfin.models.ExceptionUiTexts
import dev.pschmitt.jellyfin.models.ProfileWithUserAndServer
import dev.pschmitt.jellyfin.models.UiText
import dev.pschmitt.jellyfin.presentation.settings.pvr.PvrTestState
import dev.pschmitt.jellyfin.pvr.PvrConfigResolver
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import dev.pschmitt.jellyfin.setup.domain.ProfileRepository
import dev.pschmitt.jellyfin.setup.domain.SetupRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ProfileDetailViewModel
@Inject
constructor(
    private val profileRepository: ProfileRepository,
    private val pvrConfigResolver: PvrConfigResolver,
    private val setupRepository: SetupRepository,
    private val jellyfinRepository: JellyfinRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileDetailState())
    val state = _state.asStateFlow()

    private var profileId: UUID? = null

    // Text fields (base URL, API key, advanced HTTP settings) are debounced instead of persisted
    // per keystroke, mirroring IntegrationsSettingsViewModel.persistApiKeyDebounced - every write
    // goes through ProfileRepository.overridePvrConfig, which touches Room + encrypted secrets.
    // Dirty flags let onCleared flush anything still pending when the screen closes mid-debounce.
    private val persistJobs = mutableMapOf<PvrService, Job>()
    private val dirtyServices = mutableSetOf<PvrService>()

    // ProfileRepository's methods are suspend (Room), so a flush issued from onCleared can't ride
    // viewModelScope - it's already cancelled by the time onCleared runs. This tiny scope exists
    // solely to let that last write complete, then cancels itself.
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var quickConnectJob: Job? = null

    fun load(profileIdString: String) {
        val id = runCatching { UUID.fromString(profileIdString) }.getOrNull() ?: return
        profileId = id
        viewModelScope.launch { loadState(id) }
    }

    private suspend fun loadState(id: UUID) {
        val profile = profileRepository.getProfiles().firstOrNull { it.profile.id == id } ?: return
        _state.value =
            ProfileDetailState(
                loading = false,
                name = profile.profile.name,
                isMain = profile.profile.isMain,
                sonarr = loadSection(id, PvrService.SONARR),
                radarr = loadSection(id, PvrService.RADARR),
                seerr = loadSection(id, PvrService.SEERR),
            )
        refreshServerAndUserSection(id, profile)
    }

    private suspend fun loadSection(id: UUID, service: PvrService): PvrSectionState {
        val inheriting = profileRepository.isInheriting(id, service)
        val resolved = pvrConfigResolver.resolveConfigForProfile(id, service)
        return PvrSectionState(
            inheriting = inheriting,
            enabled = resolved?.enabled ?: false,
            baseUrl = resolved?.baseUrl.orEmpty(),
            apiKey = resolved?.apiKey.orEmpty(),
            storedApiKey = resolved?.apiKey.orEmpty(),
            httpHeaders = resolved?.httpHeaders.orEmpty(),
            basicAuthUsername = resolved?.basicAuthUsername.orEmpty(),
            basicAuthPassword = resolved?.basicAuthPassword.orEmpty(),
        )
    }

    /**
     * Reloads the server/address + Jellyfin user parts of the state without touching the PVR
     * sections - used after any action in those two sections instead of a full [loadState], which
     * would needlessly re-resolve every PVR override again.
     */
    private suspend fun refreshServerAndUserSection(
        id: UUID,
        profile: ProfileWithUserAndServer? = null,
    ) {
        val current =
            profile ?: profileRepository.getProfiles().firstOrNull { it.profile.id == id } ?: return
        val serverWithAddresses =
            setupRepository.getServers().firstOrNull { it.server.id == current.serverId }
        val users =
            try {
                setupRepository.getUsers(current.serverId)
            } catch (_: Exception) {
                emptyList()
            }
        val quickConnectEnabled =
            try {
                setupRepository.getIsQuickConnectEnabled()
            } catch (_: Exception) {
                false
            }
        // The shared JellyfinRepository/JellyfinApi singleton always talks to whichever profile is
        // currently active app-wide - only safe to ask it for admin status when that's this
        // profile, otherwise we'd be labeling the ACTIVE profile's admin status as this one's.
        val isActiveProfile = profileRepository.getCurrentProfile()?.profile?.id == id
        val isAdministrator =
            if (isActiveProfile) {
                try {
                    jellyfinRepository.isCurrentUserAdministrator()
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
        _state.value =
            _state.value.copy(
                name = current.profile.name,
                serverId = current.serverId,
                serverName = current.serverName,
                addresses = serverWithAddresses?.addresses.orEmpty(),
                currentAddressId = serverWithAddresses?.server?.currentServerAddressId,
                currentUserId = current.profile.userId,
                currentUserName = current.userName,
                otherUsers = users.filterNot { it.id == current.profile.userId },
                quickConnectEnabled = quickConnectEnabled,
                isActiveProfile = isActiveProfile,
                isAdministrator = isAdministrator,
            )
    }

    fun onAction(action: ProfileDetailAction) {
        val id = profileId ?: return
        when (action) {
            is ProfileDetailAction.OnBackClick -> Unit
            is ProfileDetailAction.OnRenameConfirmed -> renameProfile(id, action.name)
            is ProfileDetailAction.OnSetAsMainClick -> setAsMain(id)
            is ProfileDetailAction.OnDeleteClick -> deleteProfile(id)
            is ProfileDetailAction.OnToggleInherit ->
                toggleInherit(id, action.service, action.inherit)
            is ProfileDetailAction.OnEnabledChanged -> {
                updateSection(action.service) { it.copy(enabled = action.enabled) }
                persistImmediately(id, action.service)
            }
            is ProfileDetailAction.OnBaseUrlChanged -> {
                updateSection(action.service) {
                    it.copy(baseUrl = action.baseUrl, testState = PvrTestState.Idle)
                }
                persistDebounced(id, action.service)
            }
            is ProfileDetailAction.OnApiKeyChanged -> {
                updateSection(action.service) {
                    it.copy(apiKey = action.apiKey, testState = PvrTestState.Idle)
                }
                persistDebounced(id, action.service)
            }
            is ProfileDetailAction.OnAdvancedSettingsChanged -> {
                updateSection(action.service) {
                    it.copy(
                        httpHeaders = action.headers,
                        basicAuthUsername = action.basicAuthUsername,
                        basicAuthPassword = action.basicAuthPassword,
                    )
                }
                persistDebounced(id, action.service)
            }
            is ProfileDetailAction.OnTestConnectionClick -> testConnection(action.service)
            is ProfileDetailAction.OnAddressSelected -> selectAddress(id, action.addressId)
            is ProfileDetailAction.OnAddAddressClick -> addAddress(id, action.address)
            is ProfileDetailAction.OnUserSelected -> selectUser(id, action.userId)
            is ProfileDetailAction.OnLoginClick ->
                loginNewUser(id, action.username, action.password)
            is ProfileDetailAction.OnQuickConnectClick -> quickConnect(id)
            is ProfileDetailAction.OnScanLibraryClick -> scanLibrary()
        }
    }

    private fun scanLibrary() {
        val current = _state.value
        if (!current.isActiveProfile || !current.isAdministrator || current.scanningLibrary) return
        viewModelScope.launch {
            _state.value = _state.value.copy(scanningLibrary = true)
            try {
                jellyfinRepository.refreshLibrary()
                _state.value =
                    _state.value.copy(
                        scanLibraryMessage =
                            UiText.StringResource(CoreR.string.scan_libraries_started_toast)
                    )
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(
                        scanLibraryMessage =
                            UiText.StringResource(
                                CoreR.string.scan_libraries_error_toast,
                                e.message.orEmpty(),
                            )
                    )
            } finally {
                _state.value = _state.value.copy(scanningLibrary = false)
            }
        }
    }

    private fun renameProfile(id: UUID, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            profileRepository.renameProfile(id, name)
            _state.value = _state.value.copy(name = name)
        }
    }

    private fun setAsMain(id: UUID) {
        viewModelScope.launch {
            profileRepository.setMainProfile(id)
            // Becoming main changes what every section resolves to (main no longer inherits), so
            // reload everything rather than patching isMain alone.
            loadState(id)
        }
    }

    private fun deleteProfile(id: UUID) {
        // The main profile can't be deleted (ProfileRepository no-ops it) - the UI already
        // disables the action, this is just a guard against a stray call.
        if (_state.value.isMain) return
        viewModelScope.launch {
            profileRepository.deleteProfile(id)
            _state.value = _state.value.copy(deleted = true)
        }
    }

    private fun toggleInherit(id: UUID, service: PvrService, inherit: Boolean) {
        persistJobs[service]?.cancel()
        dirtyServices.remove(service)
        viewModelScope.launch {
            if (inherit) {
                profileRepository.reinheritPvrConfig(id, service)
                val section = loadSection(id, service)
                _state.value = _state.value.withSection(service, section)
            } else {
                // Seed the override with whatever's currently shown (the inherited preview)
                // instead of silently blanking the fields when switching to "custom".
                val seeded = _state.value.section(service).copy(inheriting = false)
                _state.value = _state.value.withSection(service, seeded)
                flushSection(id, service)
            }
        }
    }

    private fun updateSection(
        service: PvrService,
        transform: (PvrSectionState) -> PvrSectionState,
    ) {
        val updated = transform(_state.value.section(service))
        _state.value = _state.value.withSection(service, updated)
    }

    private fun persistImmediately(id: UUID, service: PvrService) {
        persistJobs[service]?.cancel()
        dirtyServices.remove(service)
        viewModelScope.launch { flushSection(id, service) }
    }

    private fun persistDebounced(id: UUID, service: PvrService) {
        dirtyServices.add(service)
        persistJobs[service]?.cancel()
        persistJobs[service] = viewModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            flushSection(id, service)
        }
    }

    private suspend fun flushSection(id: UUID, service: PvrService) {
        val section = _state.value.section(service)
        profileRepository.overridePvrConfig(
            profileId = id,
            service = service,
            enabled = section.enabled,
            baseUrl = section.baseUrl.ifBlank { null },
            apiKey = section.apiKey.ifBlank { null },
            httpHeaders = section.httpHeaders.ifBlank { null },
            basicAuthUsername = section.basicAuthUsername.ifBlank { null },
            basicAuthPassword = section.basicAuthPassword.ifBlank { null },
        )
        dirtyServices.remove(service)
    }

    private fun testConnection(service: PvrService) {
        val section = _state.value.section(service)
        val baseUrl = section.baseUrl
        // Falls back to the last-known-good key if the visible field has been cleared (see
        // PvrSectionState.storedApiKey) - so testing/re-verifying a connection doesn't require
        // retyping a key that's already saved.
        val apiKey = section.apiKey.ifBlank { section.storedApiKey }
        updateSection(service) { it.copy(testState = PvrTestState.Testing) }
        viewModelScope.launch {
            val result =
                try {
                    when (service) {
                        PvrService.SONARR ->
                            PvrTestState.Success(SonarrApi(baseUrl, apiKey).getSeries().size)
                        PvrService.RADARR ->
                            PvrTestState.Success(RadarrApi(baseUrl, apiKey).getMovie().size)
                        PvrService.SEERR -> {
                            val api = SeerrApi(baseUrl, apiKey)
                            // auth/me validates the key; the request count doubles as the "N
                            // items" the shared success message expects.
                            api.getCurrentUser()
                            PvrTestState.Success(api.getRequests(take = 1).pageInfo.results)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PvrTestState.Error(e.message ?: e.toString())
                }
            updateSection(service) { it.copy(testState = result) }
        }
    }

    private fun selectAddress(id: UUID, addressId: UUID) {
        viewModelScope.launch {
            _state.value = _state.value.copy(addressOperationInProgress = true)
            setupRepository.setCurrentAddress(addressId)
            refreshServerAndUserSection(id)
            _state.value = _state.value.copy(addressOperationInProgress = false)
        }
    }

    private fun addAddress(id: UUID, address: String) {
        if (address.isBlank()) return
        viewModelScope.launch {
            _state.value =
                _state.value.copy(addressOperationInProgress = true, addAddressError = null)
            try {
                // addServer()/its underlying saveServerInDatabase() mutate the GLOBAL active
                // jellyfinApi (baseUrl + wipes the access token) as a side effect, same as
                // login()/loginWithSecret() below - capture whichever profile is actually active
                // first so it can be restored once the address has been persisted, since we might
                // be editing a profile that isn't the active one.
                val previousActiveProfileId = profileRepository.getCurrentProfile()?.profile?.id
                setupRepository.addServer(address)
                if (previousActiveProfileId != null) {
                    profileRepository.setCurrentProfile(previousActiveProfileId)
                }
                refreshServerAndUserSection(id)
            } catch (e: Exception) {
                _state.value = _state.value.copy(addAddressError = e.toProfileUiText())
            } finally {
                _state.value = _state.value.copy(addressOperationInProgress = false)
            }
        }
    }

    private fun selectUser(id: UUID, userId: UUID) {
        viewModelScope.launch {
            _state.value = _state.value.copy(userOperationInProgress = true, loginError = null)
            profileRepository.setProfileUser(id, userId)
            refreshServerAndUserSection(id)
            _state.value = _state.value.copy(userOperationInProgress = false)
        }
    }

    private fun loginNewUser(id: UUID, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) return
        quickConnectJob?.cancel()
        viewModelScope.launch {
            _state.value =
                _state.value.copy(
                    userOperationInProgress = true,
                    loginError = null,
                    quickConnectCode = null,
                )
            try {
                // See the critical-correctness note in addAddress() above - login() mutates the
                // global active jellyfinApi/current user, so capture + restore whichever profile
                // was actually active before reassigning this (possibly different) profile.
                val previousActiveProfileId = profileRepository.getCurrentProfile()?.profile?.id
                setupRepository.login(username, password)
                val newUser = setupRepository.getCurrentUser()
                if (newUser != null) {
                    profileRepository.setProfileUser(id, newUser.id)
                }
                if (previousActiveProfileId != null && previousActiveProfileId != id) {
                    profileRepository.setCurrentProfile(previousActiveProfileId)
                }
                refreshServerAndUserSection(id)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loginError = e.toProfileUiText())
            } finally {
                _state.value = _state.value.copy(userOperationInProgress = false)
            }
        }
    }

    private fun quickConnect(id: UUID) {
        if (quickConnectJob?.isActive == true) {
            quickConnectJob?.cancel()
            _state.value = _state.value.copy(quickConnectCode = null)
            return
        }
        quickConnectJob = viewModelScope.launch {
            _state.value = _state.value.copy(loginError = null)
            try {
                var quickConnectState = setupRepository.initiateQuickConnect()
                _state.value = _state.value.copy(quickConnectCode = quickConnectState.code)

                while (!quickConnectState.authenticated) {
                    delay(5000L)
                    quickConnectState =
                        setupRepository.getQuickConnectState(quickConnectState.secret)
                }

                val previousActiveProfileId = profileRepository.getCurrentProfile()?.profile?.id
                setupRepository.loginWithSecret(quickConnectState.secret)
                val newUser = setupRepository.getCurrentUser()
                if (newUser != null) {
                    profileRepository.setProfileUser(id, newUser.id)
                }
                if (previousActiveProfileId != null && previousActiveProfileId != id) {
                    profileRepository.setCurrentProfile(previousActiveProfileId)
                }
                _state.value = _state.value.copy(quickConnectCode = null)
                refreshServerAndUserSection(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(quickConnectCode = null)
                _state.value = _state.value.copy(loginError = e.toProfileUiText())
            }
        }
    }

    private fun Exception.toProfileUiText(): UiText =
        when (this) {
            is ExceptionUiText -> uiText
            is ExceptionUiTexts -> uiTexts.firstOrNull()
            else -> UiText.DynamicString(message ?: "")
        } ?: UiText.StringResource(CoreR.string.unknown_error)

    private fun ProfileDetailState.section(service: PvrService): PvrSectionState =
        when (service) {
            PvrService.SONARR -> sonarr
            PvrService.RADARR -> radarr
            PvrService.SEERR -> seerr
        }

    private fun ProfileDetailState.withSection(
        service: PvrService,
        section: PvrSectionState,
    ): ProfileDetailState =
        when (service) {
            PvrService.SONARR -> copy(sonarr = section)
            PvrService.RADARR -> copy(radarr = section)
            PvrService.SEERR -> copy(seerr = section)
        }

    override fun onCleared() {
        super.onCleared()
        persistJobs.values.forEach { it.cancel() }
        quickConnectJob?.cancel()
        val id = profileId
        val pending = dirtyServices.toSet()
        if (id != null && pending.isNotEmpty()) {
            flushScope.launch {
                pending.forEach { service -> flushSection(id, service) }
                flushScope.cancel()
            }
        } else {
            flushScope.cancel()
        }
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 750L
    }
}
