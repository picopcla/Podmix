package com.podmix

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.podmix.navigation.NavGraph
import com.podmix.navigation.Screen
import com.podmix.ui.components.BottomNavBar
import com.podmix.ui.components.FullScreenPlayer
import com.podmix.ui.components.MiniPlayer
import com.podmix.ui.theme.Background
import com.podmix.ui.theme.PodmIxTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Init Cast SDK from Activity context
        try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(this)
        } catch (_: Exception) {}
        setContent {
            PodmIxTheme {
                val navController = rememberNavController()
                var showFullPlayer by remember { mutableStateOf(false) }
                val playerViewModel: com.podmix.ui.screens.player.PlayerViewModel = androidx.hilt.navigation.compose.hiltViewModel()

                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Background,
                        bottomBar = {
                            val playerState by playerViewModel.playerState.collectAsState()
                            Column(modifier = Modifier.fillMaxWidth()) {
                                MiniPlayer(
                                    onExpand = { showFullPlayer = true },
                                    onNavigateToEpisode = {
                                        val ep = playerState.currentEpisode
                                        if (ep != null) {
                                            navController.navigate(
                                                Screen.EpisodeDetail.createRoute(ep.podcastId, ep.id)
                                            )
                                        }
                                    }
                                )
                                BottomNavBar(navController)
                            }
                        }
                    ) { padding ->
                        NavGraph(navController = navController)
                    }

                    if (showFullPlayer) {
                        FullScreenPlayer(onDismiss = { showFullPlayer = false })
                    }
                }
            }
        }
    }
}
