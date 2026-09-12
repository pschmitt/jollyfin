package dev.pschmitt.jellyfin.presentation.film

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyHomeSuggestions
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyHomeView
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyServer
import dev.pschmitt.jellyfin.film.presentation.home.HomeAction
import dev.pschmitt.jellyfin.film.presentation.home.HomeState
import dev.pschmitt.jellyfin.film.presentation.home.HomeViewModel
import dev.pschmitt.jellyfin.film.presentation.search.SearchAction
import dev.pschmitt.jellyfin.film.presentation.search.SearchState
import dev.pschmitt.jellyfin.film.presentation.search.SearchViewModel
import dev.pschmitt.jellyfin.models.JollyfinCollection
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.PvrQueueEntry
import dev.pschmitt.jellyfin.models.SeerrSearchItem
import dev.pschmitt.jellyfin.presentation.film.components.FilmSearchScreen
import dev.pschmitt.jellyfin.presentation.film.components.HomeCarousel
import dev.pschmitt.jellyfin.presentation.film.components.HomeDiscoverSection
import dev.pschmitt.jellyfin.presentation.film.components.HomeHeader
import dev.pschmitt.jellyfin.presentation.film.components.HomeSection
import dev.pschmitt.jellyfin.presentation.film.components.HomeView
import dev.pschmitt.jellyfin.presentation.film.components.ProfileSelectionBottomSheet
import dev.pschmitt.jellyfin.presentation.film.components.PvrQueueDownloadCard
import dev.pschmitt.jellyfin.presentation.film.components.SectionServiceIcons
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.presentation.utils.rememberSafePadding
import dev.pschmitt.jellyfin.setup.presentation.profiles.ProfilesViewModel
import dev.pschmitt.jellyfin.utils.HomeSectionKeys
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun HomeScreen(
    onLibraryClick: (library: JollyfinCollection) -> Unit,
    onSettingsClick: () -> Unit,
    onManageServers: () -> Unit,
    onItemClick: (item: JollyfinItem) -> Unit,
    onSeerrItemClick: (item: SeerrSearchItem) -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadData() }

    // Picks up a reorder made from the "Customize home screen" settings screen as soon as Home
    // resumes - cheap (no network), unlike loadData() above which only runs once on first entry.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshSectionOrder()
        onPauseOrDispose {}
    }

    HomeScreenLayout(
        state = state,
        searchState = searchState,
        onAction = { action ->
            when (action) {
                is HomeAction.OnItemClick -> onItemClick(action.item)
                is HomeAction.OnSeerrItemClick -> onSeerrItemClick(action.item)
                is HomeAction.OnLibraryClick -> onLibraryClick(action.library)
                is HomeAction.OnDownloadsClick -> onDownloadsClick()
                is HomeAction.OnSettingsClick -> onSettingsClick()
                is HomeAction.OnManageServers -> onManageServers()
                is HomeAction.OnEnableOfflineMode -> (context as? Activity)?.recreate()
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onSearchAction = { action ->
            when (action) {
                is SearchAction.OnItemClick -> onItemClick(action.item)
                is SearchAction.OnSeerrItemClick -> onSeerrItemClick(action.item)
                else -> Unit
            }
            searchViewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenLayout(
    state: HomeState,
    searchState: SearchState,
    onAction: (HomeAction) -> Unit,
    onSearchAction: (SearchAction) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val profilesViewModel: ProfilesViewModel = hiltViewModel()
    val profilesState by profilesViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(true) { profilesViewModel.loadProfiles() }
    val safePadding = rememberSafePadding(handleStartInsets = false)

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val itemsPadding = PaddingValues(start = paddingStart, end = paddingEnd)

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    val showServerSelectionSheetState = rememberModalBottomSheetState()
    var showServerSelectionBottomSheet by remember { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    // HomeHeader gets its own row here instead of floating as an overlay on top of the scrolling
    // content below it - same "top bar isn't a transparent overlay" fix as ItemDetailScaffold on
    // every detail screen, so Home's header has a solid backdrop instead of showing whatever
    // section is currently scrolled behind it.
    Column(
        modifier =
            Modifier.fillMaxSize().semantics { isTraversalGroup = true }.testTag("e2e-home-screen")
    ) {
        if (searchExpanded) {
            // A plain in-place screen, not a floating overlay - it fully replaces Home's content
            // rather than sitting on top of it, so there's no popup/dialog to reconcile with the
            // rest of the app's screens.
            FilmSearchScreen(
                state = searchState,
                onAction = onSearchAction,
                onBackClick = { searchExpanded = false },
            )
        } else {
            HomeHeader(
                serverName =
                    profilesState.currentProfile?.profile?.name ?: state.server?.name ?: "",
                isLoading = state.isLoading,
                isError = state.error != null,
                onServerClick = { showServerSelectionBottomSheet = true },
                onErrorClick = { showErrorDialog = true },
                onRetryClick = { onAction(HomeAction.OnRetryClick) },
                onSearchClick = { searchExpanded = true },
                onUserClick = { onAction(HomeAction.OnSettingsClick) },
            )
            Box(modifier = Modifier.fillMaxSize()) {
                // Default Material3 indicator - same loading feedback as Downloads/Library, instead
                // of
                // a separate spinner living in HomeHeader too.
                PullToRefreshBox(
                    isRefreshing = state.isLoading,
                    onRefresh = { onAction(HomeAction.OnRetryClick) },
                ) {
                    val lazyListState = rememberLazyListState()
                    val reorderableState =
                        rememberReorderableLazyListState(lazyListState) { from, to ->
                            onAction(HomeAction.OnReorderSections(from.index, to.index))
                        }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize().semantics { traversalIndex = 1f },
                        state = lazyListState,
                        contentPadding =
                            PaddingValues(
                                top = MaterialTheme.spacings.small,
                                bottom = paddingBottom,
                            ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    ) {
                        items(state.sectionOrder, key = { it }) { key ->
                            ReorderableItem(reorderableState, key = key) { isDragging ->
                                // Long-press the section's own title to start dragging it, rather
                                // than a
                                // persistent handle shown at all times - Suggestions is itself a
                                // swipeable
                                // pager, so wrapping the whole item in a drag-anywhere modifier
                                // would fight
                                // that nested gesture. Each section composable applies
                                // `titleModifier` to
                                // just its title Text, leaving the rest of its content (posters,
                                // the
                                // "view all" arrow, etc.) clickable/scrollable as normal.
                                val titleModifier = Modifier.longPressDraggableHandle()

                                // Subtle "picked up" feedback while dragging - a slight
                                // scale/elevation
                                // lift plus a faint tint, so entering reorder mode reads as a
                                // distinct
                                // state rather than the section just silently moving on its own.
                                val scale by
                                    animateFloatAsState(
                                        targetValue = if (isDragging) 1.02f else 1f,
                                        label = "sectionDragScale",
                                    )
                                val elevation by
                                    animateDpAsState(
                                        targetValue = if (isDragging) 6.dp else 0.dp,
                                        label = "sectionDragElevation",
                                    )
                                val tint by
                                    animateColorAsState(
                                        targetValue =
                                            if (isDragging) {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            } else {
                                                Color.Transparent
                                            },
                                        label = "sectionDragTint",
                                    )

                                Box(
                                    modifier =
                                        Modifier.graphicsLayer {
                                                scaleX = scale
                                                scaleY = scale
                                            }
                                            .shadow(elevation, shape = MaterialTheme.shapes.medium)
                                            .background(tint, shape = MaterialTheme.shapes.medium)
                                ) {
                                    when {
                                        key == HomeSectionKeys.SUGGESTIONS ->
                                            state.suggestionsSection?.let { section ->
                                                HomeCarousel(
                                                    items = section.items,
                                                    itemsPadding = itemsPadding,
                                                    onAction = onAction,
                                                    titleModifier = titleModifier,
                                                )
                                            }
                                        key == HomeSectionKeys.CONTINUE_WATCHING ->
                                            state.resumeSection?.let { section ->
                                                HomeSection(
                                                    section = section.homeSection,
                                                    itemsPadding = itemsPadding,
                                                    onAction = onAction,
                                                    titleModifier = titleModifier,
                                                )
                                            }
                                        key == HomeSectionKeys.NEXT_UP ->
                                            state.nextUpSection?.let { section ->
                                                HomeSection(
                                                    section = section.homeSection,
                                                    itemsPadding = itemsPadding,
                                                    onAction = onAction,
                                                    titleModifier = titleModifier,
                                                )
                                            }
                                        key == HomeSectionKeys.FAVORITES ->
                                            state.favoritesSection?.let { section ->
                                                HomeSection(
                                                    section = section.homeSection,
                                                    itemsPadding = itemsPadding,
                                                    onAction = onAction,
                                                    titleModifier = titleModifier,
                                                )
                                            }
                                        key == HomeSectionKeys.ACTIVE_DOWNLOADS ->
                                            HomeDownloadProgress(
                                                entries = state.activeDownloads,
                                                onAction = onAction,
                                                modifier = Modifier.padding(itemsPadding),
                                                titleModifier = titleModifier,
                                                serviceIcons = state.pvrServiceIcons,
                                            )
                                        key.startsWith("view:") ->
                                            state.views
                                                .firstOrNull {
                                                    HomeSectionKeys.view(it.view.id) == key
                                                }
                                                ?.let { view ->
                                                    HomeView(
                                                        view = view,
                                                        itemsPadding = itemsPadding,
                                                        onAction = onAction,
                                                        titleModifier = titleModifier,
                                                    )
                                                }
                                        key.startsWith("discover:") ->
                                            state.discoverSections
                                                .firstOrNull {
                                                    HomeSectionKeys.discover(it.titleRes) == key
                                                }
                                                ?.let { section ->
                                                    HomeDiscoverSection(
                                                        section = section,
                                                        itemsPadding = itemsPadding,
                                                        onAction = onAction,
                                                        titleModifier = titleModifier,
                                                    )
                                                }
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.error != null && showErrorDialog) {
                    AlertDialog(
                        onDismissRequest = { showErrorDialog = false },
                        title = { Text(stringResource(CoreR.string.no_server_connection)) },
                        text = {
                            Text(
                                state.error!!.message ?: stringResource(CoreR.string.unknown_error)
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { onAction(HomeAction.OnEnableOfflineMode) }) {
                                Text(stringResource(CoreR.string.enable_offline_mode))
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showErrorDialog = false
                                    onAction(HomeAction.OnRetryClick)
                                }
                            ) {
                                Text(stringResource(CoreR.string.retry))
                            }
                        },
                    )
                }
            }
        }
    }

    if (showServerSelectionBottomSheet) {
        ProfileSelectionBottomSheet(
            onUpdate = {
                onAction(HomeAction.OnRetryClick)
                profilesViewModel.loadProfiles()
                scope
                    .launch { showServerSelectionSheetState.hide() }
                    .invokeOnCompletion {
                        if (!showServerSelectionSheetState.isVisible) {
                            showServerSelectionBottomSheet = false
                        }
                    }
            },
            onManage = {
                onAction(HomeAction.OnManageServers)
                scope.launch { showServerSelectionSheetState.hide() }
            },
            onDismissRequest = { showServerSelectionBottomSheet = false },
            sheetState = showServerSelectionSheetState,
        )
    }
}

@Composable
private fun HomeDownloadProgress(
    entries: List<PvrQueueEntry>,
    onAction: (HomeAction) -> Unit = {},
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    serviceIcons: List<Int> = emptyList(),
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionServiceIcons(serviceIcons)
                Text(
                    text = stringResource(CoreR.string.pvr_queue_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = titleModifier,
                )
            }
            // Same "jump to the full list" affordance a library shelf's header gets (see
            // HomeView.kt) - this section previously had no way to reach the Downloads screen at
            // all.
            IconButton(onClick = { onAction(HomeAction.OnDownloadsClick) }) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_arrow_right),
                    contentDescription = stringResource(CoreR.string.title_download),
                )
            }
        }
        if (entries.isEmpty()) {
            Text(
                text = stringResource(CoreR.string.pvr_queue_section_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.take(2).forEach { entry ->
                PvrQueueDownloadCard(status = entry.status, title = entry.title)
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun HomeScreenLayoutPreview() {
    JollyfinTheme {
        HomeScreenLayout(
            state =
                HomeState(
                    server = dummyServer,
                    suggestionsSection = dummyHomeSuggestions,
                    resumeSection = dummyHomeSection,
                    views = listOf(dummyHomeView),
                    error = Exception("Failed to load data"),
                ),
            searchState = SearchState(),
            onAction = {},
            onSearchAction = {},
        )
    }
}
