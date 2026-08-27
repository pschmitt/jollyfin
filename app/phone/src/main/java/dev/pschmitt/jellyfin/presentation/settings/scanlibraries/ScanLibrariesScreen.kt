package dev.pschmitt.jellyfin.presentation.settings.scanlibraries

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.utils.ObserveAsEvents

@Composable
fun ScanLibrariesScreen(
    navigateBack: () -> Unit,
    viewModel: ScanLibrariesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(true) { viewModel.load() }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ScanLibrariesEvent.ScanStarted ->
                Toast.makeText(
                        context,
                        CoreR.string.scan_libraries_started_toast,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            is ScanLibrariesEvent.ScanFailed -> {
                Toast.makeText(
                        context,
                        context.getString(
                            CoreR.string.scan_libraries_error_toast,
                            event.message.orEmpty(),
                        ),
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    }

    ScanLibrariesScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is ScanLibrariesAction.OnBackClick -> navigateBack()
                else -> viewModel.onAction(action)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanLibrariesScreenLayout(
    state: ScanLibrariesState,
    onAction: (ScanLibrariesAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(CoreR.string.scan_libraries_title)) },
                navigationIcon = {
                    IconButton(onClick = { onAction(ScanLibrariesAction.OnBackClick) }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(innerPadding)
                    .padding(MaterialTheme.spacings.default)
        ) {
            Text(
                text = stringResource(CoreR.string.scan_libraries_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.loading && !state.isAdministrator) {
                Text(
                    text = stringResource(CoreR.string.scan_libraries_admin_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = MaterialTheme.spacings.small),
                )
            }
            Button(
                onClick = { onAction(ScanLibrariesAction.OnScanClick) },
                enabled = !state.loading && state.isAdministrator && !state.scanning,
                modifier = Modifier.padding(top = MaterialTheme.spacings.default),
            ) {
                Text(text = stringResource(CoreR.string.scan_libraries_button))
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun ScanLibrariesScreenLayoutPreview() {
    JollyfinTheme {
        ScanLibrariesScreenLayout(
            state = ScanLibrariesState(loading = false, isAdministrator = true),
            onAction = {},
        )
    }
}
