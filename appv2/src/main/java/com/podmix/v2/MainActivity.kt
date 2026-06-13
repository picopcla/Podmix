package com.podmix.v2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.podmix.v2.navigation.PodmixV2NavGraph
import com.podmix.v2.ui.theme.PodmixV2Theme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PodmixV2Theme {
                PodmixV2NavGraph()
            }
        }
    }
}
