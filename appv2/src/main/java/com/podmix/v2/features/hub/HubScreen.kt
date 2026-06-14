package com.podmix.v2.features.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.podmix.v2.domain.model.BackendHealth

@Composable
fun HubRoute(
    onNavigate: (String) -> Unit,
    viewModel: HubViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    HubScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onNavigate = onNavigate
    )
}

@Composable
fun HubScreen(
    state: HubUiState,
    onRefresh: () -> Unit,
    onNavigate: (String) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { MiniPlayerShell() }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Podmix V2", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Console d'ecoute musicale locale-first connectee au VPS Podmix.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f)
                    )
                }
            }

            item {
                BackendHealthBanner(
                    health = state.backendHealth,
                    onRefresh = onRefresh
                )
            }

            item {
                Text(
                    "Hub",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.destinations.chunked(2).forEach { rowCards ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowCards.forEach { card ->
                                HubCard(
                                    card = card,
                                    modifier = Modifier.weight(1f),
                                    onNavigate = onNavigate
                                )
                            }
                            if (rowCards.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubCard(
    card: HubDestinationCard,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    Card(
        modifier = modifier.clickable { onNavigate(card.route) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(card.icon, contentDescription = null)
            Text(card.title, style = MaterialTheme.typography.titleMedium)
            Text(
                card.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun BackendHealthBanner(
    health: BackendHealth?,
    onRefresh: () -> Unit
) {
    val reachable = health?.isReachable == true
    val tint = when {
        reachable -> Color(0xFF26D07C)
        health == null -> Color(0xFFF0C24B)
        else -> Color(0xFFFF6B6B)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Backend public", style = MaterialTheme.typography.titleMedium)
                Box(
                    modifier = Modifier
                        .background(tint, shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = when {
                            health == null -> "checking"
                            health.isReachable -> "online"
                            else -> "offline"
                        },
                        color = Color.Black,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Text(
                health?.message ?: "Premier contact VPS en attente.",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                health?.endpoint ?: "https://podmix.mb4.fr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
            TextButton(onClick = onRefresh) {
                Text("Rafraichir")
            }
        }
    }
}

@Composable
private fun MiniPlayerShell() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Mini-player V2", style = MaterialTheme.typography.labelLarge)
            Text(
                "Le playback V2 consommera uniquement un PlayableMedia.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
        }
    }
}
