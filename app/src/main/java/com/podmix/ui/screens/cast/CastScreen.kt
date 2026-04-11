package com.podmix.ui.screens.cast

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.podmix.ui.theme.AccentPrimary
import com.podmix.ui.theme.Background
import com.podmix.ui.theme.SurfaceCard
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastScreen(viewModel: CastViewModel = hiltViewModel()) {
    val playerState by viewModel.playerState.collectAsState()
    val connectedDevice by viewModel.connectedDeviceName.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    // Init CastContext from Activity context
    LaunchedEffect(activity) {
        activity?.let { viewModel.initCast(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        TopAppBar(
            title = { Text("Cast", color = TextPrimary, fontSize = 18.sp) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
        )

        // Now playing info
        playerState.currentEpisode?.let { ep ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceCard)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("En cours de lecture", color = TextSecondary, fontSize = 11.sp)
                    Text(ep.title, color = TextPrimary, fontSize = 13.sp, maxLines = 1)
                    playerState.currentPodcast?.let {
                        Text(it.name, color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        } ?: Text(
            "Lance un épisode d'abord",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        Spacer(Modifier.height(24.dp))

        // Cast button — opens native dialog
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceCard)
                .clickable { activity?.let { viewModel.openCastDialog(it) } }
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (connectedDevice != null) Icons.Default.CastConnected else Icons.Default.Cast,
                contentDescription = "Cast",
                tint = if (connectedDevice != null) AccentPrimary else TextSecondary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (connectedDevice != null) "Connecté à $connectedDevice" else "Diffuser sur un appareil",
                    color = TextPrimary,
                    fontSize = 15.sp
                )
                Text(
                    if (connectedDevice != null) "Tap pour déconnecter"
                    else if (playerState.currentEpisode != null) "Chromecast, TV, enceinte connectée"
                    else "Lance un épisode d'abord",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(160.dp))
    }
}
