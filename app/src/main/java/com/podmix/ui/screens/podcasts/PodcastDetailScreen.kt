package com.podmix.ui.screens.podcasts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.podmix.service.PlayerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.podmix.ui.components.EpisodeRow
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.Background
import com.podmix.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    onEpisodeClick: (Int, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: PodcastDetailViewModel = hiltViewModel()
) {
    val podcast by viewModel.podcast.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val episodeIdsWithTracks by viewModel.episodeIdsWithTracks.collectAsState()

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = podcast?.name ?: "",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.delete(onBack) }) {
                        Icon(Icons.Default.Delete, "Supprimer", tint = TextPrimary)
                    }
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            color = AccentPrimary,
                            modifier = Modifier.size(20.dp).padding(end = 8.dp)
                        )
                    } else {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, "Refresh", tint = AccentPrimary)
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
                .padding(padding)
        ) {
            // Podcast logo
            item {
                podcast?.logoUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = podcast?.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Episode list
            items(episodes, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    isPlaying = playerState.currentEpisode?.id == episode.id && playerState.isPlaying,
                    hasTracklist = episode.id in episodeIdsWithTracks,
                    downloadState = if (episode.localAudioPath != null)
                        com.podmix.service.DownloadState.Downloaded(episode.localAudioPath)
                    else com.podmix.service.DownloadState.Idle,
                    onClick = { onEpisodeClick(episode.podcastId, episode.id) }
                )
            }

            // Bottom spacing
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}
