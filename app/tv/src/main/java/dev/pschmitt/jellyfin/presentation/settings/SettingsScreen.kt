package dev.pschmitt.jellyfin.presentation.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.pschmitt.jellyfin.presentation.settings.components.SettingsGroupCard
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.settings.R as SettingsR
import dev.pschmitt.jellyfin.settings.presentation.enums.DeviceType
import dev.pschmitt.jellyfin.settings.presentation.models.PreferenceCategory
import dev.pschmitt.jellyfin.settings.presentation.models.PreferenceGroup
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsAction
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsEvent
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsState
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsViewModel
import dev.pschmitt.jellyfin.utils.ObserveAsEvents
import dev.pschmitt.jellyfin.utils.restart
import timber.log.Timber

@Composable
fun SettingsScreen(
    navigateToSubSettings: (indexes: IntArray) -> Unit,
    navigateToServers: () -> Unit,
    navigateToUsers: () -> Unit,
    navigateToProfiles: () -> Unit,
    navigateToProfileDetail: (profileId: String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadPreferences(intArrayOf(), DeviceType.TV) }

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

    SettingsScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is SettingsAction.OnUpdate -> {
                    viewModel.onAction(action)
                    viewModel.loadPreferences(intArrayOf(), DeviceType.TV)
                }
                else -> Unit
            }
        },
    )
}

@Composable
private fun SettingsScreenLayout(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    val focusRequester = remember { FocusRequester() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        contentPadding =
            PaddingValues(
                horizontal = MaterialTheme.spacings.default * 2,
                vertical = MaterialTheme.spacings.large,
            ),
        modifier = Modifier.fillMaxSize().focusRequester(focusRequester),
    ) {
        item(span = { GridItemSpan(this.maxLineSpan) }) {
            Text(
                text = stringResource(id = SettingsR.string.title_settings),
                style = MaterialTheme.typography.displayMedium,
            )
        }
        items(state.preferenceGroups) { group ->
            SettingsGroupCard(group = group, onAction = onAction)
        }
    }
    LaunchedEffect(true) { focusRequester.requestFocus() }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun SettingsScreenLayoutPreview() {
    JollyfinTheme {
        SettingsScreenLayout(
            state =
                SettingsState(
                    preferenceGroups =
                        listOf(
                            PreferenceGroup(
                                nameStringResource = null,
                                preferences =
                                    listOf(
                                        PreferenceCategory(
                                            nameStringResource =
                                                SettingsR.string.settings_category_language,
                                            iconDrawableId = SettingsR.drawable.ic_languages,
                                        )
                                    ),
                            ),
                            PreferenceGroup(
                                nameStringResource = null,
                                preferences =
                                    listOf(
                                        PreferenceCategory(
                                            nameStringResource =
                                                SettingsR.string.settings_category_appearance,
                                            iconDrawableId = SettingsR.drawable.ic_palette,
                                        )
                                    ),
                            ),
                        )
                ),
            onAction = {},
        )
    }
}
