package com.podmix.ui.screens.episode

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.podmix.service.DownloadState
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
    val sourceResults by viewModel.sourceResults.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val isAudioAnalyzing by viewModel.isAudioAnalyzing.collectAsState()
    val audioAnalysisStatus by viewModel.audioAnalysisStatus.collectAsState()
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
                actions = {
                    // Bouton Play/Pause — toujours visible en haut
                    IconButton(onClick = { viewModel.play() }) {
                        Icon(
                            imageVector = if (isThisEpisode && playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isThisEpisode && playerState.isPlaying) "Pause" else "Lecture",
                            tint = AccentPrimary
                        )
                    }
                    // Bouton téléchargement
                    when (val dl = downloadState) {
                        is DownloadState.Idle -> IconButton(onClick = { viewModel.startDownload() }) {
                            Icon(Icons.Default.Download, "Télécharger", tint = TextPrimary)
                        }
                        is DownloadState.Downloading -> Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { dl.progress },
                                modifier = Modifier.size(28.dp),
                                color = AccentPrimary,
                                strokeWidth = 2.dp
                            )
                            IconButton(onClick = { viewModel.cancelDownload() }) {
                                Icon(Icons.Default.Close, "Annuler", tint = TextPrimary,
                                    modifier = Modifier.size(14.dp))
                            }
                        }
                        is DownloadState.Downloaded -> IconButton(onClick = { viewModel.deleteDownload() }) {
                            Icon(Icons.Default.Done, "Téléchargé — appuyer pour supprimer",
                                tint = AccentPrimary)
                        }
                        is DownloadState.Error -> IconButton(onClick = { viewModel.startDownload() }) {
                            Icon(Icons.Default.ErrorOutline, "Erreur — réessayer", tint = Color.Red)
                        }
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

            // Diagnostic banner
            if (sourceResults.isNotEmpty()) {
                item {
                    TracklistDiagnosticBanner(
                        results = sourceResults,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
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
                            fontSize = 14.sp
                        )
                        // Badge source — traduit les codes internes en labels lisibles
                        val sourceLabel = when (tracks.firstOrNull()?.source) {
                            "1001tl"      -> "1001TL"
                            "mixcloud"    -> "Mixcloud"
                            "comments"    -> "YouTube comments"
                            "ytdlp"       -> "yt-dlp"
                            "shazam"      -> "Shazam"
                            "mixesdb"     -> "MixesDB"
                            "timestamped" -> "description"
                            "uniform"     -> "description (estimé)"
                            "mixcloud_sections" -> "Mixcloud sections"
                            else          -> tracks.firstOrNull()?.source ?: ""
                        }
                        if (sourceLabel.isNotBlank()) {
                            Text(
                                text = "· $sourceLabel",
                                color = AccentPrimary,
                                fontSize = 11.sp,
                                modifier = androidx.compose.ui.Modifier.padding(start = 6.dp).weight(1f)
                            )
                        } else {
                            Spacer(modifier = androidx.compose.ui.Modifier.weight(1f))
                        }
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
            } else if (isAudioAnalyzing) {
                // ── Analyse audio on-device en cours : wig wag orange (2 LEDs) ──
                item {
                    WigWagAnalyzing(status = audioAnalysisStatus)
                }
            } else {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(16.dp))
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

/**
 * Indicateur wig wag orange (2 LEDs alternées) affiché pendant l'analyse audio on-device.
 * Les deux cercles oranges clignotent en opposition de phase pour simuler un feu wig wag.
 */
@Composable
private fun WigWagAnalyzing(status: String) {
    val orangeColor = Color(0xFFFF8C00)

    val transition = rememberInfiniteTransition(label = "wigwag")

    // LED 1 : commence allumée, s'éteint, se rallume...
    val scale1 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "led1_scale"
    )

    // LED 2 : opposition de phase — commence éteinte
    val scale2 by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "led2_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Les deux LEDs en wig wag
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .scale(scale1)
                    .clip(CircleShape)
                    .background(orangeColor)
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .scale(scale2)
                    .clip(CircleShape)
                    .background(orangeColor)
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = "Analyse audio en cours...",
            color = orangeColor,
            fontSize = 12.sp
        )

        if (status.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = status,
                color = TextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
