package com.podmix.ui.screens.liveset

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.podmix.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDjScreen(
    onBack: () -> Unit,
    onDjAdded: (Int) -> Unit,
    viewModel: AddDjViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsState()
    val sets by viewModel.sets.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val selectedCount = viewModel.selectedCount()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Background,
            topBar = {
                TopAppBar(
                    title = { Text("Ajouter des live sets", color = TextPrimary, fontSize = 16.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
                )
            },
            bottomBar = {
                if (selectedCount > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Background)
                            .padding(12.dp)
                    ) {
                        Button(
                            onClick = { viewModel.importSelected { djId -> onDjAdded(djId) } },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                        ) {
                            Text(
                                "Importer ($selectedCount)",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // Search field
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.setQuery(it) },
                    label = { Text("Nom de l'artiste ou festival", color = TextSecondary) },
                    leadingIcon = {
                        if (isLoading)
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentPrimary)
                        else
                            Icon(Icons.Default.Search, null, tint = TextSecondary)
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentPrimary,
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = SurfaceCard,
                        focusedContainerColor = SurfaceCard,
                        unfocusedContainerColor = SurfaceCard
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Error message
                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }

                // Sort toggle
                if (sets.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SortChip(
                            label = "Most Viewed",
                            selected = sortMode == SortMode.MOST_VIEWED,
                            onClick = { viewModel.setSortMode(SortMode.MOST_VIEWED) }
                        )
                        SortChip(
                            label = "Most Recent",
                            selected = sortMode == SortMode.MOST_RECENT,
                            onClick = { viewModel.setSortMode(SortMode.MOST_RECENT) }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // Set list
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sets, key = { it.set.tracklistUrl }) { item ->
                        SetRow(
                            item = item,
                            onClick = { viewModel.toggleSelection(item.set.tracklistUrl) }
                        )
                    }
                    item { Spacer(Modifier.height(160.dp)) }
                }
            }
        }

        // Import progress overlay
        importProgress?.let { progress ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(SurfaceCard, RoundedCornerShape(12.dp))
                        .padding(24.dp)
                ) {
                    CircularProgressIndicator(color = AccentPrimary)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Import ${progress.current}/${progress.total}",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        progress.currentTitle,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) AccentPrimary else SurfaceCard,
                RoundedCornerShape(20.dp)
            )
            .border(1.dp, if (selected) AccentPrimary else SurfaceSecondary, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SetRow(item: ArtistSetUiItem, onClick: () -> Unit) {
    val alpha = if (item.isAlreadyImported) 0.4f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.isAlreadyImported, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isSelected || item.isAlreadyImported)
                Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = when {
                item.isAlreadyImported -> TextSecondary.copy(alpha = 0.4f)
                item.isSelected -> AccentPrimary
                else -> TextSecondary
            },
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.set.title,
                color = TextPrimary.copy(alpha = alpha),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.set.date.isNotBlank()) {
                    Text(item.set.date, color = TextSecondary.copy(alpha = alpha), fontSize = 11.sp)
                }
                if (item.set.viewCount > 0) {
                    Text("${item.set.viewCount} views", color = TextSecondary.copy(alpha = alpha), fontSize = 11.sp)
                }
                if (item.isAlreadyImported) {
                    Text("✓ importé", color = AccentPrimary.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }
        }
    }
    HorizontalDivider(color = SurfaceSecondary, thickness = 0.5.dp)
}
