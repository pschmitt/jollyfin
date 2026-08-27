package dev.pschmitt.jellyfin.presentation.settings

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.pschmitt.jellyfin.presentation.settings.components.SettingsGroupCard
import dev.pschmitt.jellyfin.presentation.settings.components.SettingsMultiSelectDetailsCard
import dev.pschmitt.jellyfin.presentation.settings.components.SettingsSelectDetailsCard
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.settings.R as SettingsR
import dev.pschmitt.jellyfin.settings.domain.models.Preference
import dev.pschmitt.jellyfin.settings.presentation.enums.DeviceType
import dev.pschmitt.jellyfin.settings.presentation.models.PreferenceGroup
import dev.pschmitt.jellyfin.settings.presentation.models.PreferenceMultiSelect
import dev.pschmitt.jellyfin.settings.presentation.models.PreferenceSelect
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsAction
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsEvent
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsState
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsViewModel
import dev.pschmitt.jellyfin.utils.ObserveAsEvents
import dev.pschmitt.jellyfin.utils.restart
import timber.log.Timber

@Composable
fun SettingsSubScreen(
    indexes: IntArray = intArrayOf(),
    navigateToSubSettings: (indexes: IntArray) -> Unit,
    navigateToServers: () -> Unit,
    navigateToUsers: () -> Unit,
    navigateToProfiles: () -> Unit,
    navigateToProfileDetail: (profileId: String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadPreferences(indexes, DeviceType.TV) }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is SettingsEvent.NavigateToSettings -> navigateToSubSettings(event.indexes)
            is SettingsEvent.NavigateToSettingsFileEdit -> {}
            is SettingsEvent.NavigateToUsers -> navigateToUsers()
            is SettingsEvent.NavigateToServers -> navigateToServers()
            is SettingsEvent.NavigateToProfiles -> navigateToProfiles()
            is SettingsEvent.NavigateToProfileDetail -> navigateToProfileDetail(event.profileId)
            is SettingsEvent.NavigateToAbout -> Unit
            is SettingsEvent.NavigateToAutoDownloadRules -> Unit
            // Local CLI access (jollyfin-cli pairing) is phone-only for this pass - nothing for
            // TV to react to.
            is SettingsEvent.NavigateToLocalAccess -> Unit
            is SettingsEvent.NavigateToScanLibraries -> Unit
            is SettingsEvent.NavigateToBackupSettings -> Unit
            // Navbar customization is phone-only for this pass - nothing for TV to react to.
            is SettingsEvent.NavigateToNavigationBar -> Unit
            // QR device provisioning is phone-only (needs a camera) - nothing for TV to react to.
            is SettingsEvent.NavigateToQrExport -> Unit
            // Integrations settings are phone-only for this pass - nothing for TV to react to.
            is SettingsEvent.NavigateToConnections -> Unit
            // Home layout reordering is phone-only for this pass - nothing for TV to react to.
            is SettingsEvent.NavigateToHomeLayout -> Unit
            is SettingsEvent.UpdateTheme -> Unit
            is SettingsEvent.LaunchIntent -> {
                try {
                    context.startActivity(event.intent)
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
            is SettingsEvent.RestartActivity -> {
                try {
                    (context as Activity).restart()
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
            // The download-location relocate prompt is phone-only (that preference isn't
            // shown on TV), so there's nothing for this screen to react to.
            is SettingsEvent.DownloadLocationChanged -> Unit
        }
    }

    SettingsSubScreenLayout(
        title = indexes.last(),
        state = state,
        onAction = { action ->
            when (action) {
                is SettingsAction.OnUpdate -> {
                    viewModel.onAction(action)
                    viewModel.loadPreferences(indexes, DeviceType.TV)
                }
                else -> Unit
            }
        },
    )
}

@Composable
private fun SettingsSubScreenLayout(
    @StringRes title: Int,
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    var focusedPreference by
        remember(state.preferenceGroups.isNotEmpty()) {
            mutableStateOf(state.preferenceGroups.firstOrNull()?.preferences?.firstOrNull())
        }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    start = MaterialTheme.spacings.large,
                    top = MaterialTheme.spacings.default * 2,
                    end = MaterialTheme.spacings.large,
                )
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                state.titleIconDrawableId?.let {
                    Icon(
                        painter = painterResource(id = it),
                        contentDescription = null,
                        modifier = Modifier.padding(end = MaterialTheme.spacings.default),
                    )
                }
                Text(
                    text = stringResource(id = title),
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            Text(
                text = stringResource(id = SettingsR.string.title_settings),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large)) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                contentPadding = PaddingValues(vertical = MaterialTheme.spacings.large),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            ) {
                items(state.preferenceGroups) { group ->
                    SettingsGroupCard(
                        group = group,
                        onAction = onAction,
                        onFocusChange = { focusState, preference ->
                            if (focusState.isFocused) {
                                focusedPreference = preference
                            }
                        },
                    )
                }
            }
            Box(modifier = Modifier.weight(2f)) {
                focusedPreference?.let { preference ->
                    when (preference) {
                        is PreferenceSelect -> {
                            SettingsSelectDetailsCard(
                                preference = preference,
                                modifier =
                                    Modifier.fillMaxSize()
                                        .padding(bottom = MaterialTheme.spacings.large),
                                onUpdate = { value ->
                                    onAction(
                                        SettingsAction.OnUpdate(preference.copy(value = value))
                                    )
                                },
                            )
                        }
                        is PreferenceMultiSelect -> {
                            SettingsMultiSelectDetailsCard(
                                preference = preference,
                                modifier =
                                    Modifier.fillMaxSize()
                                        .padding(bottom = MaterialTheme.spacings.large),
                                onUpdate = { value ->
                                    onAction(
                                        SettingsAction.OnUpdate(preference.copy(value = value))
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(true) { focusRequester.requestFocus() }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun SettingsSubScreenLayoutPreview() {
    JollyfinTheme {
        SettingsSubScreenLayout(
            title = SettingsR.string.title_settings,
            state =
                SettingsState(
                    preferenceGroups =
                        listOf(
                            PreferenceGroup(
                                preferences =
                                    listOf(
                                        PreferenceSelect(
                                            nameStringResource =
                                                SettingsR.string.pref_player_mpv_hwdec,
                                            backendPreference = Preference("", ""),
                                            options = SettingsR.array.mpv_hwdec,
                                            optionValues = SettingsR.array.mpv_hwdec,
                                        )
                                    )
                            )
                        )
                ),
            onAction = {},
        )
    }
}
