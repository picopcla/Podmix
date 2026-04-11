package com.podmix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.podmix.ui.screens.player.PlayerViewModel
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.Background
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary

@Composable
fun FullScreenPlayer(
    onDismiss: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.playerState.collectAsState()
    val episode = state.currentEpisode ?: run { onDismiss(); return }
    val podcast = state.currentPodcast
    val track = state.currentTracks.getOrNull(state.currentTrackIndex)
    val duration = state.duration.coerceAtLeast(1L)
    val position = state.currentPosition
    var isSeeking by remember { mutableFloatStateOf(-1f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* consume taps */ }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.KeyboardArrowDown, "Fermer",
                        tint = TextPrimary, modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Artwork
            val artworkUrl = episode.artworkUrl ?: podcast?.logoUrl
            if (artworkUrl != null) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = "Artwork",
                    modifier = Modifier.size(280.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.size(280.dp).clip(RoundedCornerShape(16.dp)).background(SurfaceSecondary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = podcast?.name?.take(2)?.uppercase() ?: "?",
                        color = TextSecondary, fontSize = 48.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Track info — centered
            Text(
                text = track?.let { "${it.artist} — ${it.title}" } ?: episode.title,
                color = if (track != null) Color(0xFFC084FC) else TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (track != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = episode.title,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))

            // Seek bar
            Slider(
                value = if (isSeeking >= 0f) isSeeking else (position.toFloat() / duration),
                onValueChange = { isSeeking = it },
                onValueChangeFinished = {
                    if (isSeeking >= 0f) {
                        viewModel.seekTo((isSeeking * duration).toLong())
                        isSeeking = -1f
                    }
                },
                modifier = Modifier.fillMaxWidth().height(32.dp),
                colors = SliderDefaults.colors(
                    thumbColor = AccentPrimary,
                    activeTrackColor = AccentPrimary,
                    inactiveTrackColor = SurfaceSecondary
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatMs(position), color = TextSecondary, fontSize = 12.sp)
                Text(formatMs(duration), color = TextSecondary, fontSize = 12.sp)
            }

            Spacer(Modifier.height(24.dp))

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.toggleLoop() }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Repeat, "Loop",
                        tint = if (state.isLoopEnabled) AccentPrimary else TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = { viewModel.prevTrack() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Prev", tint = TextPrimary, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = { viewModel.playPause() },
                    modifier = Modifier.size(80.dp).background(AccentPrimary, CircleShape)
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = { viewModel.nextTrack() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, "Next", tint = TextPrimary, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.width(16.dp))
                // Favorite
                if (track != null) {
                    IconButton(
                        onClick = { viewModel.toggleFavorite(track.id) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favori",
                            tint = if (track.isFavorite) AccentPrimary else TextSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
    }
}
