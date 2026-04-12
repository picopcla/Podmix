package com.podmix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.podmix.ui.screens.player.PlayerViewModel
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.SurfaceCard
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary

@Composable
fun MiniPlayer(
    onExpand: () -> Unit = {},
    onNavigateToEpisode: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.playerState.collectAsState()
    val episode = state.currentEpisode ?: return
    val duration = state.duration.coerceAtLeast(1L)
    val position = state.currentPosition
    var isSeeking by remember { mutableFloatStateOf(-1f) }
    val currentTrack = state.currentTracks.getOrNull(state.currentTrackIndex)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard)
    ) {
        // Now-playing info strip — tap to open episode detail
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToEpisode)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = AccentPrimary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (currentTrack != null) {
                    val trackLabel = if (currentTrack.artist.isNotBlank())
                        "${currentTrack.artist} — ${currentTrack.title}"
                    else currentTrack.title
                    Text(
                        text = trackLabel,
                        color = AccentPrimary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        HorizontalDivider(color = SurfaceSecondary, thickness = 0.5.dp)
    }

    // Seek bar + controls
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Loop
        IconButton(onClick = { viewModel.toggleLoop() }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Repeat, "Loop",
                tint = if (state.isLoopEnabled) AccentPrimary else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        // Prev
        IconButton(onClick = { viewModel.prevTrack() }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.SkipPrevious, "Prev", tint = TextPrimary, modifier = Modifier.size(28.dp))
        }
        // Play/Pause
        IconButton(
            onClick = { viewModel.playPause() },
            modifier = Modifier.size(52.dp).background(AccentPrimary, CircleShape)
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        // Next
        IconButton(onClick = { viewModel.nextTrack() }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.SkipNext, "Next", tint = TextPrimary, modifier = Modifier.size(28.dp))
        }

        // Seek bar
        Text(formatMs(position), color = TextSecondary, fontSize = 9.sp, modifier = Modifier.width(34.dp))
        Slider(
            value = if (isSeeking >= 0f) isSeeking else (position.toFloat() / duration),
            onValueChange = { isSeeking = it },
            onValueChangeFinished = {
                if (isSeeking >= 0f) {
                    viewModel.seekTo((isSeeking * duration).toLong())
                    isSeeking = -1f
                }
            },
            modifier = Modifier.weight(1f).height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = AccentPrimary,
                activeTrackColor = AccentPrimary,
                inactiveTrackColor = SurfaceSecondary
            )
        )
        Text(formatMs(duration), color = TextSecondary, fontSize = 9.sp, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)

        // Fullscreen button
        IconButton(onClick = { onExpand() }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Fullscreen, "Plein écran", tint = AccentPrimary, modifier = Modifier.size(24.dp))
        }
    }
}

fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}
