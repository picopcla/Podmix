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
            PlaceholderScreen("Podcast", "Import, refresh, analyse VPS et lecture.")
        }
        composable("liveset") {
            PlaceholderScreen("Liveset", "Tracklist et timestamps gouvernes par le VPS.")
        }
        composable("emission") {
            PlaceholderScreen("Emission", "Jamais de tracklist, seulement import, refresh et lecture.")
        }
        composable("radio") {
            PlaceholderScreen("Radio", "Live-only, sans favoris ni tracklist persistante.")
        }
        composable("favorites") {
            PlaceholderScreen("Favoris", "Seuls les podcasts et livesets favoris vivent ici.")
        }
        composable("savedtracks") {
            PlaceholderScreen("Tracks sauvegardees", "Liens Spotify et Deezer attaches aux tracks extraites.")
        }
        composable("settings") {
            PlaceholderScreen("Settings", "Configuration locale, sync leger, backend public.")
        }
        composable("player") {
            PlaceholderScreen("Player", "Le player V2 ne consomme qu'un PlayableMedia.")
        }
    }
}
