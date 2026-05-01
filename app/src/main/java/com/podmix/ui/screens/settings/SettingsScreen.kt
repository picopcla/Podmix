package com.podmix.ui.screens.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.Background
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val maxPodcast  by viewModel.maxPodcastEpisodes.collectAsState()
    val maxLiveset  by viewModel.maxLivesetEpisodes.collectAsState()
    val maxEmission by viewModel.maxEmissionEpisodes.collectAsState()

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Paramètres", color = TextPrimary, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                "Limite d'épisodes par source",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            EpisodeLimitRow(
                label = "Podcasts",
                value = maxPodcast,
                defaultMax = 50f,
                extendedMax = 200f,
                onValueChange = { viewModel.setMaxPodcast(it) }
            )

            EpisodeLimitRow(
                label = "Live sets / DJs",
                value = maxLiveset,
                defaultMax = 50f,
                extendedMax = 500f,
                onValueChange = { viewModel.setMaxLiveset(it) }
            )

            EpisodeLimitRow(
                label = "Émissions",
                value = maxEmission,
                defaultMax = 50f,
                extendedMax = 200f,
                onValueChange = { viewModel.setMaxEmission(it) }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "Les épisodes écoutés à 98%+ les plus anciens sont supprimés automatiquement\n" +
                "pour maintenir ces limites. Les favoris et téléchargements sont protégés.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
private fun EpisodeLimitRow(
    label: String,
    value: Int,
    defaultMax: Float,
    extendedMax: Float,
    onValueChange: (Int) -> Unit
) {
    var extended by remember { mutableStateOf(value > defaultMax.toInt()) }
    val max = if (extended) extendedMax else defaultMax
    val clampedValue = value.toFloat().coerceIn(5f, max)

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            Text(
                "$value épisodes",
                color = AccentPrimary,
                fontSize = 13.sp
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { extended = !extended },
                modifier = Modifier.size(28.dp)
            ) {
                Text(
                    text = if (extended) "−" else "+",
                    color = if (extended) TextSecondary else AccentPrimary,
                    fontSize = 16.sp
                )
            }
        }
        Slider(
            value = clampedValue,
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 5f..max,
            colors = SliderDefaults.colors(
                thumbColor = AccentPrimary,
                activeTrackColor = AccentPrimary,
                inactiveTrackColor = SurfaceSecondary
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (extended) {
            Text(
                text = "Plage étendue jusqu'à ${extendedMax.toInt()}",
                color = TextSecondary,
                fontSize = 10.sp
            )
        }
    }
}
