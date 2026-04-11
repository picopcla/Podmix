package com.podmix.ui.screens.episode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podmix.domain.model.SourceResult
import com.podmix.domain.model.SourceStatus
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun TracklistDiagnosticBanner(
    results: List<SourceResult>,
    modifier: Modifier = Modifier
) {
    if (results.isEmpty()) return

    val isDetecting = results.any { it.status == SourceStatus.RUNNING || it.status == SourceStatus.PENDING }
    val hasSuccess = results.any { it.status == SourceStatus.SUCCESS }
    val allDone = results.none { it.status == SourceStatus.RUNNING || it.status == SourceStatus.PENDING }

    var expanded by remember { mutableStateOf(true) }

    // Auto-collapse 3s après succès
    LaunchedEffect(allDone, hasSuccess) {
        if (allDone && hasSuccess) {
            delay(3_000)
            expanded = false
        }
    }
    // Réouvrir si nouvelle détection démarre
    LaunchedEffect(isDetecting) {
        if (isDetecting) expanded = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceSecondary)
    ) {
        // Header cliquable
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Sources de tracklist",
                color = TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Réduire" else "Développer",
                tint = TextSecondary
            )
        }

        // Contenu collapsible
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                results.forEach { result ->
                    SourceResultRow(result)
                }
            }
        }
    }
}

@Composable
private fun SourceResultRow(result: SourceResult) {
    val icon = when (result.status) {
        SourceStatus.PENDING -> "○"
        SourceStatus.RUNNING -> "⏳"
        SourceStatus.SUCCESS -> "✅"
        SourceStatus.FAILED  -> "❌"
        SourceStatus.SKIPPED -> "⏭"
    }
    val detail = when (result.status) {
        SourceStatus.SUCCESS -> "${result.trackCount} tracks   +${result.elapsedMs}ms"
        SourceStatus.FAILED  -> "0 tracks   +${result.elapsedMs}ms"
        SourceStatus.SKIPPED -> if (result.reason.isNotBlank()) "skippé (${result.reason})" else "skippé"
        SourceStatus.RUNNING -> "en cours..."
        SourceStatus.PENDING -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 13.sp, modifier = Modifier.width(24.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = result.source,
            color = TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = detail,
            color = TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
