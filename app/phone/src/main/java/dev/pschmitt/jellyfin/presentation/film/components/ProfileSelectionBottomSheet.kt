package dev.pschmitt.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.setup.presentation.profiles.ProfilesAction
import dev.pschmitt.jellyfin.setup.presentation.profiles.ProfilesEvent
import dev.pschmitt.jellyfin.setup.presentation.profiles.ProfilesState
import dev.pschmitt.jellyfin.setup.presentation.profiles.ProfilesViewModel
import dev.pschmitt.jellyfin.utils.ObserveAsEvents
import java.util.UUID

/**
 * Home header's switcher - replaces the old server-only [dev.pschmitt.jellyfin.models.Server]
 * picker. Lists Profiles (each already bundling a Jellyfin server+user, plus optional
 * Sonarr/Radarr/Seerr overrides) instead of raw servers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSelectionBottomSheet(
    onUpdate: () -> Unit,
    onManage: () -> Unit,
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadProfiles() }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ProfilesEvent.ProfileChanged -> onUpdate()
        }
    }

    ProfileSelectionBottomSheetLayout(
        state = state,
        onManage = onManage,
        onAction = { action -> viewModel.onAction(action) },
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSelectionBottomSheetLayout(
    state: ProfilesState,
    onManage: () -> Unit,
    onAction: (action: ProfilesAction) -> Unit,
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        LazyColumn(
            contentPadding =
                PaddingValues(
                    start = MaterialTheme.spacings.medium,
                    end = MaterialTheme.spacings.medium,
                    bottom = MaterialTheme.spacings.default,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            items(items = state.profiles, key = { it.profile.id }) { profile ->
                ProfileSelectionItem(
                    profile = profile,
                    selected = profile.profile.id == state.currentProfileId,
                    onClick = { onAction(ProfilesAction.OnProfileClick(profile.profile.id)) },
                    modifier = Modifier.fillMaxWidth(),
                    baseUrl = state.serverBaseUrls[profile.serverId].orEmpty(),
                )
            }
            item(key = "manage") {
                OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(CoreR.string.manage_profiles))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
private fun ProfileSelectionBottomSheetPreview() {
    JollyfinTheme {
        ProfileSelectionBottomSheetLayout(
            state = ProfilesState(currentProfileId = UUID.randomUUID()),
            onManage = {},
            onAction = {},
            onDismissRequest = {},
            sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded),
        )
    }
}
