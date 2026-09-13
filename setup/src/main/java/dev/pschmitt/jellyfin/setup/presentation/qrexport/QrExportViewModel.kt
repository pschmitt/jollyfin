package dev.pschmitt.jellyfin.setup.presentation.qrexport

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pschmitt.jellyfin.api.JellyfinApi
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.pvr.PvrConfigResolver
import dev.pschmitt.jellyfin.qrsetup.JellyfinUserOverride
import dev.pschmitt.jellyfin.qrsetup.PvrOverride
import dev.pschmitt.jellyfin.qrsetup.QrConfigCodec
import dev.pschmitt.jellyfin.qrsetup.QrConfigManager
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.setup.domain.ProfileRepository
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.AuthenticateUserByName

@HiltViewModel
class QrExportViewModel
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    private val profileRepository: ProfileRepository,
    private val pvrConfigResolver: PvrConfigResolver,
    private val qrConfigManager: QrConfigManager,
) : ViewModel() {
    private val _state = MutableStateFlow(QrExportState())
    val state = _state.asStateFlow()

    // Cancelled/replaced on every regenerate so rapid typing/toggling can't let an earlier (now
    // stale) generate() call - which may involve a real login network call - finish after a
    // later one and clobber its result.
    private var generateJob: Job? = null

    fun onAction(action: QrExportAction) {
        when (action) {
            is QrExportAction.OnLoad -> load()
            is QrExportAction.OnIncludeJellyfinChanged ->
                updateAndRegenerate { it.copy(includeJellyfin = action.include) }
            is QrExportAction.OnIncludeSonarrChanged ->
                updateAndRegenerate { it.copy(includeSonarr = action.include) }
            is QrExportAction.OnIncludeRadarrChanged ->
                updateAndRegenerate { it.copy(includeRadarr = action.include) }
            is QrExportAction.OnIncludeSeerrChanged ->
                updateAndRegenerate { it.copy(includeSeerr = action.include) }
            is QrExportAction.OnProfileSelected -> selectProfile(action.profileId)
            is QrExportAction.OnServerSelected -> {
                val server = _state.value.availableServers.find { it.server.id == action.serverId }
                val defaultUser =
                    server?.users?.find { it.id == server.server.currentUserId }
                        ?: server?.users?.firstOrNull()
                updateAndRegenerate {
                    it.copy(
                        selectedServerId = action.serverId,
                        selectedUserId = defaultUser?.id,
                        jellyfinUsername = defaultUser?.name.orEmpty(),
                        jellyfinPassword = "",
                        jellyfinLoginError = null,
                    )
                }
            }
            is QrExportAction.OnUserSelected -> {
                val userName =
                    _state.value.availableServers
                        .find { it.server.id == _state.value.selectedServerId }
                        ?.users
                        ?.find { it.id == action.userId }
                        ?.name
                updateAndRegenerate {
                    it.copy(
                        selectedUserId = action.userId,
                        jellyfinUsername = userName.orEmpty(),
                        jellyfinPassword = "",
                        jellyfinLoginError = null,
                    )
                }
            }
            is QrExportAction.OnAdvancedToggle ->
                _state.value = _state.value.copy(advancedExpanded = !_state.value.advancedExpanded)
            is QrExportAction.OnJellyfinUsernameChanged ->
                updateAndRegenerate {
                    it.copy(jellyfinUsername = action.value, jellyfinLoginError = null)
                }
            is QrExportAction.OnJellyfinPasswordChanged ->
                updateAndRegenerate {
                    it.copy(jellyfinPassword = action.value, jellyfinLoginError = null)
                }
            is QrExportAction.OnToggleJellyfinPasswordVisibility ->
                _state.value =
                    _state.value.copy(
                        jellyfinPasswordVisible = !_state.value.jellyfinPasswordVisible
                    )
            is QrExportAction.OnSonarrBaseUrlChanged ->
                updateAndRegenerate { it.copy(sonarrBaseUrl = action.value) }
            is QrExportAction.OnSonarrApiKeyChanged ->
                updateAndRegenerate { it.copy(sonarrApiKey = action.value) }
            is QrExportAction.OnRadarrBaseUrlChanged ->
                updateAndRegenerate { it.copy(radarrBaseUrl = action.value) }
            is QrExportAction.OnRadarrApiKeyChanged ->
                updateAndRegenerate { it.copy(radarrApiKey = action.value) }
            is QrExportAction.OnSeerrBaseUrlChanged ->
                updateAndRegenerate { it.copy(seerrBaseUrl = action.value) }
            is QrExportAction.OnSeerrApiKeyChanged ->
                updateAndRegenerate { it.copy(seerrApiKey = action.value) }
            is QrExportAction.OnRegeneratePassword ->
                updateAndRegenerate { it.copy(password = generatePassword()) }
            is QrExportAction.OnTogglePasswordVisibility ->
                _state.value = _state.value.copy(passwordVisible = !_state.value.passwordVisible)
            is QrExportAction.OnBackClick -> Unit
        }
    }

    private fun updateAndRegenerate(transform: (QrExportState) -> QrExportState) {
        _state.value = transform(_state.value)
        generate()
    }

    private fun load() {
        viewModelScope.launch {
            val availableProfiles = profileRepository.getProfiles()
            val selectedProfileId =
                profileRepository.getCurrentProfile()?.profile?.id
                    ?: availableProfiles.firstOrNull()?.profile?.id
            val availableServers = qrConfigManager.getAvailableServers()
            val selectedProfile = availableProfiles.firstOrNull {
                it.profile.id == selectedProfileId
            }
            val currentServerId =
                selectedProfile?.serverId ?: appPreferences.getValue(appPreferences.currentServer)
            val currentServer =
                availableServers.find { it.server.id == currentServerId }
                    ?: availableServers.firstOrNull()
            val currentUser =
                currentServer?.users?.find { it.id == selectedProfile?.profile?.userId }
                    ?: currentServer?.users?.find { it.id == currentServer.server.currentUserId }
                    ?: currentServer?.users?.firstOrNull()
            _state.value =
                _state.value.copy(
                    jellyfinAvailable = currentServer != null,
                    sonarrAvailable = isConfigured(selectedProfileId, PvrService.SONARR),
                    radarrAvailable = isConfigured(selectedProfileId, PvrService.RADARR),
                    seerrAvailable = isConfigured(selectedProfileId, PvrService.SEERR),
                    availableProfiles = availableProfiles,
                    selectedProfileId = selectedProfileId,
                    availableServers = availableServers,
                    selectedServerId = currentServer?.server?.id,
                    selectedUserId = currentUser?.id,
                    jellyfinUsername = currentUser?.name.orEmpty(),
                    sonarrBaseUrl = qrConfigManager.currentSonarrBaseUrl(selectedProfileId),
                    radarrBaseUrl = qrConfigManager.currentRadarrBaseUrl(selectedProfileId),
                    seerrBaseUrl = qrConfigManager.currentSeerrBaseUrl(selectedProfileId),
                    password = generatePassword(),
                )
            generate()
        }
    }

    private fun selectProfile(profileId: UUID) {
        val current = _state.value
        val profile = current.availableProfiles.firstOrNull { it.profile.id == profileId } ?: return
        val profileUser =
            current.availableServers
                .find { it.server.id == profile.serverId }
                ?.users
                ?.find { it.id == profile.profile.userId }
        updateAndRegenerate {
            it.copy(
                selectedProfileId = profileId,
                sonarrAvailable = isConfigured(profileId, PvrService.SONARR),
                radarrAvailable = isConfigured(profileId, PvrService.RADARR),
                seerrAvailable = isConfigured(profileId, PvrService.SEERR),
                selectedServerId = profile.serverId,
                selectedUserId = profileUser?.id,
                jellyfinUsername = profileUser?.name.orEmpty(),
                jellyfinPassword = "",
                jellyfinLoginError = null,
                sonarrBaseUrl = qrConfigManager.currentSonarrBaseUrl(profileId),
                sonarrApiKey = "",
                radarrBaseUrl = qrConfigManager.currentRadarrBaseUrl(profileId),
                radarrApiKey = "",
                seerrBaseUrl = qrConfigManager.currentSeerrBaseUrl(profileId),
                seerrApiKey = "",
            )
        }
    }

    private fun isConfigured(profileId: UUID?, service: PvrService): Boolean {
        val config =
            if (profileId == null) {
                pvrConfigResolver.resolveConfig(service)
            } else {
                pvrConfigResolver.resolveConfigForProfile(profileId, service)
            }
        return config?.let {
            it.enabled && !it.baseUrl.isNullOrBlank() && !it.apiKey.isNullOrBlank()
        } == true
    }

    private fun generate() {
        generateJob?.cancel()
        val current = _state.value
        val anySelected =
            (current.includeJellyfin && current.jellyfinAvailable) ||
                (current.includeSonarr && current.sonarrAvailable) ||
                (current.includeRadarr && current.radarrAvailable) ||
                (current.includeSeerr && current.seerrAvailable)
        if (!anySelected) {
            _state.value = _state.value.copy(payload = null, error = null, isGenerating = false)
            return
        }
        generateJob = viewModelScope.launch {
            // Debounced so typing into a text field (username/password/base URL/API key)
            // doesn't fire a fresh build - and, worse, a fresh login attempt - on every
            // keystroke.
            delay(DEBOUNCE_MILLIS)
            _state.value = _state.value.copy(isGenerating = true, error = null)

            val jellyfinOverride =
                if (
                    current.includeJellyfin &&
                        current.jellyfinAvailable &&
                        current.jellyfinPassword.isNotBlank()
                ) {
                    val result = authenticate(current)
                    if (result == null) {
                        _state.value = _state.value.copy(isGenerating = false)
                        return@launch
                    }
                    result
                } else {
                    null
                }

            try {
                val envelope =
                    qrConfigManager.buildEnvelope(
                        profileId = current.selectedProfileId,
                        includeJellyfin = current.includeJellyfin && current.jellyfinAvailable,
                        jellyfinServerId = current.selectedServerId,
                        jellyfinUserId = current.selectedUserId,
                        jellyfinOverride = jellyfinOverride,
                        includeSonarr = current.includeSonarr && current.sonarrAvailable,
                        sonarrOverride =
                            if (current.includeSonarr && current.sonarrAvailable) {
                                PvrOverride(current.sonarrBaseUrl, current.sonarrApiKey)
                            } else {
                                null
                            },
                        includeRadarr = current.includeRadarr && current.radarrAvailable,
                        radarrOverride =
                            if (current.includeRadarr && current.radarrAvailable) {
                                PvrOverride(current.radarrBaseUrl, current.radarrApiKey)
                            } else {
                                null
                            },
                        includeSeerr = current.includeSeerr && current.seerrAvailable,
                        seerrOverride =
                            if (current.includeSeerr && current.seerrAvailable) {
                                PvrOverride(current.seerrBaseUrl, current.seerrApiKey)
                            } else {
                                null
                            },
                    )
                val payload = QrConfigCodec.encodePayload(envelope, current.password)
                _state.value =
                    _state.value.copy(
                        payload = payload,
                        isGenerating = false,
                        jellyfinLoginError = null,
                    )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message, isGenerating = false)
            }
        }
    }

    /**
     * Live-authenticates against the selected server with the typed username/password, using a
     * throwaway [JellyfinApi] instance so the app's actual active session is never touched. Returns
     * null (with [QrExportState.jellyfinLoginError] set) on failure.
     */
    private suspend fun authenticate(state: QrExportState): JellyfinUserOverride? {
        val server = state.availableServers.find { it.server.id == state.selectedServerId }
        val address =
            server?.addresses?.find { it.id == server.server.currentServerAddressId }
                ?: server?.addresses?.firstOrNull()
        if (address == null) {
            _state.value = _state.value.copy(jellyfinLoginError = "No server address known")
            return null
        }

        _state.value = _state.value.copy(isVerifyingJellyfinLogin = true, jellyfinLoginError = null)
        return try {
            val oneOff = JellyfinApi(context)
            oneOff.api.update(baseUrl = address.address)
            val authenticationResult by
                oneOff.authenticationApi.authenticateUserByName(
                    data =
                        AuthenticateUserByName(
                            username = state.jellyfinUsername,
                            pw = state.jellyfinPassword,
                        )
                )
            JellyfinUserOverride(
                userId = authenticationResult.user!!.id,
                userName = authenticationResult.user!!.name!!,
                accessToken = authenticationResult.accessToken!!,
            )
        } catch (e: Exception) {
            val message =
                if (e.message?.contains("401") == true) {
                    "Invalid username or password"
                } else {
                    e.message ?: "Couldn't reach the server"
                }
            _state.value = _state.value.copy(jellyfinLoginError = message)
            null
        } finally {
            _state.value = _state.value.copy(isVerifyingJellyfinLogin = false)
        }
    }

    /**
     * Legible-alphabet (no `0/O/1/I/L`) random passphrase, ~60 bits of entropy - short enough to
     * read off one screen and type into another by hand, since unlike the rest of the payload, the
     * passphrase itself never travels through the QR code.
     */
    private fun generatePassword(): String {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val random = SecureRandom()
        return (1..12)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .chunked(4)
            .joinToString("-") { it.joinToString("") }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 500L
    }
}
