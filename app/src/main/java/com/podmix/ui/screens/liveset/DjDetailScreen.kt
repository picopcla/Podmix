package com.podmix.ui.screens.liveset

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.podmix.ui.components.EpisodeRow
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.Background
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjDetailScreen(
    onEpisodeClick: (Int, Int) -> Unit,
    onBack: () -> Unit,
    onAddMore: (Int) -> Unit = {},
    viewModel: DjDetailViewModel = hiltViewModel()
) {
    val dj by viewModel.dj.collectAsState()
    val sets by viewModel.sets.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val episodeIdsWithTracks by viewModel.episodeIdsWithTracks.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val refinementProgress by viewModel.refinementProgress.collectAsState()

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = dj?.name ?: "",
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
                    IconButton(onClick = { onAddMore(viewModel.djId) }) {
                        Icon(Icons.Default.Add, contentDescription = "Ajouter des sets", tint = AccentPrimary)
                    }
                    IconButton(onClick = { viewModel.delete(onBack) }) {
                        Icon(Icons.Default.Delete, "Supprimer", tint = TextPrimary)
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
            // DJ photo
            item {
                dj?.logoUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = dj?.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    )
                }
                Spacer(Modifier.height(8.dp))
                // ── Barre de recherche live ──
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { viewModel.search(it) },
                    placeholder = "Rechercher un set..."
                )
            }

            if (sets.isEmpty()) {
                item {
                    Text(
                        "No sets found",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Sets list — swipe left to delete
            items(sets, key = { it.id }) { episode ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            viewModel.deleteEpisode(episode.id)
                            true
                        } else false
                    }
                )
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        val color = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                            Color(0xFFB00020) else Background
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(color)
                                .padding(end = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = Color.White)
                            }
                        }
                    }
                ) {
                    EpisodeRow(
                        episode = episode,
                        isPlaying = playerState.currentEpisode?.id == episode.id && playerState.isPlaying,
                        hasTracklist = episode.id in episodeIdsWithTracks,
                        downloadState = downloadStates[episode.id]
                            ?: if (episode.localAudioPath != null) com.podmix.service.DownloadState.Downloaded(episode.localAudioPath) else com.podmix.service.DownloadState.Idle,
                        refinementPct = refinementProgress[episode.id],
                        onClick = { onEpisodeClick(episode.podcastId, episode.id) }
                    )
                }
            }

            // Bottom spacing
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Rechercher..."
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)),
        placeholder = { Text(placeholder, color = TextSecondary, fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, "Effacer", tint = TextSecondary)
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = SurfaceSecondary,
            unfocusedContainerColor = SurfaceSecondary,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = AccentPrimary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}
