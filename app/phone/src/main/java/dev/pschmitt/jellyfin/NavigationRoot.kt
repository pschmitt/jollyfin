package dev.pschmitt.jellyfin

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.Navigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.film.presentation.media.MediaViewModel
import dev.pschmitt.jellyfin.models.CollectionType
import dev.pschmitt.jellyfin.models.JollyfinBoxSet
import dev.pschmitt.jellyfin.models.JollyfinCollection
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinFolder
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.JollyfinSeason
import dev.pschmitt.jellyfin.models.JollyfinShow
import dev.pschmitt.jellyfin.models.PvrSource
import dev.pschmitt.jellyfin.models.SeerrMediaType
import dev.pschmitt.jellyfin.presentation.film.AutoDownloadRulesScreen
import dev.pschmitt.jellyfin.presentation.film.CalendarScreen
import dev.pschmitt.jellyfin.presentation.film.CollectionScreen
import dev.pschmitt.jellyfin.presentation.film.DownloadsScreen
import dev.pschmitt.jellyfin.presentation.film.EpisodeScreen
import dev.pschmitt.jellyfin.presentation.film.HomeScreen
import dev.pschmitt.jellyfin.presentation.film.LibraryScreen
import dev.pschmitt.jellyfin.presentation.film.MediaItemsKind
import dev.pschmitt.jellyfin.presentation.film.MediaItemsScreen
import dev.pschmitt.jellyfin.presentation.film.MovieScreen
import dev.pschmitt.jellyfin.presentation.film.PersonScreen
import dev.pschmitt.jellyfin.presentation.film.SeasonScreen
import dev.pschmitt.jellyfin.presentation.film.SeerrMediaScreen
import dev.pschmitt.jellyfin.presentation.film.ShowScreen
import dev.pschmitt.jellyfin.presentation.settings.AboutScreen
import dev.pschmitt.jellyfin.presentation.settings.SettingsFileEditScreen
import dev.pschmitt.jellyfin.presentation.settings.SettingsScreen
import dev.pschmitt.jellyfin.presentation.settings.backup.BackupSettingsScreen
import dev.pschmitt.jellyfin.presentation.settings.homelayout.HomeLayoutSettingsScreen
import dev.pschmitt.jellyfin.presentation.settings.localaccess.LocalAccessScreen
import dev.pschmitt.jellyfin.presentation.settings.navigation.NavigationBarIcon
import dev.pschmitt.jellyfin.presentation.settings.navigation.NavigationBarSettingsScreen
import dev.pschmitt.jellyfin.presentation.settings.navigation.NavigationBarSettingsViewModel
import dev.pschmitt.jellyfin.presentation.settings.profiles.ProfileDetailScreen
import dev.pschmitt.jellyfin.presentation.settings.profiles.ProfilesListScreen
import dev.pschmitt.jellyfin.presentation.settings.qrexport.QrExportScreen
import dev.pschmitt.jellyfin.presentation.settings.scanlibraries.ScanLibrariesScreen
import dev.pschmitt.jellyfin.presentation.setup.addresses.ServerAddressesScreen
import dev.pschmitt.jellyfin.presentation.setup.addserver.AddServerScreen
import dev.pschmitt.jellyfin.presentation.setup.login.LoginScreen
import dev.pschmitt.jellyfin.presentation.setup.qrscan.QrScanScreen
import dev.pschmitt.jellyfin.presentation.setup.restore.RestoreBackupScreen
import dev.pschmitt.jellyfin.presentation.setup.servers.ServersScreen
import dev.pschmitt.jellyfin.presentation.setup.users.UsersScreen
import dev.pschmitt.jellyfin.presentation.setup.welcome.WelcomeScreen
import dev.pschmitt.jellyfin.presentation.utils.LocalOfflineMode
import dev.pschmitt.jellyfin.settings.domain.NavigationBarPinnedItem
import dev.pschmitt.jellyfin.utils.NavigationBarItemKeys
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable data object WelcomeRoute

@Serializable data object ServersRoute

@Serializable data object AddServerRoute

@Serializable data class ServerAddressesRoute(val serverId: String)

@Serializable data object UsersRoute

@Serializable data class LoginRoute(val username: String? = null)

@Serializable data object HomeRoute

@Serializable data object FavoritesRoute

@Serializable data object NextUpRoute

@Serializable data object DownloadsRoute

@Serializable data object CalendarRoute

// The merged movies+shows browse view - replaces the per-library Movies/Shows tabs.
@Serializable data object MediaRoute

// Also renders what used to be the separate "Remote devices" screen - the two were merged
// (FINDROID-54) since both are fundamentally "a show + season scope + toggle/remove" for
// different devices.
@Serializable data object AutoDownloadRulesRoute

@Serializable data object LocalAccessRoute

@Serializable data object ScanLibrariesRoute

@Serializable
data class LibraryRoute(
    val libraryId: String,
    val libraryName: String,
    val libraryType: CollectionType,
    // True when reached via its own top-level tab, in which case there's nothing to "go back"
    // to - false for the "View all" drill-down from a Home library shelf, which does need a
    // back button to return to Home.
    val isTab: Boolean = false,
)

@Serializable data class CollectionRoute(val collectionId: String, val collectionName: String)

// Detail view for a Seerr media item that isn't in the library (yet) - keyed by TMDB id.
// mediaType is a SeerrMediaType enum name.
@Serializable
data class SeerrMediaRoute(
    val tmdbId: Int,
    val mediaType: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val sonarrEpisodeId: Int? = null,
    // ISO-8601 strings (LocalDate.toString()/LocalTime.toString()) - already timezone-localized
    // air date/time known from Sonarr, when navigated here from a Season screen upcoming-episode
    // row. Null when not known (e.g. reached via search/Home discovery instead).
    val airDate: String? = null,
    val airTime: String? = null,
)

@Serializable data class MovieRoute(val movieId: String)

@Serializable data class ShowRoute(val showId: String)

@Serializable data class EpisodeRoute(val episodeId: String)

@Serializable data class SeasonRoute(val seasonId: String)

@Serializable data class PersonRoute(val personId: String)

@Serializable data class SettingsRoute(val indexes: IntArray)

@Serializable data class SettingsFileEditRoute(val filePath: String)

@Serializable data object AboutRoute

@Serializable data object BackupSettingsRoute

@Serializable data object QrExportRoute

@Serializable data object ProfilesRoute

@Serializable data class ProfileDetailRoute(val profileId: String)

@Serializable data object HomeLayoutSettingsRoute

@Serializable data object NavigationBarSettingsRoute

@Serializable data object RestoreBackupRoute

@Serializable data class ScanQrRoute(val rawPayload: String? = null)

data class TabBarItem(
    val key: String,
    @param:StringRes val title: Int = 0,
    val titleText: String? = null,
    @param:DrawableRes val icon: Int,
    val imageUri: String? = null,
    val showImage: Boolean = false,
    val route: Any,
    val enabled: Boolean = true,
)

@Composable private fun TabBarItem.resolvedTitle(): String = titleText ?: stringResource(title)

@DrawableRes
private fun libraryIcon(type: CollectionType): Int =
    when (type) {
        CollectionType.Movies -> CoreR.drawable.ic_film
        CollectionType.TvShows -> CoreR.drawable.ic_tv
        else -> CoreR.drawable.ic_library
    }

private fun libraryTab(library: JollyfinCollection) =
    TabBarItem(
        key = NavigationBarItemKeys.library(library.id.toString()),
        titleText = library.name,
        icon = libraryIcon(library.type),
        route =
            LibraryRoute(
                libraryId = library.id.toString(),
                libraryName = library.name,
                libraryType = library.type,
                isTab = true,
            ),
    )

private fun pinnedTab(item: NavigationBarPinnedItem): TabBarItem? {
    val route =
        when (item.type) {
            "movie" -> MovieRoute(item.id)
            "show" -> ShowRoute(item.id)
            "episode" -> EpisodeRoute(item.id)
            "season" -> SeasonRoute(item.id)
            else -> return null
        }
    return TabBarItem(
        key = item.key,
        titleText = item.title,
        icon = CoreR.drawable.ic_star,
        imageUri = item.imageUri,
        showImage = item.showImage,
        route = route,
    )
}

val homeTab =
    TabBarItem(
        key = NavigationBarItemKeys.HOME,
        title = CoreR.string.title_home,
        icon = CoreR.drawable.ic_home,
        route = HomeRoute,
    )
val mediaTab =
    TabBarItem(
        key = NavigationBarItemKeys.MEDIA,
        title = CoreR.string.title_media_tab,
        icon = CoreR.drawable.ic_library,
        route = MediaRoute,
    )
val downloadsTab =
    TabBarItem(
        key = NavigationBarItemKeys.DOWNLOADS,
        title = CoreR.string.title_download,
        icon = CoreR.drawable.ic_download,
        route = DownloadsRoute,
    )
val calendarTab =
    TabBarItem(
        key = NavigationBarItemKeys.CALENDAR,
        title = CoreR.string.title_calendar,
        icon = CoreR.drawable.ic_calendar,
        route = CalendarRoute,
    )
val favoritesTab =
    TabBarItem(
        key = NavigationBarItemKeys.FAVORITES,
        title = CoreR.string.title_favorite,
        icon = CoreR.drawable.ic_heart,
        route = FavoritesRoute,
    )
val nextUpTab =
    TabBarItem(
        key = NavigationBarItemKeys.NEXT_UP,
        title = CoreR.string.next_up,
        icon = CoreR.drawable.ic_skip_forward,
        route = NextUpRoute,
    )
val settingsTab =
    TabBarItem(
        key = NavigationBarItemKeys.SETTINGS,
        title = CoreR.string.title_settings,
        icon = CoreR.drawable.ic_settings,
        route = settingsRootRoute(),
    )

/** Plain "open Settings at its root", not scrolled to any particular section. */
private fun settingsRootRoute() = SettingsRoute(indexes = intArrayOf(CoreR.string.title_settings))

// Routes reachable before there's a logged-in server/user - the nav rail's tabs (Home, libraries,
// Downloads, Calendar) would be empty/non-functional here, so these never show it, tablet or not.
private val preAuthRouteNames =
    listOf(
            WelcomeRoute::class,
            ServersRoute::class,
            AddServerRoute::class,
            ServerAddressesRoute::class,
            UsersRoute::class,
            LoginRoute::class,
        )
        .map { it.qualifiedName }

@Composable
fun NavigationRoot(
    navController: NavHostController,
    hasServers: Boolean,
    hasCurrentServer: Boolean,
    hasCurrentUser: Boolean,
    mediaViewModel: MediaViewModel = hiltViewModel(),
    navigationSettingsViewModel: NavigationBarSettingsViewModel = hiltViewModel(),
) {
    val isOfflineMode = LocalOfflineMode.current

    val startDestination =
        when {
            hasServers && hasCurrentServer && hasCurrentUser -> HomeRoute
            hasServers && hasCurrentServer -> UsersRoute
            hasServers -> ServersRoute
            else -> WelcomeRoute
        }

    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val isTablet =
        windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
        )

    val mediaState by mediaViewModel.state.collectAsStateWithLifecycle()
    val pinnedItemsVersion by
        navigationSettingsViewModel.pinnedItemsVersion.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { navigationSettingsViewModel.refreshPinnedArtwork() }

    // Movies and TV show libraries are merged into a single "Media" tab; any other library
    // types (box sets, mixed, folders, ...) keep their own tab.
    val (mergedLibraries, standaloneLibraries) =
        mediaState.libraries.partition {
            it.type == CollectionType.Movies || it.type == CollectionType.TvShows
        }

    val naturalNavigationItems =
        when (isOfflineMode) {
            false ->
                listOf(homeTab) +
                    (if (mergedLibraries.isNotEmpty()) listOf(mediaTab) else emptyList()) +
                    standaloneLibraries.map(::libraryTab) +
                    listOf(downloadsTab) +
                    (if (mediaState.showCalendarTab) listOf(calendarTab) else emptyList()) +
                    listOf(favoritesTab, nextUpTab, settingsTab) +
                    pinnedItemsVersion.let {
                        navigationSettingsViewModel.pinnedItems().mapNotNull(::pinnedTab)
                    }
            true -> listOf(homeTab, downloadsTab, settingsTab)
        }
    val navigationItems = navigationSettingsViewModel.resolveVisibleItems(naturalNavigationItems)

    val navBackStackEntry by navController.currentBackStackEntryAsState()

    val currentRoute = navBackStackEntry?.destination?.route
    val currentLibraryRoute = navBackStackEntry?.let { entry ->
        runCatching { entry.toRoute<LibraryRoute>() }.getOrNull()
    }

    fun TabBarItem.isSelected(): Boolean =
        when (val r = route) {
            is LibraryRoute -> currentLibraryRoute?.libraryId == r.libraryId
            is MovieRoute ->
                if (key.startsWith("item:")) {
                    navBackStackEntry?.let {
                        runCatching { it.toRoute<MovieRoute>().movieId == r.movieId }
                            .getOrDefault(false)
                    } == true
                } else currentRoute?.startsWith(r::class.qualifiedName.orEmpty()) == true
            is ShowRoute ->
                if (key.startsWith("item:")) {
                    navBackStackEntry?.let {
                        runCatching { it.toRoute<ShowRoute>().showId == r.showId }
                            .getOrDefault(false)
                    } == true
                } else currentRoute?.startsWith(r::class.qualifiedName.orEmpty()) == true
            is EpisodeRoute ->
                if (key.startsWith("item:")) {
                    navBackStackEntry?.let {
                        runCatching { it.toRoute<EpisodeRoute>().episodeId == r.episodeId }
                            .getOrDefault(false)
                    } == true
                } else currentRoute?.startsWith(r::class.qualifiedName.orEmpty()) == true
            is SeasonRoute ->
                if (key.startsWith("item:")) {
                    navBackStackEntry?.let {
                        runCatching { it.toRoute<SeasonRoute>().seasonId == r.seasonId }
                            .getOrDefault(false)
                    } == true
                } else currentRoute?.startsWith(r::class.qualifiedName.orEmpty()) == true
            else -> currentRoute?.startsWith(r::class.qualifiedName.orEmpty()) == true
        }

    // Matched by prefix since some pre-auth routes carry args (e.g. LoginRoute(username)), whose
    // NavDestination.route pattern is "qualifiedName/{arg}", not the bare qualified name.
    val isPreAuthRoute = preAuthRouteNames.any { name -> currentRoute?.startsWith(name!!) == true }

    // On tablet, keep the nav rail visible everywhere past login - including on "fullscreen"
    // detail screens (Show/Movie/Season/Episode/...) that used to hide it, since there's plenty
    // of width for both. On phone, keep the original behavior: only show it on an actual tab.
    // The video player is a separate Activity (PlayerActivity), not a route in this NavHost, so
    // it's unaffected either way and stays truly fullscreen.
    val showBottomBar = !isPreAuthRoute && (isTablet || navigationItems.any { it.isSelected() })

    val navigationSuiteScaffoldState = rememberNavigationSuiteScaffoldState()

    LaunchedEffect(showBottomBar) {
        if (showBottomBar) {
            navigationSuiteScaffoldState.show()
        } else {
            navigationSuiteScaffoldState.hide()
        }
    }

    val customNavSuiteType =
        with(windowAdaptiveInfo) {
            if (isTablet) {
                NavigationSuiteType.NavigationRail
            } else {
                NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(this)
            }
        }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            navigationItems.forEach { item ->
                item(
                    selected = item.isSelected(),
                    onClick = {
                        when {
                            // Home is the graph's start destination, so it's always still on the
                            // bottom of the back stack - just pop back to it directly instead of
                            // going through popUpTo+saveState+restoreState, which (at least
                            // empirically) can restore the wrong saved entry when the destination
                            // being popped up to is also the navigation target.
                            item.route == HomeRoute -> {
                                navController.popBackStack(route = HomeRoute, inclusive = false)
                            }
                            // Per-library tabs all share the LibraryRoute type, so
                            // launchSingleTop/restoreState (which dedup by destination type, not
                            // by route arguments) would otherwise treat switching from one library
                            // tab to another as "already there" and silently no-op. Only rely on
                            // that save/restore behavior for tabs with their own dedicated route
                            // type, and skip re-navigating if the tapped library tab is already
                            // the open one.
                            item.route !is LibraryRoute || !item.isSelected() -> {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        // A tab tap is an explicit request for that tab's root,
                                        // not for the last detail screen visited from it.
                                        saveState = false
                                    }
                                    launchSingleTop = true
                                    restoreState = false
                                }
                            }
                        }
                    },
                    icon = {
                        NavigationBarIcon(
                            imageUri = item.imageUri.takeIf { item.showImage },
                            iconRes = item.icon,
                            contentDescription = item.resolvedTitle(),
                        )
                    },
                    enabled = item.enabled,
                    label = { Text(text = item.resolvedTitle()) },
                )
            }
        },
        layoutType = customNavSuiteType,
        state = navigationSuiteScaffoldState,
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            enterTransition = { fadeIn(tween(300)) },
            exitTransition = { fadeOut(tween(300)) },
        ) {
            composable<WelcomeRoute> {
                WelcomeScreen(
                    onContinueClick = { navController.safeNavigate(ServersRoute) },
                    onRestoreClick = { navController.safeNavigate(RestoreBackupRoute) },
                    onScanQrClick = { navController.safeNavigate(ScanQrRoute()) },
                )
            }
            composable<ServersRoute> {
                ServersScreen(
                    navigateToUsers = { navController.safeNavigate(UsersRoute) },
                    navigateToAddresses = { serverId ->
                        navController.safeNavigate(ServerAddressesRoute(serverId))
                    },
                    onAddClick = { navController.safeNavigate(AddServerRoute) },
                    onRestoreClick = { navController.safeNavigate(RestoreBackupRoute) },
                    onBackClick = { navController.safePopBackStack() },
                    showBack = navController.previousBackStackEntry != null,
                )
            }
            composable<AddServerRoute> {
                AddServerScreen(
                    onSuccess = { navController.safeNavigate(UsersRoute) },
                    onBackClick = { navController.safePopBackStack() },
                )
            }
            composable<ServerAddressesRoute> { backStackEntry ->
                val route: ServerAddressesRoute = backStackEntry.toRoute()
                ServerAddressesScreen(
                    serverId = route.serverId,
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<UsersRoute> {
                UsersScreen(
                    navigateToHome = { navigateHome(navController) },
                    onChangeServerClick = {
                        navController.safeNavigate(ServersRoute) {
                            popUpTo(ServersRoute) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onAddClick = { navController.safeNavigate(LoginRoute()) },
                    onBackClick = { navController.safePopBackStack() },
                    onPublicUserClick = { username ->
                        navController.safeNavigate(LoginRoute(username = username))
                    },
                    showBack = navController.previousBackStackEntry != null,
                )
            }
            composable<LoginRoute> { backStackEntry ->
                val route: LoginRoute = backStackEntry.toRoute()
                LoginScreen(
                    onSuccess = {
                        navController.safeNavigate(HomeRoute) {
                            popUpTo(0)
                            launchSingleTop = true
                        }
                    },
                    onChangeServerClick = {
                        navController.safeNavigate(ServersRoute) {
                            popUpTo(ServersRoute) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onBackClick = { navController.safePopBackStack() },
                    prefilledUsername = route.username,
                )
            }
            composable<HomeRoute> {
                // Reloaded on every visit (rather than once at NavigationRoot's initial
                // composition) since MainViewModel's hasCurrentUser snapshot - and thus whether a
                // session existed yet - is only computed once at process cold-start. Without this,
                // logging in or restoring a backup within the same process would leave the
                // library/Movies/Shows nav tabs permanently empty.
                LaunchedEffect(Unit) { if (!isOfflineMode) mediaViewModel.loadData() }
                HomeScreen(
                    onLibraryClick = {
                        navController.safeNavigate(
                            LibraryRoute(
                                libraryId = it.id.toString(),
                                libraryName = it.name,
                                libraryType = it.type,
                            )
                        )
                    },
                    onSettingsClick = { navController.safeNavigate(settingsRootRoute()) },
                    // "Manage servers" from within an already-set-up app goes straight to
                    // Settings > Connections - it already has the same add/switch server and
                    // add/switch user UI as the dedicated ServersRoute/AddServerRoute/UsersRoute
                    // flow (which stays reserved for first-run setup, before any server exists to
                    // have a Settings screen for).
                    onManageServers = { navController.safeNavigate(ProfilesRoute) },
                    onItemClick = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    onSeerrItemClick = { item ->
                        navController.safeNavigate(
                            SeerrMediaRoute(
                                tmdbId = item.tmdbId,
                                mediaType = item.mediaType.name,
                            )
                        )
                    },
                )
            }
            composable<FavoritesRoute> {
                MediaItemsScreen(
                    kind = MediaItemsKind.FAVORITES,
                    navigateBack = { navigateHome(navController) },
                    onItemClick = { item -> navigateToItem(navController, item) },
                )
            }
            composable<NextUpRoute> {
                MediaItemsScreen(
                    kind = MediaItemsKind.NEXT_UP,
                    navigateBack = { navigateHome(navController) },
                    onItemClick = { item -> navigateToItem(navController, item) },
                )
            }
            composable<DownloadsRoute> {
                DownloadsScreen(
                    onItemClick = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    onPvrItemClick = { item, source ->
                        item.tmdbId?.let { tmdbId ->
                            navController.safeNavigate(
                                SeerrMediaRoute(
                                    tmdbId = tmdbId,
                                    mediaType =
                                        if (source == PvrSource.SONARR) {
                                            SeerrMediaType.TV.name
                                        } else {
                                            SeerrMediaType.MOVIE.name
                                        },
                                    seasonNumber = item.seasonNumber,
                                    episodeNumber = item.episodeNumber,
                                    sonarrEpisodeId = item.sonarrEpisodeId,
                                )
                            )
                        }
                    },
                    onShowClick = { showId ->
                        navController.safeNavigate(ShowRoute(showId = showId.toString()))
                    },
                    onMoviesClick = { navController.safeNavigate(MediaRoute) },
                    onSettingsClick = {
                        navController.safeNavigate(
                            SettingsRoute(
                                indexes =
                                    intArrayOf(
                                        CoreR.string.title_settings,
                                        CoreR.string.title_download,
                                    )
                            )
                        )
                    },
                    onGoToHomeClick = {
                        // Home is the graph's start destination, so it's always on the bottom of
                        // the back stack - pop back to it, same as tapping the Home tab.
                        navController.popBackStack(route = HomeRoute, inclusive = false)
                    },
                )
            }
            composable<CalendarRoute> {
                CalendarScreen(
                    onSeasonClick = { seasonId ->
                        navController.safeNavigate(SeasonRoute(seasonId = seasonId.toString()))
                    },
                    onEpisodeClick = { episodeId ->
                        navController.safeNavigate(EpisodeRoute(episodeId = episodeId.toString()))
                    },
                    onMovieClick = { movieId ->
                        navController.safeNavigate(MovieRoute(movieId = movieId.toString()))
                    },
                    onSeerrClick = { entry ->
                        navController.safeNavigate(
                            SeerrMediaRoute(
                                tmdbId = entry.tmdbId!!,
                                mediaType =
                                    when (entry.source) {
                                        PvrSource.SONARR -> SeerrMediaType.TV.name
                                        PvrSource.RADARR -> SeerrMediaType.MOVIE.name
                                    },
                                seasonNumber = entry.seasonNumber,
                                episodeNumber = entry.episodeNumber,
                                sonarrEpisodeId = entry.episodeId,
                            )
                        )
                    },
                    onSettingsClick = { navController.safeNavigate(settingsRootRoute()) },
                )
            }
            composable<MediaRoute> {
                LibraryScreen(
                    libraryId = null,
                    libraryName = stringResource(CoreR.string.title_media_tab),
                    libraryType = CollectionType.Mixed,
                    showBackButton = false,
                    onItemClick = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    navigateBack = { navController.safePopBackStack() },
                    onSettingsClick = { navController.safeNavigate(settingsRootRoute()) },
                    onSeerrItemClick = { tmdbId, mediaType ->
                        navController.safeNavigate(
                            SeerrMediaRoute(tmdbId = tmdbId, mediaType = mediaType.name)
                        )
                    },
                )
            }
            composable<AutoDownloadRulesRoute> {
                AutoDownloadRulesScreen(
                    navigateBack = { navController.safePopBackStack() },
                    navigateToDownloadSettings = {
                        navController.safeNavigate(
                            SettingsRoute(
                                indexes =
                                    intArrayOf(
                                        CoreR.string.title_settings,
                                        CoreR.string.title_download,
                                    )
                            )
                        )
                    },
                )
            }
            composable<LocalAccessRoute> {
                LocalAccessScreen(navigateBack = { navController.safePopBackStack() })
            }
            composable<ScanLibrariesRoute> {
                ScanLibrariesScreen(navigateBack = { navController.safePopBackStack() })
            }
            composable<LibraryRoute> { backStackEntry ->
                val route: LibraryRoute = backStackEntry.toRoute()
                LibraryScreen(
                    libraryId = UUID.fromString(route.libraryId),
                    libraryName = route.libraryName,
                    libraryType = route.libraryType,
                    showBackButton = !route.isTab,
                    onItemClick = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    navigateBack = { navController.safePopBackStack() },
                    onSettingsClick = { navController.safeNavigate(settingsRootRoute()) },
                    onSeerrItemClick = { tmdbId, mediaType ->
                        navController.safeNavigate(
                            SeerrMediaRoute(tmdbId = tmdbId, mediaType = mediaType.name)
                        )
                    },
                )
            }
            composable<SeerrMediaRoute> { backStackEntry ->
                val route: SeerrMediaRoute = backStackEntry.toRoute()
                SeerrMediaScreen(
                    tmdbId = route.tmdbId,
                    mediaType = SeerrMediaType.valueOf(route.mediaType),
                    seasonNumber = route.seasonNumber,
                    episodeNumber = route.episodeNumber,
                    sonarrEpisodeId = route.sonarrEpisodeId,
                    airDate = route.airDate?.let { java.time.LocalDate.parse(it) },
                    airTime = route.airTime?.let { java.time.LocalTime.parse(it) },
                    navigateToShow = { showId ->
                        if (showId != null) {
                            navController.safeNavigate(ShowRoute(showId = showId.toString()))
                        } else {
                            navController.safeNavigate(
                                SeerrMediaRoute(
                                    tmdbId = route.tmdbId,
                                    mediaType = SeerrMediaType.TV.name,
                                )
                            )
                        }
                    },
                    navigateToSeason = { seasonNumber, seasonId ->
                        if (seasonId != null) {
                            navController.safeNavigate(SeasonRoute(seasonId = seasonId.toString()))
                        } else {
                            navController.safeNavigate(
                                SeerrMediaRoute(
                                    tmdbId = route.tmdbId,
                                    mediaType = SeerrMediaType.TV.name,
                                    seasonNumber = seasonNumber,
                                )
                            )
                        }
                    },
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<CollectionRoute> { backStackEntry ->
                val route: CollectionRoute = backStackEntry.toRoute()
                CollectionScreen(
                    collectionId = UUID.fromString(route.collectionId),
                    collectionName = route.collectionName,
                    onItemClick = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<MovieRoute> { backStackEntry ->
                val route: MovieRoute = backStackEntry.toRoute()
                MovieScreen(
                    movieId = UUID.fromString(route.movieId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToPerson = { personId ->
                        navController.safeNavigate(PersonRoute(personId.toString()))
                    },
                    navigateToSettings = { navController.safeNavigate(settingsRootRoute()) },
                )
            }
            composable<ShowRoute> { backStackEntry ->
                val route: ShowRoute = backStackEntry.toRoute()
                ShowScreen(
                    showId = UUID.fromString(route.showId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToItem = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    navigateToPerson = { personId ->
                        navController.safeNavigate(PersonRoute(personId.toString()))
                    },
                    navigateToSeerr = { tmdbId, seasonNumber ->
                        navController.safeNavigate(
                            SeerrMediaRoute(
                                tmdbId = tmdbId,
                                mediaType = SeerrMediaType.TV.name,
                                seasonNumber = seasonNumber,
                            )
                        )
                    },
                    navigateToSettings = { navController.safeNavigate(settingsRootRoute()) },
                )
            }
            composable<SeasonRoute> { backStackEntry ->
                val route: SeasonRoute = backStackEntry.toRoute()
                SeasonScreen(
                    seasonId = UUID.fromString(route.seasonId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToItem = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    navigateToSeries = { seriesId ->
                        navController.safeNavigate(ShowRoute(showId = seriesId.toString())) {
                            popUpTo(ShowRoute(showId = seriesId.toString()))
                            launchSingleTop = true
                        }
                    },
                    navigateToSeerr = {
                        tmdbId,
                        seasonNumber,
                        episodeNumber,
                        sonarrEpisodeId,
                        airDate,
                        airTime ->
                        navController.safeNavigate(
                            SeerrMediaRoute(
                                tmdbId = tmdbId,
                                mediaType = SeerrMediaType.TV.name,
                                seasonNumber = seasonNumber,
                                episodeNumber = episodeNumber,
                                sonarrEpisodeId = sonarrEpisodeId,
                                airDate = airDate,
                                airTime = airTime,
                            )
                        )
                    },
                    navigateToSettings = { navController.safeNavigate(settingsRootRoute()) },
                )
            }
            composable<EpisodeRoute> { backStackEntry ->
                val route: EpisodeRoute = backStackEntry.toRoute()
                EpisodeScreen(
                    episodeId = UUID.fromString(route.episodeId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToPerson = { personId ->
                        navController.safeNavigate(PersonRoute(personId.toString()))
                    },
                    navigateToSeason = { seasonId ->
                        navController.safeNavigate(SeasonRoute(seasonId = seasonId.toString())) {
                            popUpTo(SeasonRoute(seasonId = seasonId.toString()))
                            launchSingleTop = true
                        }
                    },
                    navigateToShow = { showId ->
                        navController.safeNavigate(ShowRoute(showId = showId.toString()))
                    },
                    navigateToSettings = { navController.safeNavigate(settingsRootRoute()) },
                )
            }
            composable<PersonRoute> { backStackEntry ->
                val route: PersonRoute = backStackEntry.toRoute()
                PersonScreen(
                    personId = UUID.fromString(route.personId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToItem = { item ->
                        navigateToItem(navController = navController, item = item)
                    },
                    navigateToSettings = { navController.safeNavigate(settingsRootRoute()) },
                )
            }
            composable<SettingsRoute> { backStackEntry ->
                val route: SettingsRoute = backStackEntry.toRoute()
                SettingsScreen(
                    indexes = route.indexes,
                    navigateToSettings = { indexes ->
                        navController.safeNavigate(SettingsRoute(indexes = indexes))
                    },
                    navigateToSettingsFileEdit = { filePath ->
                        navController.safeNavigate(SettingsFileEditRoute(filePath = filePath))
                    },
                    navigateToAbout = { navController.safeNavigate(AboutRoute) },
                    navigateToAutoDownloadRules = {
                        navController.safeNavigate(AutoDownloadRulesRoute)
                    },
                    navigateToLocalAccess = { navController.safeNavigate(LocalAccessRoute) },
                    navigateToScanLibraries = { navController.safeNavigate(ScanLibrariesRoute) },
                    navigateToBackupSettings = { navController.safeNavigate(BackupSettingsRoute) },
                    navigateToQrExport = { navController.safeNavigate(QrExportRoute) },
                    navigateToProfiles = { navController.safeNavigate(ProfilesRoute) },
                    navigateToProfileDetail = { profileId ->
                        navController.safeNavigate(ProfileDetailRoute(profileId = profileId))
                    },
                    navigateToHomeLayout = { navController.safeNavigate(HomeLayoutSettingsRoute) },
                    navigateToNavigationBar = {
                        navController.safeNavigate(NavigationBarSettingsRoute)
                    },
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<HomeLayoutSettingsRoute> {
                HomeLayoutSettingsScreen(navigateBack = { navController.safePopBackStack() })
            }
            composable<NavigationBarSettingsRoute> {
                NavigationBarSettingsScreen(navigateBack = { navController.safePopBackStack() })
            }
            composable<BackupSettingsRoute> {
                BackupSettingsScreen(
                    navigateBack = { navController.safePopBackStack() },
                    navigateToRestore = { navController.safeNavigate(RestoreBackupRoute) },
                )
            }
            composable<QrExportRoute> {
                QrExportScreen(navigateBack = { navController.safePopBackStack() })
            }
            composable<ProfilesRoute> {
                ProfilesListScreen(
                    navigateBack = { navController.safePopBackStack() },
                    navigateToProfileDetail = { profileId ->
                        navController.safeNavigate(ProfileDetailRoute(profileId = profileId))
                    },
                )
            }
            composable<ProfileDetailRoute> { backStackEntry ->
                val route: ProfileDetailRoute = backStackEntry.toRoute()
                ProfileDetailScreen(
                    profileId = route.profileId,
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<RestoreBackupRoute> {
                RestoreBackupScreen(onBackClick = { navController.safePopBackStack() })
            }
            composable<ScanQrRoute> { backStackEntry ->
                val route: ScanQrRoute = backStackEntry.toRoute()
                QrScanScreen(
                    onBackClick = { navController.safePopBackStack() },
                    initialRaw = route.rawPayload,
                )
            }
            composable<SettingsFileEditRoute> { backStackEntry ->
                val route: SettingsFileEditRoute = backStackEntry.toRoute()
                SettingsFileEditScreen(
                    filePath = route.filePath,
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<AboutRoute> {
                AboutScreen(navigateBack = { navController.safePopBackStack() })
            }
        }
    }
}

private fun navigateHome(navController: NavHostController) {
    navController.safeNavigate(HomeRoute) {
        popUpTo(navController.graph.startDestinationId)
        launchSingleTop = true
    }
}

private fun navigateToItem(navController: NavHostController, item: JollyfinItem) {
    when (item) {
        is JollyfinBoxSet ->
            navController.safeNavigate(
                CollectionRoute(collectionId = item.id.toString(), collectionName = item.name)
            )
        is JollyfinMovie -> navController.safeNavigate(MovieRoute(movieId = item.id.toString()))
        is JollyfinShow -> navController.safeNavigate(ShowRoute(showId = item.id.toString()))
        is JollyfinSeason -> navController.safeNavigate(SeasonRoute(seasonId = item.id.toString()))
        is JollyfinEpisode ->
            navController.safeNavigate(EpisodeRoute(episodeId = item.id.toString()))
        is JollyfinCollection ->
            navController.safeNavigate(
                LibraryRoute(
                    libraryId = item.id.toString(),
                    libraryName = item.name,
                    libraryType = item.type,
                )
            )
        is JollyfinFolder ->
            navController.safeNavigate(
                LibraryRoute(
                    libraryId = item.id.toString(),
                    libraryName = item.name,
                    libraryType = CollectionType.Folders,
                )
            )
        else -> Unit
    }
}

private fun <T : Any> NavHostController.safeNavigate(
    route: T,
    navOptions: NavOptions? = null,
    navigatorExtras: Navigator.Extras? = null,
) {
    if (this.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        this.navigate(route, navOptions, navigatorExtras)
    }
}

private fun <T : Any> NavHostController.safeNavigate(
    route: T,
    builder: NavOptionsBuilder.() -> Unit,
) {
    if (this.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        this.navigate(route, builder)
    }
}

private fun NavHostController.safePopBackStack(): Boolean {
    return if (this.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        this.popBackStack()
    } else {
        false
    }
}
