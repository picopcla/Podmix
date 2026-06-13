package com.podmix.v2.features.hub

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.podmix.v2.domain.model.BackendHealth
import org.junit.Rule
import org.junit.Test

class HubScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displaysBackendHealthMessage() {
        composeRule.setContent {
            HubScreen(
                state = HubUiState(
                    backendHealth = BackendHealth(
                        status = "ok",
                        endpoint = "https://podmix.mb4.fr",
                        checkedAtEpochMillis = 10L,
                        isReachable = true,
                        message = "Backend reachable"
                    )
                ),
                onRefresh = {},
                onNavigate = {}
            )
        }

        composeRule.onNodeWithText("Backend reachable").assertIsDisplayed()
        composeRule.onNodeWithText("Podcast").assertIsDisplayed()
    }
}
