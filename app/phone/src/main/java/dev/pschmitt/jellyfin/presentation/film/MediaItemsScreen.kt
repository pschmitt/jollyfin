package dev.pschmitt.jellyfin.presentation.film

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.presentation.components.TopBarTitle
import dev.pschmitt.jellyfin.presentation.film.components.Direction
import dev.pschmitt.jellyfin.presentation.film.components.ItemCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaItemsScreen(
    kind: MediaItemsKind,
    navigateBack: () -> Unit,
    onItemClick: (JollyfinItem) -> Unit,
    viewModel: MediaItemsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(kind) { viewModel.load(kind) }

    val title =
        when (kind) {
            MediaItemsKind.FAVORITES -> CoreR.string.title_favorite
            MediaItemsKind.NEXT_UP -> CoreR.string.next_up
        }
    val icon =
        when (kind) {
            MediaItemsKind.FAVORITES -> CoreR.drawable.ic_heart
            MediaItemsKind.NEXT_UP -> CoreR.drawable.ic_skip_forward
        }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { TopBarTitle(text = stringResource(title), iconRes = icon) },
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
        if (state.error != null) {
            Text(
                text = stringResource(CoreR.string.error_loading_data),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(innerPadding).padding(16.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items = state.items, key = { it.id }) { item ->
                    ItemCard(item = item, direction = Direction.VERTICAL, onClick = onItemClick)
                }
            }
        }
    }
}
