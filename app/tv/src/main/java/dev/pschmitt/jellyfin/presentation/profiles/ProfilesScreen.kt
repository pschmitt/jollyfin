package dev.pschmitt.jellyfin.presentation.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.models.Profile
import dev.pschmitt.jellyfin.models.ProfileWithUserAndServer
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.setup.R as SetupR
import dev.pschmitt.jellyfin.setup.presentation.profiles.ProfilesAction
import dev.pschmitt.jellyfin.setup.presentation.profiles.ProfilesEvent
import dev.pschmitt.jellyfin.setup.presentation.profiles.ProfilesState
import dev.pschmitt.jellyfin.setup.presentation.profiles.ProfilesViewModel
import dev.pschmitt.jellyfin.utils.ObserveAsEvents
import java.util.UUID

/**
 * TV Settings > Profiles screen, also reachable directly from
 * [dev.pschmitt.jellyfin.ui.MainScreen]'s repurposed profile button as a home-screen quick
 * switcher. Reuses the shared [ProfilesViewModel] (same one app/phone uses) for
 * listing/switching/creating profiles.
 *
 * Since TV only has this one screen for both "switch profile" and "manage profiles" (unlike phone's
 * bottom-sheet-vs-settings-list split), each row's primary click switches the active profile, and a
 * separate sibling "Manage" button opens [ProfileDetailScreen] for rename/set-as-main/delete/PVR
 * overrides - kept as a sibling surface rather than nested inside the profile card itself, matching
 * how the rest of this app avoids nesting one focusable D-pad target inside another.
 */
@Composable
fun ProfilesScreen(
    navigateBack: () -> Unit,
    navigateToProfileDetail: (profileId: String) -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(true) { viewModel.loadProfiles() }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            // Switching profile repoints the active server/user - head back to Home so it picks
            // up the new profile on recomposition (MainScreen reloads on every fresh entry).
            is ProfilesEvent.ProfileChanged -> navigateBack()
        }
    }

    ProfilesScreenLayout(
        state = state,
        navigateToProfileDetail = navigateToProfileDetail,
        onAction = viewModel::onAction,
        onProfileCreated = { viewModel.loadProfiles() },
    )
}

@Composable
private fun ProfilesScreenLayout(
    state: ProfilesState,
    navigateToProfileDetail: (profileId: String) -> Unit,
    onAction: (ProfilesAction) -> Unit,
    onProfileCreated: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
        ) {
            Text(
                text = stringResource(id = CoreR.string.manage_profiles),
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
            Text(
                text = stringResource(id = CoreR.string.profile_tv_switch_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))
            if (state.profiles.isEmpty()) {
                Text(
                    text = stringResource(id = CoreR.string.profile_empty_list),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
                    contentPadding = PaddingValues(horizontal = MaterialTheme.spacings.default),
                ) {
                    items(state.profiles, key = { it.profile.id }) { profile ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            ProfileItem(
                                profile = profile,
                                selected = profile.profile.id == state.currentProfileId,
                                onClick = {
                                    onAction(ProfilesAction.OnProfileClick(profile.profile.id))
                                },
                                serverVersion = state.serverVersions[profile.serverId],
                            )
                            ManageProfileButton(
                                onClick = { navigateToProfileDetail(profile.profile.id.toString()) }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))
            OutlinedButton(onClick = { showAddDialog = true }) {
                Text(text = stringResource(id = CoreR.string.profile_new))
            }
        }
    }

    if (showAddDialog) {
        AddProfileDialog(
            existingUserIds = state.profiles.map { it.profile.userId }.toSet(),
            onDismissRequest = { showAddDialog = false },
            onProfileCreated = {
                showAddDialog = false
                onProfileCreated()
            },
        )
    }
}

/**
 * Small square "manage this profile" affordance, sibling to [ProfileItem] rather than nested in it.
 */
@Composable
private fun ManageProfileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF132026),
                focusedContainerColor = Color(0xFF132026),
            ),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(4.dp, Color.White), shape = CircleShape)
            ),
        modifier = modifier.width(48.dp).height(48.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_more_vertical),
                contentDescription = stringResource(CoreR.string.profile_manage),
                modifier = Modifier.width(20.dp).height(20.dp),
            )
        }
    }
}

@Composable
private fun AddProfileDialog(
    existingUserIds: Set<UUID>,
    onDismissRequest: () -> Unit,
    onProfileCreated: () -> Unit,
    viewModel: AddProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(existingUserIds) { viewModel.load(existingUserIds) }

    AlertDialog(
        title = { Text(text = stringResource(CoreR.string.profile_pick_user_title)) },
        text = {
            when {
                state.loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.large),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.eligibleUsers.isEmpty() -> {
                    Text(text = stringResource(CoreR.string.profile_no_eligible_users))
                }
                else -> {
                    Column(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    ) {
                        state.eligibleUsers.forEach { eligible ->
                            EligibleUserRow(
                                userName = eligible.user.name,
                                serverName = eligible.serverName,
                                enabled = !state.creating,
                                onClick = {
                                    viewModel.createProfile(eligible, onCreated = onProfileCreated)
                                },
                            )
                        }
                    }
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(SetupR.string.cancel))
            }
        },
    )
}

@Composable
private fun EligibleUserRow(
    userName: String,
    serverName: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium)) {
            Text(text = userName, style = MaterialTheme.typography.titleSmall)
            Text(
                text = serverName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun ProfilesScreenLayoutPreview() {
    JollyfinTheme {
        val profiles =
            listOf(
                ProfileWithUserAndServer(
                    profile =
                        Profile(
                            id = UUID.randomUUID(),
                            name = "jelly",
                            userId = UUID.randomUUID(),
                            isMain = true,
                        ),
                    userName = "jelly",
                    serverId = "server-1",
                    serverName = "Home Server",
                ),
                ProfileWithUserAndServer(
                    profile =
                        Profile(id = UUID.randomUUID(), name = "kiddo", userId = UUID.randomUUID()),
                    userName = "kiddo",
                    serverId = "server-1",
                    serverName = "Home Server",
                ),
            )
        ProfilesScreenLayout(
            state =
                ProfilesState(profiles = profiles, currentProfileId = profiles.first().profile.id),
            navigateToProfileDetail = {},
            onAction = {},
            onProfileCreated = {},
        )
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun ProfilesScreenLayoutEmptyPreview() {
    JollyfinTheme {
        ProfilesScreenLayout(
            state = ProfilesState(),
            navigateToProfileDetail = {},
            onAction = {},
            onProfileCreated = {},
        )
    }
}
