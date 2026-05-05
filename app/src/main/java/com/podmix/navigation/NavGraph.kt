package com.podmix.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.podmix.ui.screens.cast.CastScreen
import com.podmix.ui.screens.episode.EpisodeDetailScreen
import com.podmix.ui.screens.favorites.FavoritesScreen
import com.podmix.ui.screens.liveset.AddDjScreen
import com.podmix.ui.screens.liveset.DjDetailScreen
import com.podmix.ui.screens.podcasts.AddPodcastScreen
import com.podmix.ui.screens.podcasts.PodcastDetailScreen
import com.podmix.ui.screens.podcasts.PodcastsScreen
import com.podmix.ui.screens.radio.AddRadioScreen
import com.podmix.ui.screens.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Podcasts : Screen("podcasts")
    object Favorites : Screen("favorites")
    object Cast : Screen("cast")
    object Settings : Screen("settings")
    object AddPodcast : Screen("add_podcast")
    object AddEmission : Screen("add_emission")
    object AddDj : Screen("add_dj")
    object AddRadio : Screen("add_radio")
    object PodcastDetail : Screen("podcast_detail/{podcastId}") {
        fun createRoute(id: Int) = "podcast_detail/$id"
    }
    object DjDetail : Screen("dj_detail/{djId}") {
        fun createRoute(id: Int) = "dj_detail/$id"
    }
    object AddSets : Screen("add_sets/{djId}") {
        fun createRoute(djId: Int) = "add_sets/$djId"
    }
    object EpisodeDetail : Screen("episode_detail/{podcastId}/{episodeId}") {
        fun createRoute(podcastId: Int, episodeId: Int) = "episode_detail/$podcastId/$episodeId"
    }
}

@Composable
fun NavGraph(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController, startDestination = Screen.Podcasts.route, modifier = modifier) {

        composable(Screen.Podcasts.route) {
            PodcastsScreen(
                onPodcastClick = { navController.navigate(Screen.PodcastDetail.createRoute(it)) },
                onAddPodcast = { navController.navigate(Screen.AddPodcast.route) },
                onDjClick = { navController.navigate(Screen.DjDetail.createRoute(it)) },
                onAddDj = { navController.navigate(Screen.AddDj.route) },
                onEmissionClick = { navController.navigate(Screen.PodcastDetail.createRoute(it)) },
                onAddEmission = { navController.navigate(Screen.AddEmission.route) },
                onAddRadio = { navController.navigate(Screen.AddRadio.route) },
                onSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Favorites.route) { FavoritesScreen() }
        composable(Screen.Cast.route) { CastScreen() }
        composable(Screen.AddPodcast.route) { AddPodcastScreen(onBack = { navController.popBackStack() }, type = "podcast") }
        composable(Screen.AddEmission.route) { AddPodcastScreen(onBack = { navController.popBackStack() }, type = "emission") }

        composable(Screen.AddRadio.route) {
            AddRadioScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.AddDj.route) {
            AddDjScreen(
                onBack = { navController.popBackStack() },
                onDjAdded = { id ->
                    navController.navigate(Screen.DjDetail.createRoute(id)) {
                        popUpTo(Screen.Podcasts.route)
                    }
                }
            )
        }

        composable(
            Screen.PodcastDetail.route,
            arguments = listOf(navArgument("podcastId") { type = NavType.IntType })
        ) {
            PodcastDetailScreen(
                onEpisodeClick = { pid, eid -> navController.navigate(Screen.EpisodeDetail.createRoute(pid, eid)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Screen.DjDetail.route,
            arguments = listOf(navArgument("djId") { type = NavType.IntType })
        ) {
            DjDetailScreen(
                onEpisodeClick = { djId, eid -> navController.navigate(Screen.EpisodeDetail.createRoute(djId, eid)) },
                onBack = { navController.popBackStack() },
                onAddMore = { djId -> navController.navigate(Screen.AddSets.createRoute(djId)) }
            )
        }

        composable(
            Screen.AddSets.route,
            arguments = listOf(navArgument("djId") { type = NavType.IntType })
        ) {
            AddDjScreen(
                onBack = { navController.popBackStack() },
                onDjAdded = { navController.popBackStack() }
            )
        }

        composable(
            Screen.EpisodeDetail.route,
            arguments = listOf(
                navArgument("podcastId") { type = NavType.IntType },
                navArgument("episodeId") { type = NavType.IntType }
            )
        ) {
            EpisodeDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}
