package dev.pschmitt.jellyfin.presentation.settings.pvr

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.pschmitt.jellyfin.api.pvr.PvrAdvancedConfig
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings

/** Result of a one-shot "Test connection" call - surfaced as inline status text, not a Snackbar. */
sealed interface PvrTestState {
    data object Idle : PvrTestState

    data object Testing : PvrTestState

    data class Success(val itemCount: Int) : PvrTestState

    data class Error(val message: String) : PvrTestState
}

/**
 * A single Sonarr/Radarr/Seerr config card, used by `ProfileDetailScreen` (per-profile override,
 * with an inherit/override toggle for non-main profiles). Material 3 tonal card, matching
 * [ProfileSelectionItem][dev.pschmitt.jellyfin.presentation.film.components.ProfileSelectionItem]'s
 * visual language.
 */
@Composable
fun PvrServiceSection(
    nameRes: Int,
    @DrawableRes logoRes: Int,
    // Path under the service's base URL where its web UI shows the API key, e.g.
    // "/settings/general" for Sonarr/Radarr - linked from the section for easier setup.
    apiKeySettingsPath: String,
    enabled: Boolean,
    baseUrl: String,
    apiKey: String,
    // Last-known-good key for this section (e.g. what's already saved server-side) - lets "Test
    // connection" stay usable even when [apiKey] itself is blank (mid-edit, field just cleared).
    storedApiKey: String,
    httpHeaders: String,
    basicAuthUsername: String,
    basicAuthPassword: String,
    testState: PvrTestState,
    onEnabledChanged: (Boolean) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onTestConnectionClick: () -> Unit,
    onAdvancedSettingsChanged: (headers: String, username: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
    // Only rendered for non-main profiles - main always edits its own config directly, there's no
    // "inherit" concept for it.
    showInheritToggle: Boolean = false,
    inheriting: Boolean = false,
    onInheritToggleChanged: (Boolean) -> Unit = {},
) {
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    // While inheriting, fields show main's resolved values as a read-only preview rather than
    // something this profile can edit directly - switch to "custom" first.
    val fieldsEnabled = !inheriting

    Card(
        modifier = modifier.fillMaxWidth(),
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
            // Single header line: logo, name, and the enable toggle right-aligned.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(logoRes),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = stringResource(nameRes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChanged,
                    enabled = fieldsEnabled,
                )
            }

            if (showInheritToggle) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(CoreR.string.profile_custom_config_label),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text =
                                stringResource(
                                    if (inheriting) CoreR.string.profile_inherit_from_main
                                    else CoreR.string.profile_override
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = !inheriting,
                        onCheckedChange = { useCustom -> onInheritToggleChanged(!useCustom) },
                    )
                }
            }

            if (enabled) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChanged,
                    label = { Text(text = stringResource(CoreR.string.integrations_server_url)) },
                    placeholder = {
                        Text(text = stringResource(CoreR.string.integrations_server_url_hint))
                    },
                    singleLine = true,
                    enabled = fieldsEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChanged,
                    label = { Text(text = stringResource(CoreR.string.integrations_api_key)) },
                    singleLine = true,
                    enabled = fieldsEnabled,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(
                            enabled = fieldsEnabled,
                            onClick = {
                                clipboardManager.getText()?.text?.let { pasted ->
                                    onApiKeyChanged(pasted.trim())
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_clipboard_paste),
                                contentDescription =
                                    stringResource(CoreR.string.integrations_paste_api_key),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                PvrAdvancedHttpFields(
                    headers = httpHeaders,
                    basicAuthUsername = basicAuthUsername,
                    basicAuthPassword = basicAuthPassword,
                    enabled = fieldsEnabled,
                    onChanged = onAdvancedSettingsChanged,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Testing works off whatever's currently shown (override or inherited preview
                    // values), so it stays available regardless of inherit/custom mode.
                    Button(
                        onClick = onTestConnectionClick,
                        enabled =
                            testState !is PvrTestState.Testing &&
                                baseUrl.isNotBlank() &&
                                (apiKey.isNotBlank() || storedApiKey.isNotBlank()),
                    ) {
                        Text(text = stringResource(CoreR.string.integrations_test_connection))
                    }
                    TextButton(
                        onClick = {
                            val url = baseUrl.trim().trimEnd('/')
                            uriHandler.openUri(url + apiKeySettingsPath)
                        },
                        enabled = baseUrl.isNotBlank(),
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_globe),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                        Text(text = stringResource(CoreR.string.integrations_get_api_key))
                    }
                }

                when (testState) {
                    is PvrTestState.Success -> {
                        Text(
                            text =
                                stringResource(
                                    CoreR.string.integrations_test_connection_success,
                                    testState.itemCount,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is PvrTestState.Error -> {
                        Text(
                            text =
                                stringResource(
                                    CoreR.string.integrations_test_connection_error,
                                    testState.message,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** One editable name/value row in the custom-headers list, keyed by [id] for stable UI state. */
private data class HeaderEntry(val id: Long, val key: String, val value: String)

private val HeaderEntryListSaver =
    listSaver<SnapshotStateList<HeaderEntry>, Any>(
        save = { list -> list.flatMap { listOf(it.id, it.key, it.value) } },
        restore = { saved ->
            mutableStateListOf<HeaderEntry>().apply {
                saved.chunked(3).forEach { (id, key, value) ->
                    add(HeaderEntry(id as Long, key as String, value as String))
                }
            }
        },
    )

@Composable
fun PvrAdvancedHttpFields(
    headers: String,
    basicAuthUsername: String,
    basicAuthPassword: String,
    onChanged: (headers: String, username: String, password: String) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var nextEntryId by rememberSaveable { mutableStateOf(0L) }
    // Local source of truth while editing - re-derived from `headers` only once, so an in-progress
    // row with a still-empty name (which `parseHeaders` would drop) isn't lost on recomposition.
    val entries =
        rememberSaveable(saver = HeaderEntryListSaver) {
            mutableStateListOf<HeaderEntry>().apply {
                PvrAdvancedConfig.parseHeaders(headers).forEach { (name, value) ->
                    add(HeaderEntry(nextEntryId++, name, value))
                }
            }
        }

    fun emitHeaders() {
        val serialized =
            entries.filter { it.key.isNotBlank() }.joinToString("\n") { "${it.key}: ${it.value}" }
        onChanged(serialized, basicAuthUsername, basicAuthPassword)
    }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(CoreR.string.integrations_advanced_http),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter =
                    painterResource(
                        if (expanded) CoreR.drawable.ic_chevron_up
                        else CoreR.drawable.ic_chevron_down
                    ),
                contentDescription =
                    stringResource(if (expanded) CoreR.string.collapse else CoreR.string.expand),
            )
        }
        if (expanded) {
            Text(
                text = stringResource(CoreR.string.integrations_custom_headers),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entries.forEachIndexed { index, entry ->
                PvrHeaderRow(
                    entry = entry,
                    enabled = enabled,
                    onKeyChanged = { newKey ->
                        entries[index] = entry.copy(key = newKey)
                        emitHeaders()
                    },
                    onValueChanged = { newValue ->
                        entries[index] = entry.copy(value = newValue)
                        emitHeaders()
                    },
                    onRemove = {
                        entries.removeAt(index)
                        emitHeaders()
                    },
                )
            }
            TextButton(
                onClick = { entries.add(HeaderEntry(nextEntryId++, "", "")) },
                enabled = enabled,
            ) {
                Icon(painter = painterResource(CoreR.drawable.ic_plus), contentDescription = null)
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(stringResource(CoreR.string.integrations_custom_headers_add))
            }
            OutlinedTextField(
                value = basicAuthUsername,
                onValueChange = { onChanged(headers, it, basicAuthPassword) },
                label = { Text(stringResource(CoreR.string.integrations_basic_auth_username)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = basicAuthPassword,
                onValueChange = { onChanged(headers, basicAuthUsername, it) },
                label = { Text(stringResource(CoreR.string.integrations_basic_auth_password)) },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A single custom-header name/value row, with the value masked like a password by default. */
@Composable
private fun PvrHeaderRow(
    entry: HeaderEntry,
    enabled: Boolean,
    onKeyChanged: (String) -> Unit,
    onValueChanged: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var valueVisible by rememberSaveable(entry.id) { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalAlignment = Alignment.Top,
    ) {
        OutlinedTextField(
            value = entry.key,
            onValueChange = onKeyChanged,
            label = { Text(stringResource(CoreR.string.integrations_custom_header_name)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = entry.value,
            onValueChange = onValueChanged,
            label = { Text(stringResource(CoreR.string.integrations_custom_header_value)) },
            singleLine = true,
            enabled = enabled,
            visualTransformation =
                if (valueVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { valueVisible = !valueVisible }) {
                    Icon(
                        painter =
                            painterResource(
                                if (valueVisible) CoreR.drawable.ic_eye_off
                                else CoreR.drawable.ic_eye
                            ),
                        contentDescription = null,
                    )
                }
            },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_trash),
                contentDescription = stringResource(CoreR.string.remove),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
@Preview
private fun PvrServiceSectionOverridePreview() {
    JollyfinTheme {
        PvrServiceSection(
            nameRes = CoreR.string.integrations_sonarr,
            logoRes = CoreR.drawable.ic_sonarr,
            apiKeySettingsPath = "/settings/general",
            enabled = true,
            baseUrl = "https://sonarr.example.com",
            apiKey = "abc123",
            storedApiKey = "abc123",
            httpHeaders = "",
            basicAuthUsername = "",
            basicAuthPassword = "",
            testState = PvrTestState.Success(42),
            onEnabledChanged = {},
            onBaseUrlChanged = {},
            onApiKeyChanged = {},
            onTestConnectionClick = {},
            onAdvancedSettingsChanged = { _, _, _ -> },
            showInheritToggle = true,
            inheriting = false,
        )
    }
}

@Composable
@Preview
private fun PvrServiceSectionInheritingPreview() {
    JollyfinTheme {
        PvrServiceSection(
            nameRes = CoreR.string.integrations_radarr,
            logoRes = CoreR.drawable.ic_radarr,
            apiKeySettingsPath = "/settings/general",
            enabled = true,
            baseUrl = "https://radarr.example.com",
            apiKey = "xyz789",
            storedApiKey = "xyz789",
            httpHeaders = "",
            basicAuthUsername = "",
            basicAuthPassword = "",
            testState = PvrTestState.Idle,
            onEnabledChanged = {},
            onBaseUrlChanged = {},
            onApiKeyChanged = {},
            onTestConnectionClick = {},
            onAdvancedSettingsChanged = { _, _, _ -> },
            showInheritToggle = true,
            inheriting = true,
        )
    }
}
