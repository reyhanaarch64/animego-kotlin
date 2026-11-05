package app.animego.inc.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.animego.inc.AppGraph
import app.animego.inc.ui.components.PlaybackMiniPlayer
import app.animego.inc.core.player.PlaybackEngine
import app.animego.inc.ui.screens.BrowseScreen
import app.animego.inc.ui.screens.DetailScreen
import app.animego.inc.ui.screens.DownloadsScreen
import app.animego.inc.ui.screens.FavoritesScreen
import app.animego.inc.ui.screens.GenreListScreen
import app.animego.inc.ui.screens.GenreScreen
import app.animego.inc.ui.screens.HistoryScreen
import app.animego.inc.ui.screens.HomeScreen
import app.animego.inc.ui.screens.PlayerScreen
import app.animego.inc.ui.screens.ScheduleScreen
import app.animego.inc.ui.screens.SearchScreen
import app.animego.inc.ui.screens.SettingsScreen
import app.animego.inc.ui.theme.AnimeGoTheme
import com.composables.icons.lucide.Compass
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search

private data class BottomDestination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun AnimeGoApp(graph: AppGraph, initialRoute: String = "home", routeRequestId: Long = 0L) {
    val dark by graph.settings.darkTheme.collectAsState(initial = true)
    val dynamic by graph.settings.dynamicColor.collectAsState(initial = false)
    val navController = rememberNavController()
    val context = LocalContext.current
    val playback by PlaybackEngine.snapshot.collectAsState()
    LaunchedEffect(routeRequestId) {
        if (routeRequestId > 0L) {
            navController.navigate(initialRoute) { launchSingleTop = true }
        }
    }
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val showBottomBar = route in setOf("home", "browse", "search", "favorites", "downloads")
    val items = listOf(
        BottomDestination("home", "beranda", Lucide.House),
        BottomDestination("browse", "jelajah", Lucide.Compass),
        BottomDestination("search", "cari", Lucide.Search),
        BottomDestination("favorites", "favorit", Lucide.Heart),
        BottomDestination("downloads", "offline", Lucide.Download)
    )

    AnimeGoTheme(darkTheme = dark, dynamicColor = dynamic) {
        Scaffold(
            bottomBar = {
                Column {
                    if (playback.source.isNotBlank() && !route.startsWith("player")) {
                        PlaybackMiniPlayer(
                            playback = playback,
                            onOpen = {
                                navController.navigate(playerRoute(
                                    slug = playback.slug,
                                    source = playback.source,
                                    title = playback.title,
                                    episode = playback.episode,
                                    cover = playback.cover,
                                    offline = playback.offline
                                )) { launchSingleTop = true }
                            },
                            onToggle = { PlaybackEngine.toggle(context) },
                            onClose = { PlaybackEngine.stop(context) }
                        )
                    }
                    if (showBottomBar) {
                        NavigationBar {
                            items.forEach { item ->
                                val selected = route == item.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(item.route) {
                                            popUpTo("home") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label) }
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(navController = navController, startDestination = initialRoute, modifier = Modifier.padding(padding)) {
                composable("home") {
                    HomeScreen(
                        graph = graph,
                        onOpenAnime = { navController.navigate("detail/${Uri.encode(it)}") },
                        onOpenHistory = { navController.navigate("history") },
                        onOpenBrowse = { navController.navigate("browse") },
                        onOpenSettings = { navController.navigate("settings") },
                        onResumeHistory = { row -> navController.navigate(playerRoute(source = row.source, slug = row.slug, title = row.title, episode = row.ep, cover = row.cover, position = row.position)) }
                    )
                }
                composable("browse") {
                    BrowseScreen(graph,
                        onOpenAnime = { navController.navigate("detail/${Uri.encode(it)}") },
                        onOpenGenres = { navController.navigate("genres") },
                        onOpenSchedule = { navController.navigate("schedule") })
                }
                composable("search") {
                    SearchScreen(graph, onOpenAnime = { navController.navigate("detail/${Uri.encode(it)}") })
                }
                composable("favorites") {
                    FavoritesScreen(graph, onOpenAnime = { navController.navigate("detail/${Uri.encode(it)}") })
                }
                composable("history") {
                    HistoryScreen(graph, onResume = { row ->
                        navController.navigate(playerRoute(source = row.source, slug = row.slug, title = row.title,
                            episode = row.ep, cover = row.cover, position = row.position))
                    })
                }
                composable("downloads") {
                    DownloadsScreen(graph, onPlayOffline = { row ->
                        navController.navigate(playerRoute(source = row.path, slug = row.slug, title = row.title,
                            episode = row.ep, cover = "", offline = true))
                    })
                }
                composable("genres") {
                    GenreListScreen(graph, onOpenGenre = { navController.navigate("genre/${Uri.encode(it)}") }, onBack = { navController.popBackStack() })
                }
                composable("genre/{slug}", arguments = listOf(navArgument("slug") { type = NavType.StringType })) { entry ->
                    GenreScreen(graph, entry.arguments?.getString("slug").orEmpty(),
                        onOpenAnime = { navController.navigate("detail/${Uri.encode(it)}") }, onBack = { navController.popBackStack() })
                }
                composable("schedule") {
                    ScheduleScreen(graph, onOpenAnime = { navController.navigate("detail/${Uri.encode(it)}") }, onBack = { navController.popBackStack() })
                }
                composable("detail/{slug}", arguments = listOf(navArgument("slug") { type = NavType.StringType })) { entry ->
                    val detailSlug = entry.arguments?.getString("slug").orEmpty()
                    DetailScreen(graph, detailSlug,
                        onBack = { navController.popBackStack() },
                        onPlay = { slug, index -> navController.navigate(playerRoute(slug = slug, index = index)) },
                        onOpenGenre = { genre -> navController.navigate("genre/${Uri.encode(genre)}") })
                }
                composable(
                    route = "player?slug={slug}&index={index}&source={source}&title={title}&episode={episode}&cover={cover}&position={position}&offline={offline}",
                    arguments = listOf(
                        navArgument("slug") { type = NavType.StringType; defaultValue = "" },
                        navArgument("index") { type = NavType.IntType; defaultValue = -1 },
                        navArgument("source") { type = NavType.StringType; defaultValue = "" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                        navArgument("episode") { type = NavType.StringType; defaultValue = "" },
                        navArgument("cover") { type = NavType.StringType; defaultValue = "" },
                        navArgument("position") { type = NavType.FloatType; defaultValue = 0f },
                        navArgument("offline") { type = NavType.BoolType; defaultValue = false }
                    )
                ) { entry ->
                    val args = entry.arguments
                    PlayerScreen(
                        graph = graph,
                        slug = args?.getString("slug").orEmpty(),
                        index = args?.getInt("index") ?: -1,
                        source = args?.getString("source").orEmpty(),
                        title = args?.getString("title").orEmpty(),
                        episode = args?.getString("episode").orEmpty(),
                        cover = args?.getString("cover").orEmpty(),
                        initialPosition = ((args?.getFloat("position") ?: 0f) * 1000).toLong(),
                        offline = args?.getBoolean("offline") ?: false,
                        onBack = { navController.popBackStack() },
                        onOpenDetail = { navController.navigate("detail/${Uri.encode(args?.getString("slug").orEmpty())}") }
                    )
                }
                composable("settings") {
                    SettingsScreen(graph, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

private fun playerRoute(
    slug: String = "", index: Int = -1, source: String = "", title: String = "", episode: String = "",
    cover: String = "", position: Double = 0.0, offline: Boolean = false
): String = buildString {
    append("player?slug=${Uri.encode(slug)}&index=$index")
    append("&source=${Uri.encode(source)}&title=${Uri.encode(title)}&episode=${Uri.encode(episode)}")
    append("&cover=${Uri.encode(cover)}&position=${position.toFloat()}&offline=$offline")
}
