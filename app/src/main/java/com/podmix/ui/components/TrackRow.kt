package com.podmix.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podmix.domain.model.Track
import androidx.compose.ui.graphics.Color
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.AccentSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary

@Composable
fun TrackRow(
    track: Track,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Always show timestamp (0:00 for first track is valid)
        Text(
            text = formatTimestamp(track.startTimeSec),
            color = if (isPlaying) Color(0xFFC084FC) else AccentSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 8.dp)
        )
        // Artist — Title on one line
        Text(
            text = "${track.artist} — ${track.title}",
            color = if (isPlaying) Color(0xFFC084FC) else TextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onToggleFavorite != null) {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (track.isFavorite) "Remove favorite" else "Add favorite",
                    tint = if (track.isFavorite) AccentPrimary else TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun formatTimestamp(seconds: Float): String {
    val totalSec = seconds.toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}
