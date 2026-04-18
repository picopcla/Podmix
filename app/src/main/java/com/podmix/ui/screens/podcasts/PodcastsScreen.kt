package com.podmix.ui.screens.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.podmix.R
import com.podmix.domain.model.Podcast
import com.podmix.ui.components.DjCard
import com.podmix.ui.components.PodcastCard
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.Background
import com.podmix.ui.theme.BackgroundBottom
import com.podmix.ui.theme.SurfaceCard
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastsScreen(
    onPodcastClick: (Int) -> Unit,
    onAddPodcast: () -> Unit = {},
    onDjClick: (Int) -> Unit = {},
    onAddDj: () -> Unit = {},
    onEmissionClick: (Int) -> Unit = {},
    onAddEmission: () -> Unit = {},
    onAddRadio: () -> Unit = {},
    viewModel: PodcastsViewModel = hiltViewModel()
) {
    val podcasts by viewModel.podcasts.collectAsState()
    val djs by viewModel.djs.collectAsState()
    val emissions by viewModel.emissions.collectAsState()
    val radios by viewModel.radios.collectAsState()

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Image(
                            painter = painterResource(R.drawable.titre_podmix),
                            contentDescription = "Podmix",
                            modifier = Modifier.height(22.dp)
                        )
                        Text(
                            text = "Your best podcast app for EDM",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.2.sp
                        )
                    }
                },
                actions = {
                    Text(
                        text = "v${com.podmix.BuildConfig.VERSION_NAME} (b${com.podmix.BuildConfig.VERSION_CODE})",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Background, BackgroundBottom)
                    )
                )
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ── PODCASTS ──
            item(span = { GridItemSpan(3) }) {
                SectionHeader("Podcasts", onAdd = onAddPodcast)
            }
            items(podcasts, key = { "p_${it.id}" }) { podcast ->
                PodcastCard(podcast = podcast, onClick = { onPodcastClick(podcast.id) })
            }

            // ── LIVE ──
            item(span = { GridItemSpan(3) }) {
                Spacer(Modifier.height(8.dp))
            }
            item(span = { GridItemSpan(3) }) {
                SectionHeader("Live", onAdd = onAddDj)
            }
            items(djs, key = { "dj_${it.id}" }) { dj ->
                DjCard(dj = dj, onClick = { onDjClick(dj.id) })
            }

            // ── ÉMISSIONS ──
            item(span = { GridItemSpan(3) }) {
                Spacer(Modifier.height(8.dp))
            }
            item(span = { GridItemSpan(3) }) {
                SectionHeader("Émissions", onAdd = onAddEmission)
            }
            items(emissions, key = { "em_${it.id}" }) { emission ->
                PodcastCard(podcast = emission, onClick = { onEmissionClick(emission.id) })
            }

            // ── RADIO ──
            item(span = { GridItemSpan(3) }) {
                Spacer(Modifier.height(8.dp))
            }
            item(span = { GridItemSpan(3) }) {
                SectionHeader("Radio", onAdd = onAddRadio)
            }
            items(radios, key = { "r_${it.id}" }) { radio ->
                RadioCard(radio = radio, onClick = { viewModel.playRadio(radio) })
            }

            // Bottom spacing
            item(span = { GridItemSpan(3) }) {
                Spacer(Modifier.height(160.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onAdd: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                title.uppercase(),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            IconButton(onClick = onAdd, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, "Ajouter", tint = AccentPrimary, modifier = Modifier.size(20.dp))
            }
        }
        // Gradient bar under title — bleu → violet → rose
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colors = listOf(
                            androidx.compose.ui.graphics.Color(0xFF2196F3),
                            androidx.compose.ui.graphics.Color(0xFFA855F7),
                            androidx.compose.ui.graphics.Color(0xFFE91E63)
                        )
                    )
                )
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun RadioCard(radio: Podcast, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceCard)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (!radio.logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = radio.logoUrl,
                contentDescription = radio.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Radio, radio.name, tint = AccentPrimary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = radio.name,
                    color = TextPrimary,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}
