package com.podmix.v2.features.hub

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.podmix.v2.domain.model.BackendHealth

data class HubUiState(
    val backendHealth: BackendHealth? = null,
    val destinations: List<HubDestinationCard> = defaultHubCards
)

data class HubDestinationCard(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

val defaultHubCards = listOf(
    HubDestinationCard("podcast", "Podcast", "Importer, rafraichir, analyser", Icons.Outlined.Podcasts),
    HubDestinationCard("liveset", "Liveset", "Tracklist et timestamps centraux", Icons.Outlined.GraphicEq),
    HubDestinationCard("emission", "Emission", "Ecoute et refresh sans tracklist", Icons.Outlined.LibraryMusic),
    HubDestinationCard("radio", "Radio", "Live only, sans favoris", Icons.Outlined.Radio),
    HubDestinationCard("favorites", "Favoris", "Episodes podcast et livesets", Icons.Outlined.FavoriteBorder),
    HubDestinationCard("savedtracks", "Tracks sauvegardees", "Spotify, Deezer, reprise au timestamp", Icons.Outlined.Album),
    HubDestinationCard("settings", "Settings", "Backend, sync, comportement local", Icons.Outlined.Settings)
)
