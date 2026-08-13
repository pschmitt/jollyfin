package dev.pschmitt.jellyfin.presentation.settings.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.presentation.components.TopBarTitle
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.settings.R as SettingsR
import dev.pschmitt.jellyfin.utils.NavigationBarItemKeys
import kotlinx.coroutines.delay

@Composable
fun NavigationBarSettingsScreen(
    navigateBack: () -> Unit,
    viewModel: NavigationBarSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    var searchQuery by remember { mutableStateOf("") }
    var editingRow by remember { mutableStateOf<NavigationBarRow?>(null) }
    var editingLabel by remember { mutableStateOf("") }
    var editingShowImage by remember { mutableStateOf(true) }

    LaunchedEffect(searchQuery) {
        delay(250)
        viewModel.search(searchQuery)
    }

    NavigationBarSettingsScreenLayout(
        state = state,
        navigateBack = navigateBack,
        onMove = viewModel::move,
        onHide = viewModel::hide,
        onRestore = viewModel::restore,
        onReset = viewModel::reset,
        onEdit = { row ->
            editingRow = row
            editingLabel = row.titleText.orEmpty()
            editingShowImage = row.showImage
        },
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        onAdd = viewModel::add,
    )

    editingRow?.let { row ->
        AlertDialog(
            onDismissRequest = { editingRow = null },
            title = {
                Text(stringResource(SettingsR.string.settings_navigation_bar_edit_label_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                    OutlinedTextField(
                        value = editingLabel,
                        onValueChange = { editingLabel = it },
                        singleLine = true,
                        label = {
                            Text(stringResource(SettingsR.string.settings_navigation_bar_label))
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = editingShowImage,
                            onCheckedChange = { editingShowImage = it },
                        )
                        Text(
                            text =
                                stringResource(
                                    SettingsR.string.settings_navigation_bar_show_cover_art
                                )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updatePinnedItem(row.key, editingLabel, editingShowImage)
                        editingRow = null
                    },
                    enabled = editingLabel.isNotBlank(),
                ) {
                    Text(stringResource(CoreR.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingRow = null }) {
                    Text(stringResource(CoreR.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationBarSettingsScreenLayout(
    state: NavigationBarSettingsState,
    navigateBack: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onHide: (String) -> Unit,
    onRestore: (String) -> Unit,
    onReset: () -> Unit,
    onEdit: (NavigationBarRow) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onAdd: (JollyfinItem) -> Unit,
) {
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    TopBarTitle(
                        text = stringResource(SettingsR.string.settings_category_navigation_bar),
                        iconRes = SettingsR.drawable.ic_list,
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
                actions = {
                    IconButton(onClick = { showResetConfirm = true }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                            contentDescription = stringResource(CoreR.string.home_layout_reset),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            item { NavigationBarPreview(state.rows) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.default),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_search),
                                contentDescription = null,
                            )
                        },
                        label = {
                            Text(stringResource(SettingsR.string.settings_navigation_bar_search))
                        },
                    )
                }
            }
            if (state.searchResults.isNotEmpty()) {
                item {
                    Text(
                        text =
                            stringResource(SettingsR.string.settings_navigation_bar_search_results),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacings.default),
                    )
                }
                items(state.searchResults, key = { "search:${it.id}" }) { item ->
                    NavigationBarRowItem(
                        row =
                            NavigationBarRow(
                                key = "search:${item.id}",
                                titleText = item.name,
                                icon = CoreR.drawable.ic_star,
                                imageUri =
                                    (item.images.primary ?: item.images.showPrimary)?.toString(),
                                showImage = true,
                            ),
                        canMoveUp = false,
                        canMoveDown = false,
                        onMoveUp = {},
                        onMoveDown = {},
                        onTrailingClick = { onAdd(item) },
                        trailingIcon = CoreR.drawable.ic_plus,
                        trailingDescription =
                            stringResource(SettingsR.string.settings_navigation_bar_add),
                        showMoveButtons = false,
                        imageUri =
                            (item.images.primary ?: item.images.showPrimary)?.toString(),
                        iconModifier = Modifier.size(48.dp, 72.dp),
                    )
                }
            }
            item {
                Text(
                    text = stringResource(SettingsR.string.settings_navigation_bar_visible),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(MaterialTheme.spacings.default),
                )
            }
            items(state.rows, key = { it.key }) { row ->
                val index = state.rows.indexOf(row)
                NavigationBarRowItem(
                    row = row,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.rows.lastIndex,
                    onMoveUp = { onMove(index, index - 1) },
                    onMoveDown = { onMove(index, index + 1) },
                    onTrailingClick = { onHide(row.key) },
                    onEdit = if (row.key.startsWith("item:")) ({ onEdit(row) }) else null,
                    trailingIcon =
                        if (row.key == NavigationBarItemKeys.HOME) CoreR.drawable.ic_lock
                        else CoreR.drawable.ic_eye_off,
                    trailingDescription =
                        if (row.key == NavigationBarItemKeys.HOME) {
                            stringResource(SettingsR.string.settings_navigation_bar_required)
                        } else {
                            stringResource(CoreR.string.home_layout_hide_section)
                        },
                    trailingEnabled = row.key != NavigationBarItemKeys.HOME,
                    imageUri = row.imageUri.takeIf { row.showImage },
                )
            }
            if (state.hiddenRows.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(SettingsR.string.settings_navigation_bar_hidden),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(MaterialTheme.spacings.default),
                    )
                }
                items(state.hiddenRows, key = { "hidden:${it.key}" }) { row ->
                    NavigationBarRowItem(
                        row = row,
                        canMoveUp = false,
                        canMoveDown = false,
                        onMoveUp = {},
                        onMoveDown = {},
                        onTrailingClick = { onRestore(row.key) },
                        onEdit = if (row.key.startsWith("item:")) ({ onEdit(row) }) else null,
                        trailingIcon = CoreR.drawable.ic_plus,
                        trailingDescription = stringResource(CoreR.string.home_layout_show_section),
                        showMoveButtons = false,
                        titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        imageUri = row.imageUri.takeIf { row.showImage },
                    )
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(SettingsR.string.settings_navigation_bar_reset_title)) },
            text = { Text(stringResource(SettingsR.string.settings_navigation_bar_reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onReset()
                    }
                ) {
                    Text(stringResource(CoreR.string.home_layout_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(CoreR.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun NavigationBarPreview(rows: List<NavigationBarRow>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacings.default),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        Text(
            text = stringResource(SettingsR.string.settings_navigation_bar_preview),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier =
                    Modifier.horizontalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacings.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                rows.forEach { row ->
                    Column(
                        modifier = Modifier.width(72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        NavigationBarIcon(
                            imageUri = row.imageUri.takeIf { row.showImage },
                            iconRes = row.icon,
                            contentDescription = null,
                        )
                        Text(
                            text = row.titleText ?: stringResource(row.title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationBarRowItem(
    row: NavigationBarRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onTrailingClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    trailingIcon: Int,
    trailingDescription: String,
    modifier: Modifier = Modifier,
    showMoveButtons: Boolean = true,
    trailingEnabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    imageUri: String? = null,
    iconModifier: Modifier = Modifier.size(24.dp),
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacings.default),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavigationBarIcon(
                imageUri = imageUri,
                iconRes = row.icon,
                contentDescription = null,
                modifier = iconModifier,
            )
            Text(
                text = row.titleText ?: stringResource(row.title),
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
            )
        }
        Row {
            if (showMoveButtons) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_chevron_up),
                        contentDescription = stringResource(CoreR.string.move_up),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_chevron_down),
                        contentDescription = stringResource(CoreR.string.move_down),
                    )
                }
            }
            onEdit?.let { edit ->
                IconButton(onClick = edit) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_edit),
                        contentDescription =
                            stringResource(SettingsR.string.settings_navigation_bar_edit_label),
                    )
                }
            }
            IconButton(onClick = onTrailingClick, enabled = trailingEnabled) {
                Icon(
                    painter = painterResource(trailingIcon),
                    contentDescription = trailingDescription,
                )
            }
        }
    }
}
