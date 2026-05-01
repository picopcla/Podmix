package com.podmix.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podmix.domain.model.Episode
import com.podmix.service.DownloadState
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WigWagIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wigwag")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wigwag_phase"
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier.size(5.dp).background(
                Color(0xFFA855F7).copy(alpha = phase), CircleShape
            )
        )
        Box(
            Modifier.size(5.dp).background(
                Color(0xFFA855F7).copy(alpha = 1f - phase), CircleShape
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(
    episode: Episode,
    isPlaying: Boolean = false,
    hasTracklist: Boolean = false,
    downloadState: DownloadState = DownloadState.Idle,
    refinementPct: Int? = null, // null=pas en cours, 0-100=progression
    onClick: () -> Unit
) {
    val hasAudio = episode.audioUrl.isNotBlank()
        || !episode.youtubeVideoId.isNullOrBlank()
        || episode.mixcloudKey != null
        || !episode.soundcloudTrackUrl.isNullOrBlank()
        || episode.localAudioPath != null
    val listenedAlpha = if (episode.isListened) 0.45f else 1f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(listenedAlpha)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (episode.trackRefinementStatus == "refining") {
                // Affinage en cours → wig-wag + pourcentage
                WigWagIndicator(Modifier.padding(end = 2.dp))
                if (refinementPct != null) {
                    Text(
                        text = "$refinementPct%",
                        color = Color(0xFFA855F7),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            } else {
                // Dot : rouge=pas d'audio, orange=audio sans TL ou TTE, vert=TL réelle affinée
                val dotColor = when {
                    !hasAudio -> Color(0xFFFF4444)
                    !hasTracklist || episode.trackRefinementStatus == "pending"
                        || episode.trackRefinementStatus == "chroma_pending" -> Color(0xFFFF9800)
                    else -> Color(0xFF4CAF50)
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape)
                )
            }

            val dateStr = episode.datePublished
                ?.takeIf { it > 0L }
                ?.let { SimpleDateFormat("MMM yyyy", Locale.ENGLISH).format(Date(it)) }
                ?: ""
            val durStr = if (episode.durationSeconds > 0) {
                val h = episode.durationSeconds / 3600
                val m = (episode.durationSeconds % 3600) / 60
                val s = episode.durationSeconds % 60
                if (h > 0) String.format("%d:%02d:%02d", h, m, s)
                else String.format("%d:%02d", m, s)
            } else ""

            // Date fixe (ne défile pas)
            if (dateStr.isNotEmpty()) {
                Text(
                    text = "$dateStr · ",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            // Titre seul défile en marquee
            Text(
                text = episode.title,
                color = if (isPlaying) Color(0xFFC084FC) else TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .basicMarquee(iterations = Int.MAX_VALUE)
            )
            if (downloadState is DownloadState.Downloaded) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Téléchargé",
                    tint = AccentPrimary,
                    modifier = Modifier.size(14.dp).padding(start = 4.dp)
                )
            }
            if (durStr.isNotEmpty()) {
                Text(
                    text = durStr,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // Download indicator
        when (downloadState) {
            is DownloadState.Downloading -> {
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { downloadState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(start = 14.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = Color(0xFFFFA726),
                    trackColor = SurfaceSecondary
                )
            }
            is DownloadState.Downloaded -> {
                // small green dot already handled by hasTracklist dot above; nothing extra needed
            }
            else -> {}
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
