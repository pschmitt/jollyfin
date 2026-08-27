package dev.pschmitt.jellyfin.presentation.settings.profiles

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.models.ServerAddress
import dev.pschmitt.jellyfin.models.UiText
import dev.pschmitt.jellyfin.models.User
import dev.pschmitt.jellyfin.presentation.components.TopBarTitle
import dev.pschmitt.jellyfin.presentation.settings.pvr.PvrServiceSection
import dev.pschmitt.jellyfin.presentation.settings.pvr.PvrTestState
import dev.pschmitt.jellyfin.presentation.setup.components.LoadingButton
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.setup.R as SetupR
import java.util.UUID

@Composable
fun ProfileDetailScreen(
    profileId: String,
    navigateBack: () -> Unit,
    viewModel: ProfileDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(profileId) { viewModel.load(profileId) }

    LaunchedEffect(state.deleted) { if (state.deleted) navigateBack() }

    LaunchedEffect(state.scanLibraryMessage) {
        state.scanLibraryMessage?.let {
            Toast.makeText(context, it.asString(context.resources), Toast.LENGTH_LONG).show()
        }
    }

    ProfileDetailScreenLayout(
        state = state,
        navigateBack = navigateBack,
        onAction = viewModel::onAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDetailScreenLayout(
    state: ProfileDetailState,
    navigateBack: () -> Unit,
    onAction: (ProfileDetailAction) -> Unit,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { TopBarTitle(text = state.name.ifBlank { "…" }) },
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
    ) { innerPadding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacings.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                ProfileHeaderCard(
                    name = state.name,
                    isMain = state.isMain,
                    onRenameClick = { showRenameDialog = true },
                    onSetAsMainClick = { onAction(ProfileDetailAction.OnSetAsMainClick) },
                    onDeleteClick = { showDeleteDialog = true },
                )

                ServerAddressCard(
                    serverName = state.serverName,
                    addresses = state.addresses,
                    currentAddressId = state.currentAddressId,
                    inProgress = state.addressOperationInProgress,
                    addAddressError = state.addAddressError,
                    onAddressSelected = { onAction(ProfileDetailAction.OnAddressSelected(it)) },
                    onAddAddress = { onAction(ProfileDetailAction.OnAddAddressClick(it)) },
                )

                ScanLibraryCard(
                    isActiveProfile = state.isActiveProfile,
                    isAdministrator = state.isAdministrator,
                    scanning = state.scanningLibrary,
                    onScanClick = { onAction(ProfileDetailAction.OnScanLibraryClick) },
                )

                JellyfinUserCard(
                    currentUserName = state.currentUserName,
                    otherUsers = state.otherUsers,
                    inProgress = state.userOperationInProgress,
                    loginError = state.loginError,
                    quickConnectEnabled = state.quickConnectEnabled,
                    quickConnectCode = state.quickConnectCode,
                    onUserSelected = { onAction(ProfileDetailAction.OnUserSelected(it)) },
                    onLogin = { username, password ->
                        onAction(ProfileDetailAction.OnLoginClick(username, password))
                    },
                    onQuickConnect = { onAction(ProfileDetailAction.OnQuickConnectClick) },
                )

                PvrServiceSection(
                    nameRes = CoreR.string.integrations_sonarr,
                    logoRes = CoreR.drawable.ic_sonarr,
                    apiKeySettingsPath = "/settings/general",
                    enabled = state.sonarr.enabled,
                    baseUrl = state.sonarr.baseUrl,
                    apiKey = state.sonarr.apiKey,
                    storedApiKey = state.sonarr.storedApiKey,
                    httpHeaders = state.sonarr.httpHeaders,
                    basicAuthUsername = state.sonarr.basicAuthUsername,
                    basicAuthPassword = state.sonarr.basicAuthPassword,
                    testState = state.sonarr.testState,
                    showInheritToggle = !state.isMain,
                    inheriting = !state.isMain && state.sonarr.inheriting,
                    onInheritToggleChanged = {
                        onAction(ProfileDetailAction.OnToggleInherit(PvrService.SONARR, it))
                    },
                    onEnabledChanged = {
                        onAction(ProfileDetailAction.OnEnabledChanged(PvrService.SONARR, it))
                    },
                    onBaseUrlChanged = {
                        onAction(ProfileDetailAction.OnBaseUrlChanged(PvrService.SONARR, it))
                    },
                    onApiKeyChanged = {
                        onAction(ProfileDetailAction.OnApiKeyChanged(PvrService.SONARR, it))
                    },
                    onTestConnectionClick = {
                        onAction(ProfileDetailAction.OnTestConnectionClick(PvrService.SONARR))
                    },
                    onAdvancedSettingsChanged = { headers, username, password ->
                        onAction(
                            ProfileDetailAction.OnAdvancedSettingsChanged(
                                PvrService.SONARR,
                                headers,
                                username,
                                password,
                            )
                        )
                    },
                )

                PvrServiceSection(
                    nameRes = CoreR.string.integrations_radarr,
                    logoRes = CoreR.drawable.ic_radarr,
                    apiKeySettingsPath = "/settings/general",
                    enabled = state.radarr.enabled,
                    baseUrl = state.radarr.baseUrl,
                    apiKey = state.radarr.apiKey,
                    storedApiKey = state.radarr.storedApiKey,
                    httpHeaders = state.radarr.httpHeaders,
                    basicAuthUsername = state.radarr.basicAuthUsername,
                    basicAuthPassword = state.radarr.basicAuthPassword,
                    testState = state.radarr.testState,
                    showInheritToggle = !state.isMain,
                    inheriting = !state.isMain && state.radarr.inheriting,
                    onInheritToggleChanged = {
                        onAction(ProfileDetailAction.OnToggleInherit(PvrService.RADARR, it))
                    },
                    onEnabledChanged = {
                        onAction(ProfileDetailAction.OnEnabledChanged(PvrService.RADARR, it))
                    },
                    onBaseUrlChanged = {
                        onAction(ProfileDetailAction.OnBaseUrlChanged(PvrService.RADARR, it))
                    },
                    onApiKeyChanged = {
                        onAction(ProfileDetailAction.OnApiKeyChanged(PvrService.RADARR, it))
                    },
                    onTestConnectionClick = {
                        onAction(ProfileDetailAction.OnTestConnectionClick(PvrService.RADARR))
                    },
                    onAdvancedSettingsChanged = { headers, username, password ->
                        onAction(
                            ProfileDetailAction.OnAdvancedSettingsChanged(
                                PvrService.RADARR,
                                headers,
                                username,
                                password,
                            )
                        )
                    },
                )

                PvrServiceSection(
                    nameRes = CoreR.string.integrations_seerr,
                    logoRes = CoreR.drawable.ic_seerr,
                    apiKeySettingsPath = "/settings",
                    enabled = state.seerr.enabled,
                    baseUrl = state.seerr.baseUrl,
                    apiKey = state.seerr.apiKey,
                    storedApiKey = state.seerr.storedApiKey,
                    httpHeaders = state.seerr.httpHeaders,
                    basicAuthUsername = state.seerr.basicAuthUsername,
                    basicAuthPassword = state.seerr.basicAuthPassword,
                    testState = state.seerr.testState,
                    showInheritToggle = !state.isMain,
                    inheriting = !state.isMain && state.seerr.inheriting,
                    onInheritToggleChanged = {
                        onAction(ProfileDetailAction.OnToggleInherit(PvrService.SEERR, it))
                    },
                    onEnabledChanged = {
                        onAction(ProfileDetailAction.OnEnabledChanged(PvrService.SEERR, it))
                    },
                    onBaseUrlChanged = {
                        onAction(ProfileDetailAction.OnBaseUrlChanged(PvrService.SEERR, it))
                    },
                    onApiKeyChanged = {
                        onAction(ProfileDetailAction.OnApiKeyChanged(PvrService.SEERR, it))
                    },
                    onTestConnectionClick = {
                        onAction(ProfileDetailAction.OnTestConnectionClick(PvrService.SEERR))
                    },
                    onAdvancedSettingsChanged = { headers, username, password ->
                        onAction(
                            ProfileDetailAction.OnAdvancedSettingsChanged(
                                PvrService.SEERR,
                                headers,
                                username,
                                password,
                            )
                        )
                    },
                )
            }
        }
    }

    if (showRenameDialog) {
        RenameProfileDialog(
            currentName = state.name,
            onDismissRequest = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onAction(ProfileDetailAction.OnRenameConfirmed(newName))
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            title = { Text(text = stringResource(CoreR.string.profile_delete)) },
            text = {
                Text(text = stringResource(CoreR.string.profile_delete_confirm_text, state.name))
            },
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onAction(ProfileDetailAction.OnDeleteClick)
                    }
                ) {
                    Text(text = stringResource(CoreR.string.profile_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = stringResource(SetupR.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ProfileHeaderCard(
    name: String,
    isMain: Boolean,
    onRenameClick: () -> Unit,
    onSetAsMainClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier.size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = name.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(modifier = Modifier.size(MaterialTheme.spacings.medium))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isMain) {
                            Spacer(modifier = Modifier.size(MaterialTheme.spacings.small))
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = {
                                    Text(text = stringResource(CoreR.string.profile_main_badge))
                                },
                                colors =
                                    AssistChipDefaults.assistChipColors(
                                        disabledContainerColor =
                                            MaterialTheme.colorScheme.tertiaryContainer,
                                        disabledLabelColor =
                                            MaterialTheme.colorScheme.onTertiaryContainer,
                                    ),
                                border = null,
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                TextButton(onClick = onRenameClick) {
                    Text(text = stringResource(CoreR.string.profile_rename))
                }
                if (!isMain) {
                    TextButton(onClick = onSetAsMainClick) {
                        Text(text = stringResource(CoreR.string.profile_set_as_main))
                    }
                }
                TextButton(
                    onClick = onDeleteClick,
                    enabled = !isMain,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text(text = stringResource(CoreR.string.profile_delete))
                }
            }

            if (isMain) {
                Text(
                    text = stringResource(CoreR.string.profile_delete_main_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ServerAddressCard(
    serverName: String,
    addresses: List<ServerAddress>,
    currentAddressId: UUID?,
    inProgress: Boolean,
    addAddressError: UiText?,
    onAddressSelected: (UUID) -> Unit,
    onAddAddress: (String) -> Unit,
) {
    var newAddress by rememberSaveable { mutableStateOf("") }
    // Once an address is successfully added the list grows - clear the input instead of leaving
    // a stale value sitting in the field under the address it just created.
    var previousAddressCount by remember { mutableIntStateOf(addresses.size) }
    LaunchedEffect(addresses.size) {
        if (addresses.size > previousAddressCount) newAddress = ""
        previousAddressCount = addresses.size
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            Text(
                text = stringResource(CoreR.string.profile_server_section_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = serverName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))

            addresses.forEach { address ->
                AddressRow(
                    address = address.address,
                    selected = address.id == currentAddressId,
                    enabled = !inProgress,
                    onClick = { onAddressSelected(address.id) },
                )
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
                        onGo = { if (newAddress.isNotBlank()) onAddAddress(newAddress) }
                    ),
                isError = addAddressError != null,
                enabled = !inProgress,
                supportingText = {
                    addAddressError?.let {
                        Text(text = it.asString(), color = MaterialTheme.colorScheme.error)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LoadingButton(
                text = stringResource(CoreR.string.profile_add_address),
                onClick = { if (newAddress.isNotBlank()) onAddAddress(newAddress) },
                isLoading = inProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ScanLibraryCard(
    isActiveProfile: Boolean,
    isAdministrator: Boolean,
    scanning: Boolean,
    onScanClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            Text(
                text = stringResource(CoreR.string.scan_libraries_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(CoreR.string.scan_libraries_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isActiveProfile) {
                Text(
                    text = stringResource(CoreR.string.scan_libraries_inactive_profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!isAdministrator) {
                Text(
                    text = stringResource(CoreR.string.scan_libraries_admin_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onScanClick,
                enabled = isActiveProfile && isAdministrator && !scanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(CoreR.string.scan_libraries_button))
            }
        }
    }
}

@Composable
private fun AddressRow(address: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 1.dp else 0.dp),
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
            Text(
                text = address,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun JellyfinUserCard(
    currentUserName: String,
    otherUsers: List<User>,
    inProgress: Boolean,
    loginError: UiText?,
    quickConnectEnabled: Boolean,
    quickConnectCode: String?,
    onUserSelected: (UUID) -> Unit,
    onLogin: (String, String) -> Unit,
    onQuickConnect: () -> Unit,
) {
    var showLoginForm by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    // Once the reassignment succeeds the signed-in name changes - fold the login form back away
    // instead of leaving it dangling open below the user who just became current.
    LaunchedEffect(currentUserName) {
        if (showLoginForm) {
            showLoginForm = false
            username = ""
            password = ""
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            Text(
                text = stringResource(CoreR.string.profile_user_section_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text =
                    stringResource(
                        CoreR.string.integrations_jellyfin_signed_in_as,
                        currentUserName,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (otherUsers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                otherUsers.forEach { user ->
                    UserRow(
                        name = user.name,
                        enabled = !inProgress,
                        onClick = { onUserSelected(user.id) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().clickable { showLoginForm = !showLoginForm },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(CoreR.string.profile_login_different_user),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter =
                        painterResource(
                            if (showLoginForm) CoreR.drawable.ic_chevron_up
                            else CoreR.drawable.ic_chevron_down
                        ),
                    contentDescription =
                        stringResource(
                            if (showLoginForm) CoreR.string.collapse else CoreR.string.expand
                        ),
                )
            }

            AnimatedVisibility(showLoginForm) {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(SetupR.string.edit_text_username_hint)) },
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Next,
                            ),
                        enabled = !inProgress,
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
                        isError = loginError != null,
                        enabled = !inProgress,
                        supportingText = {
                            loginError?.let {
                                Text(text = it.asString(), color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LoadingButton(
                        text = stringResource(SetupR.string.login_btn_login),
                        onClick = { onLogin(username, password) },
                        isLoading = inProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AnimatedVisibility(quickConnectEnabled) {
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
                                if (quickConnectCode != null) {
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
                                            quickConnectCode
                                                ?: stringResource(
                                                    SetupR.string.login_btn_quick_connect
                                                )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserRow(name: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 4.dp),
    ) {
        Box(
            modifier =
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RenameProfileDialog(
    currentName: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        title = { Text(text = stringResource(CoreR.string.profile_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(text = stringResource(CoreR.string.profile_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) {
                Text(text = stringResource(SetupR.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(SetupR.string.cancel))
            }
        },
    )
}

@Composable
@Preview
private fun ProfileDetailScreenLayoutPreview() {
    JollyfinTheme {
        ProfileDetailScreenLayout(
            state =
                ProfileDetailState(
                    loading = false,
                    name = "kiddo",
                    isMain = false,
                    sonarr =
                        PvrSectionState(
                            inheriting = true,
                            enabled = true,
                            baseUrl = "https://sonarr.example.com",
                            apiKey = "abc123",
                        ),
                    radarr = PvrSectionState(inheriting = false, enabled = false),
                    seerr =
                        PvrSectionState(
                            inheriting = false,
                            enabled = true,
                            baseUrl = "https://seerr.example.com",
                            apiKey = "xyz789",
                            testState = PvrTestState.Success(3),
                        ),
                    serverName = "Home Server",
                    addresses =
                        listOf(
                            ServerAddress(
                                id = UUID.randomUUID(),
                                serverId = "server-1",
                                address = "https://home.example.com",
                            )
                        ),
                    currentUserName = "kiddo",
                    otherUsers =
                        listOf(User(id = UUID.randomUUID(), name = "jelly", serverId = "server-1")),
                ),
            navigateBack = {},
            onAction = {},
        )
    }
}

@Composable
@Preview
private fun ProfileDetailScreenLayoutMainPreview() {
    JollyfinTheme {
        ProfileDetailScreenLayout(
            state =
                ProfileDetailState(
                    loading = false,
                    name = "jelly",
                    isMain = true,
                    sonarr =
                        PvrSectionState(enabled = true, baseUrl = "https://sonarr.example.com"),
                    serverName = "Home Server",
                    addresses =
                        listOf(
                            ServerAddress(
                                id = UUID.randomUUID(),
                                serverId = "server-1",
                                address = "https://home.example.com",
                            ),
                            ServerAddress(
                                id = UUID.randomUUID(),
                                serverId = "server-1",
                                address = "http://192.168.1.10:8096",
                            ),
                        ),
                    currentUserName = "jelly",
                    quickConnectEnabled = true,
                ),
            navigateBack = {},
            onAction = {},
        )
    }
}
