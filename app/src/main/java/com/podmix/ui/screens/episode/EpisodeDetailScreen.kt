package com.podmix.ui.screens.episode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.podmix.ui.components.TrackRow
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.Background
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailScreen(
    onBack: () -> Unit,
    viewModel: EpisodeDetailViewModel = hiltViewModel()
) {
    val episode by viewModel.episode.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val detectStatus by viewModel.detectStatus.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val isThisEpisode = playerState.currentEpisode?.id == episode?.id
    val isDetecting = detectStatus.isNotBlank()

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = episode?.title ?: "",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Episode description snippet
            item {
                episode?.description?.let { desc ->
                    val clean = desc.replace(Regex("<[^>]*>"), "").take(200)
                    if (clean.isNotBlank()) {
                        Text(
                            text = clean,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Tracklist section
            if (isDetecting) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = AccentPrimary,
                            trackColor = SurfaceSecondary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = detectStatus,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else if (tracks.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tracklist (${tracks.size})",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.play() },
                            modifier = Modifier
                                .size(32.dp)
                                .background(AccentPrimary, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isThisEpisode && playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { viewModel.redetectTracks() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, "Re-detect tracklist", tint = AccentPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                items(tracks, key = { it.id }) { track ->
                    val trackIndex = tracks.indexOf(track)
                    val nextTrackStart = tracks.getOrNull(trackIndex + 1)?.startTimeSec
                    val currentPosSec = playerState.currentPosition / 1000f
                    val isTrackPlaying = isThisEpisode &&
                        currentPosSec >= track.startTimeSec &&
                        (nextTrackStart == null || currentPosSec < nextTrackStart)
                    TrackRow(
                        track = track,
                        isPlaying = isTrackPlaying,
                        onClick = { viewModel.seekToTrack(track) },
                        onToggleFavorite = { viewModel.toggleFavorite(track.id) }
                    )
                }
            } else {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Play button even without tracklist
                        Spacer(Modifier.height(16.dp))
                        IconButton(
                            onClick = { viewModel.play() },
                            modifier = Modifier
                                .size(56.dp)
                                .background(AccentPrimary, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isThisEpisode && playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Aucune tracklist trouvée",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        IconButton(
                            onClick = { viewModel.redetectTracks() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, "Re-détecter", tint = AccentPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // Bottom spacing for MiniPlayer + NavBar
            item { Spacer(Modifier.height(180.dp)) }
        }
    }
}
