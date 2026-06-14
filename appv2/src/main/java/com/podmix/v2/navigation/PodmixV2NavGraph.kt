package com.podmix.v2.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.podmix.v2.features.hub.HubRoute
import com.podmix.v2.features.placeholders.PlaceholderScreen

@Composable
fun PodmixV2NavGraph(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "hub",
        modifier = modifier
    ) {
        composable("hub") {
            HubRoute(onNavigate = navController::navigate)
        }
        composable("podcast") {
            PlaceholderScreen(
                title = "Podcast",
                subtitle = "Import, refresh, analyse VPS et lecture.",
                onBack = navController::navigateUp
            )
        }
        composable("liveset") {
            PlaceholderScreen(
                title = "Liveset",
                subtitle = "Tracklist et timestamps gouvernes par le VPS.",
                onBack = navController::navigateUp
            )
        }
        composable("emission") {
            PlaceholderScreen(
                title = "Emission",
                subtitle = "Jamais de tracklist, seulement import, refresh et lecture.",
                onBack = navController::navigateUp
            )
        }
        composable("radio") {
            PlaceholderScreen(
                title = "Radio",
                subtitle = "Live-only, sans favoris ni tracklist persistante.",
                onBack = navController::navigateUp
            )
        }
        composable("favorites") {
            PlaceholderScreen(
                title = "Favoris",
                subtitle = "Seuls les podcasts et livesets favoris vivent ici.",
                onBack = navController::navigateUp
            )
        }
        composable("savedtracks") {
            PlaceholderScreen(
                title = "Tracks sauvegardees",
                subtitle = "Liens Spotify et Deezer attaches aux tracks extraites.",
                onBack = navController::navigateUp
            )
        }
        composable("settings") {
            PlaceholderScreen(
                title = "Settings",
                subtitle = "Configuration locale, sync leger, backend public.",
                onBack = navController::navigateUp
            )
        }
        composable("player") {
            PlaceholderScreen(
                title = "Player",
                subtitle = "Le player V2 ne consomme qu'un PlayableMedia.",
                onBack = navController::navigateUp
            )
        }
    }
}
