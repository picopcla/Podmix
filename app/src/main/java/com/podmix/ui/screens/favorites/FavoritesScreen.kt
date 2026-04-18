package com.podmix.ui.screens.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.res.painterResource
import com.podmix.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import com.podmix.data.local.dao.FavoriteWithInfo
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.Background
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(viewModel: FavoritesViewModel = hiltViewModel()) {
    val favorites by viewModel.favorites.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val currentEpisodeId = playerState.currentEpisode?.id
    val currentPosSec = playerState.currentPosition / 1000f
    val uriHandler = LocalUriHandler.current

    // Backfill au premier affichage seulement (déjà appelé dans init, ici = filet de sécurité)
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.backfillSpotify()
        viewModel.backfillDeezer()
    }

    val podcastFavs = favorites.filter { it.podcastType == "podcast" }
    val djFavs = favorites.filter { it.podcastType == "dj" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        TopAppBar(
            title = { Text("Favoris", color = TextPrimary, fontSize = 18.sp) },
            actions = {
                if (favorites.isNotEmpty()) {
                    IconButton(onClick = { viewModel.forceRefreshDeezer() }) {
                        Icon(Icons.Default.Refresh, "Rafraîchir Deezer", tint = Color(0xFFEF5466))
                    }
                    IconButton(onClick = { viewModel.playAllFavorites() }) {
                        Icon(Icons.Default.PlayArrow, "Tout lire", tint = AccentPrimary)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
        )

        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Aucun favori pour l'instant",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // --- Podcasts section ---
                if (podcastFavs.isNotEmpty()) {
                    item(key = "section_podcasts") {
                        Text(
                            text = "Podcasts",
                            color = AccentPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                    val grouped = podcastFavs.groupBy { it.podcastId }
                    grouped.forEach { (_, groupFavorites) ->
                        val first = groupFavorites.first()
                        item(key = "header_p_${first.podcastId}") {
                            FavoriteSectionHeader(first)
                        }
                        items(groupFavorites, key = { "p_${it.id}" }) { fav ->
                            FavoriteTrackRow(
                                fav = fav,
                                currentEpisodeId = currentEpisodeId,
                                currentPosSec = currentPosSec,
                                isPlaying = playerState.isPlaying,
                                uriHandler = uriHandler,
                                onPlay = { viewModel.playFromFavorite(fav) },
                                onToggle = { viewModel.toggleFavorite(fav.id) }
                            )
                        }
                        item(key = "spacer_p_${first.podcastId}") {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }

                // --- Live Sets section ---
                if (djFavs.isNotEmpty()) {
                    item(key = "section_livesets") {
                        Text(
                            text = "Live Sets",
                            color = AccentPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                    val grouped = djFavs.groupBy { it.podcastId }
                    grouped.forEach { (_, groupFavorites) ->
                        val first = groupFavorites.first()
                        item(key = "header_d_${first.podcastId}") {
                            FavoriteSectionHeader(first)
                        }
                        items(groupFavorites, key = { "d_${it.id}" }) { fav ->
                            FavoriteTrackRow(
                                fav = fav,
                                currentEpisodeId = currentEpisodeId,
                                currentPosSec = currentPosSec,
                                isPlaying = playerState.isPlaying,
                                uriHandler = uriHandler,
                                onPlay = { viewModel.playFromFavorite(fav) },
                                onToggle = { viewModel.toggleFavorite(fav.id) }
                            )
                        }
                        item(key = "spacer_d_${first.podcastId}") {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteSectionHeader(fav: FavoriteWithInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceSecondary)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = fav.podcastLogoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = fav.podcastName,
            color = TextPrimary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FavoriteTrackRow(
    fav: FavoriteWithInfo,
    currentEpisodeId: Int?,
    currentPosSec: Float,
    isPlaying: Boolean,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    onPlay: () -> Unit,
    onToggle: () -> Unit
) {
    val favEndSec = fav.endTimeSec ?: Float.MAX_VALUE
    val isCurrentlyPlaying = currentEpisodeId == fav.episodeId &&
        isPlaying &&
        currentPosSec >= fav.startTimeSec &&
        currentPosSec < favEndSec
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${fav.artist} — ${fav.title}",
            color = if (isCurrentlyPlaying) Color(0xFFC084FC) else TextPrimary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = {
                if (fav.spotifyUrl != null) {
                    uriHandler.openUri(fav.spotifyUrl!!)
                }
            },
            enabled = fav.spotifyUrl != null,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_spotify),
                contentDescription = "Ouvrir sur Spotify",
                tint = if (fav.spotifyUrl != null) Color.Unspecified
                       else TextSecondary.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(
            onClick = {
                if (fav.deezerUrl != null) {
                    uriHandler.openUri(fav.deezerUrl!!)
                }
            },
            enabled = fav.deezerUrl != null,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_deezer),
                contentDescription = "Ouvrir sur Deezer",
                tint = if (fav.deezerUrl != null) Color.Unspecified
                       else TextSecondary.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(
            onClick = { onToggle() },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = "Retirer des favoris",
                tint = AccentPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
