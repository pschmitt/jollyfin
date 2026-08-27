package dev.pschmitt.jellyfin.presentation.settings

import android.app.Activity
import android.app.UiModeManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.presentation.components.TopBarTitle
import dev.pschmitt.jellyfin.presentation.settings.components.DownloadLocationChangeDialog
import dev.pschmitt.jellyfin.presentation.settings.components.SettingsGroupCard
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.presentation.utils.plus
import dev.pschmitt.jellyfin.settings.R as SettingsR
import dev.pschmitt.jellyfin.settings.presentation.enums.DeviceType
import dev.pschmitt.jellyfin.settings.presentation.models.PreferenceCategory
import dev.pschmitt.jellyfin.settings.presentation.models.PreferenceGroup
import dev.pschmitt.jellyfin.settings.presentation.settings.RelocateDownloadsMode
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsAction
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsEvent
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsState
import dev.pschmitt.jellyfin.settings.presentation.settings.SettingsViewModel
import dev.pschmitt.jellyfin.utils.ObserveAsEvents
import dev.pschmitt.jellyfin.utils.restart
import dev.pschmitt.jellyfin.work.RelocateDownloadsWorker
import timber.log.Timber

@Composable
fun SettingsScreen(
    indexes: IntArray = intArrayOf(),
    navigateToSettings: (indexes: IntArray) -> Unit,
    navigateToSettingsFileEdit: (filePath: String) -> Unit,
    navigateToAbout: () -> Unit,
    navigateToAutoDownloadRules: () -> Unit,
    navigateToLocalAccess: () -> Unit,
    navigateToScanLibraries: () -> Unit,
    navigateToBackupSettings: () -> Unit,
    navigateToQrExport: () -> Unit,
    navigateToProfiles: () -> Unit,
    navigateToProfileDetail: (profileId: String) -> Unit,
    navigateToHomeLayout: () -> Unit,
    navigateToNavigationBar: () -> Unit,
    navigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    var pendingLocationChange by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(true) { viewModel.loadPreferences(indexes, DeviceType.PHONE) }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is SettingsEvent.NavigateToSettings -> navigateToSettings(event.indexes)
            is SettingsEvent.NavigateToSettingsFileEdit ->
                navigateToSettingsFileEdit(event.filePath)
            is SettingsEvent.NavigateToUsers -> Unit
            is SettingsEvent.NavigateToServers -> Unit
            is SettingsEvent.NavigateToAbout -> navigateToAbout()
            is SettingsEvent.NavigateToAutoDownloadRules -> navigateToAutoDownloadRules()
            is SettingsEvent.NavigateToLocalAccess -> navigateToLocalAccess()
            is SettingsEvent.NavigateToScanLibraries -> navigateToScanLibraries()
            is SettingsEvent.NavigateToBackupSettings -> navigateToBackupSettings()
            is SettingsEvent.NavigateToQrExport -> navigateToQrExport()
            is SettingsEvent.NavigateToConnections -> Unit
            is SettingsEvent.NavigateToProfiles -> navigateToProfiles()
            is SettingsEvent.NavigateToProfileDetail -> navigateToProfileDetail(event.profileId)
            is SettingsEvent.NavigateToHomeLayout -> navigateToHomeLayout()
            is SettingsEvent.NavigateToNavigationBar -> navigateToNavigationBar()
            is SettingsEvent.UpdateTheme -> {
                val uiModeManager = context.getSystemService(UiModeManager::class.java)
                val nightMode =
                    when (event.theme) {
                        "system" ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_AUTO
                            else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        "light" ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_NO
                            else AppCompatDelegate.MODE_NIGHT_NO
                        "dark" ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_YES
                            else AppCompatDelegate.MODE_NIGHT_YES
                        else ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_AUTO
                            else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    uiModeManager.setApplicationNightMode(nightMode)
                } else {
                    AppCompatDelegate.setDefaultNightMode(nightMode)
                }
            }
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
                } catch (_: Exception) {}
            }
            is SettingsEvent.DownloadLocationChanged -> {
                pendingLocationChange = event.from to event.to
            }
        }
    }

    val onAction: (SettingsAction) -> Unit = { action ->
        when (action) {
            is SettingsAction.OnBackClick -> navigateBack()
            is SettingsAction.OnUpdate -> {
                viewModel.onAction(action)
                viewModel.loadPreferences(indexes, DeviceType.PHONE)
            }
            is SettingsAction.OnRelocateDownloads -> {
                enqueueRelocateDownloadsWork(context, action.mode, action.from, action.to)
            }
        }
    }

    val relocateProgress = rememberRelocateDownloadsProgress()
    SettingsScreenLayout(
        title = indexes.last(),
        state = state,
        onAction = onAction,
        relocateProgress = relocateProgress,
    )

    pendingLocationChange?.let { (from, to) ->
        DownloadLocationChangeDialog(
            from = from,
            to = to,
            onMove = {
                onAction(SettingsAction.OnRelocateDownloads(RelocateDownloadsMode.MOVE, from, to))
                pendingLocationChange = null
            },
            onClear = {
                onAction(SettingsAction.OnRelocateDownloads(RelocateDownloadsMode.CLEAR, from, to))
                pendingLocationChange = null
            },
            onDismissRequest = { pendingLocationChange = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenLayout(
    @StringRes title: Int,
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
    relocateProgress: RelocateDownloadsProgress? = null,
) {
    val contentPadding = PaddingValues(all = MaterialTheme.spacings.default)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsets()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        bottomBar = { relocateProgress?.let { RelocateProgressCard(it) } },
        topBar = {
            TopAppBar(
                title = {
                    TopBarTitle(
                        text = stringResource(title),
                        iconRes =
                            if (title == CoreR.string.title_settings) {
                                CoreR.drawable.ic_settings
                            } else {
                                state.titleIconDrawableId
                            },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(SettingsAction.OnBackClick) }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding + innerPadding,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(state.preferenceGroups) { group ->
                SettingsGroupCard(
                    group = group,
                    onAction = onAction,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
        }
    }
}

@Composable
private fun RelocateProgressCard(progress: RelocateDownloadsProgress) {
    Card(modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.default)) {
        Column(modifier = Modifier.padding(MaterialTheme.spacings.default)) {
            Text(
                text =
                    stringResource(
                        if (progress.mode == RelocateDownloadsWorker.MODE_CLEAR) {
                            CoreR.string.relocate_downloads_progress_clearing
                        } else {
                            CoreR.string.relocate_downloads_progress_moving
                        },
                        progress.done,
                        progress.total,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) progress.done / progress.total.toFloat() else 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@PreviewScreenSizes
@Composable
private fun SettingsScreenLayoutPreview() {
    JollyfinTheme {
        SettingsScreenLayout(
            title = CoreR.string.title_settings,
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
