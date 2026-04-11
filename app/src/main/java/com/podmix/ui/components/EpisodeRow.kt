package com.podmix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podmix.domain.model.Episode
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EpisodeRow(
    episode: Episode,
    isPlaying: Boolean = false,
    hasTracklist: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tracklist indicator dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (hasTracklist) Color(0xFF4ADE80) else Color(0xFF555555),
                        CircleShape
                    )
            )

            val dateStr = episode.datePublished?.let {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it))
            } ?: ""
            val durStr = if (episode.durationSeconds > 0) {
                val h = episode.durationSeconds / 3600
                val m = (episode.durationSeconds % 3600) / 60
                val s = episode.durationSeconds % 60
                if (h > 0) String.format("%d:%02d:%02d", h, m, s)
                else String.format("%d:%02d", m, s)
            } else ""

            Text(
                text = buildString {
                    if (dateStr.isNotEmpty()) append("$dateStr · ")
                    append(episode.title)
                },
                color = if (isPlaying) Color(0xFFC084FC) else TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            if (durStr.isNotEmpty()) {
                Text(
                    text = durStr,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // Progress bar
        if (episode.durationSeconds > 0 && episode.progressSeconds > 0) {
            val progress = (episode.progressSeconds.toFloat() / episode.durationSeconds).coerceIn(0f, 1f)
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(start = 14.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = if (progress >= 0.9f) Color(0xFF4ADE80) else AccentPrimary,
                trackColor = SurfaceSecondary,
            )
        }
    }
}
