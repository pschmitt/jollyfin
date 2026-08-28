package dev.pschmitt.jellyfin.presentation.settings.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.models.Profile
import dev.pschmitt.jellyfin.models.ProfileWithUserAndServer
import dev.pschmitt.jellyfin.models.ServerWithAddresses
import dev.pschmitt.jellyfin.presentation.components.TopBarTitle
import dev.pschmitt.jellyfin.presentation.film.components.ProfileSelectionItem
import dev.pschmitt.jellyfin.presentation.setup.components.DiscoveredServerItem
import dev.pschmitt.jellyfin.presentation.setup.components.LoadingButton
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.setup.R as SetupR
import dev.pschmitt.jellyfin.setup.presentation.profiles.ProfilesViewModel
import java.util.UUID

@Composable
fun ProfilesListScreen(
    navigateBack: () -> Unit,
    navigateToProfileDetail: (profileId: String) -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadProfiles() }

    ProfilesListScreenLayout(
        profiles = state.profiles,
        currentProfileId = state.currentProfileId,
        serverBaseUrls = state.serverBaseUrls,
        navigateBack = navigateBack,
        navigateToProfileDetail = navigateToProfileDetail,
        onProfileCreated = { viewModel.loadProfiles() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilesListScreenLayout(
    profiles: List<ProfileWithUserAndServer>,
    currentProfileId: UUID?,
    serverBaseUrls: Map<String, String> = emptyMap(),
    navigateBack: () -> Unit,
    navigateToProfileDetail: (profileId: String) -> Unit,
    onProfileCreated: () -> Unit,
) {
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    TopBarTitle(
                        text = stringResource(CoreR.string.manage_profiles),
                        iconRes = CoreR.drawable.ic_user,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(painterResource(CoreR.drawable.ic_plus), contentDescription = null) },
                text = { Text(text = stringResource(CoreR.string.profile_new)) },
            )
        },
    ) { innerPadding ->
        if (profiles.isEmpty()) {
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .padding(innerPadding)
                        .padding(MaterialTheme.spacings.large),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(CoreR.string.profile_empty_list),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                items(profiles, key = { it.profile.id }) { profile ->
                    ProfileSelectionItem(
                        profile = profile,
                        selected = profile.profile.id == currentProfileId,
                        onClick = { navigateToProfileDetail(profile.profile.id.toString()) },
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = MaterialTheme.spacings.medium),
                        baseUrl = serverBaseUrls[profile.serverId].orEmpty(),
                    )
                }
                // Trailing space so the last card isn't obscured by the FAB.
                item { Spacer(modifier = Modifier.height(88.dp)) }
            }
        }
    }

    if (showAddSheet) {
        AddProfileBottomSheet(
            existingUserIds = profiles.map { it.profile.userId }.toSet(),
            onDismissRequest = { showAddSheet = false },
            onProfileCreated = {
                showAddSheet = false
                onProfileCreated()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProfileBottomSheet(
    existingUserIds: Set<UUID>,
    onDismissRequest: () -> Unit,
    onProfileCreated: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    viewModel: AddProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(existingUserIds) { viewModel.load(existingUserIds) }

    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacings.medium)
                    .padding(bottom = MaterialTheme.spacings.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            Text(
                text = stringResource(CoreR.string.profile_pick_user_title),
                style = MaterialTheme.typography.titleLarge,
            )

            if (state.loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.large),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                if (state.eligibleUsers.isEmpty()) {
                    Text(
                        text = stringResource(CoreR.string.profile_no_eligible_users),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
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

                Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))

                AddServerAndLoginSection(
                    state = state,
                    initiallyExpanded = state.eligibleUsers.isEmpty(),
                    onServerSelected = viewModel::selectServer,
                    onAddServer = viewModel::addServer,
                    onLogin = { username, password ->
                        viewModel.login(username, password, onCreated = onProfileCreated)
                    },
                    onQuickConnect = { viewModel.quickConnect(onCreated = onProfileCreated) },
                )
            }
        }
    }
}

@Composable
private fun EligibleUserRow(
    userName: String,
    serverName: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = userName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(modifier = Modifier.size(MaterialTheme.spacings.medium))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = serverName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Replaces the old dead-end "navigate to Connections" button - lets you connect a brand-new server
 * (with discovery, mirroring the original setup flow) or pick an existing server and log in as a
 * new user on it, all inline, ending with a freshly created + activated profile.
 */
@Composable
private fun AddServerAndLoginSection(
    state: AddProfileState,
    initiallyExpanded: Boolean,
    onServerSelected: (String) -> Unit,
    onAddServer: (String) -> Unit,
    onLogin: (String, String) -> Unit,
    onQuickConnect: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var newAddress by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(CoreR.string.profile_add_new_server_toggle),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter =
                painterResource(
                    if (expanded) CoreR.drawable.ic_chevron_up else CoreR.drawable.ic_chevron_down
                ),
            contentDescription =
                stringResource(if (expanded) CoreR.string.collapse else CoreR.string.expand),
        )
    }

    AnimatedVisibility(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
            if (state.currentServerId == null) {
                Text(
                    text = stringResource(CoreR.string.profile_select_server_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.servers.forEach { server ->
                    ServerRow(server = server, onClick = { onServerSelected(server.server.id) })
                }

                if (state.discoveredServers.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.discoveredServers) { discovered ->
                            DiscoveredServerItem(
                                name = discovered.name,
                                onClick = {
                                    newAddress = discovered.address
                                    onAddServer(discovered.address)
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = newAddress,
                    onValueChange = { newAddress = it },
                    label = { Text(stringResource(SetupR.string.edit_text_server_address_hint)) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions =
                        KeyboardActions(
                            onGo = { if (newAddress.isNotBlank()) onAddServer(newAddress) }
                        ),
                    isError = state.addServerError != null,
                    enabled = !state.operationInProgress,
                    supportingText = {
                        state.addServerError?.let {
                            Text(text = it.asString(), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                LoadingButton(
                    text = stringResource(SetupR.string.add_server_btn_connect),
                    onClick = { if (newAddress.isNotBlank()) onAddServer(newAddress) },
                    isLoading = state.operationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = stringResource(CoreR.string.profile_login_new_user_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(SetupR.string.edit_text_username_hint)) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(SetupR.string.edit_text_password_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (passwordVisible) CoreR.drawable.ic_eye_off
                                        else CoreR.drawable.ic_eye
                                    ),
                                contentDescription = null,
                            )
                        }
                    },
                    visualTransformation =
                        if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Go,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onGo = {
                                if (username.isNotBlank() && password.isNotBlank()) {
                                    onLogin(username, password)
                                }
                            }
                        ),
                    isError = state.loginError != null,
                    enabled = !state.operationInProgress,
                    supportingText = {
                        state.loginError?.let {
                            Text(text = it.asString(), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                LoadingButton(
                    text = stringResource(SetupR.string.login_btn_login),
                    onClick = { onLogin(username, password) },
                    isLoading = state.operationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
                AnimatedVisibility(state.quickConnectEnabled) {
                    Column {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            )
                            Text(
                                text = stringResource(SetupR.string.or),
                                color = DividerDefaults.color,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box {
                            if (state.quickConnectCode != null) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier =
                                        Modifier.size(20.dp)
                                            .align(Alignment.CenterStart)
                                            .padding(start = 8.dp),
                                )
                            }
                            OutlinedButton(
                                onClick = onQuickConnect,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text =
                                        state.quickConnectCode
                                            ?: stringResource(SetupR.string.login_btn_quick_connect)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(server: ServerWithAddresses, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_server),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.server.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                server.addresses.firstOrNull()?.let { address ->
                    Text(
                        text = address.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
@Preview
private fun ProfilesListScreenLayoutPreview() {
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
        ProfilesListScreenLayout(
            profiles = profiles,
            currentProfileId = profiles.first().profile.id,
            navigateBack = {},
            navigateToProfileDetail = {},
            onProfileCreated = {},
        )
    }
}

@Composable
@Preview
private fun ProfilesListScreenLayoutEmptyPreview() {
    JollyfinTheme {
        ProfilesListScreenLayout(
            profiles = emptyList(),
            currentProfileId = null,
            navigateBack = {},
            navigateToProfileDetail = {},
            onProfileCreated = {},
        )
    }
}
