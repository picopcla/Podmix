package com.podmix.ui.screens.liveset

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
    val selectedCount by viewModel.selectedCount.collectAsState()

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
                actions = {
                    if (sets.isNotEmpty()) {
                        TextButton(
                            onClick = { viewModel.importSelected { djId -> onDjAdded(djId) } },
                            enabled = selectedCount > 0
                        ) {
                            Text(
                                if (selectedCount > 0) "Importer ($selectedCount)" else "Importer",
                                color = if (selectedCount > 0) AccentPrimary else TextSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        bottomBar = {}
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

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

            errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp)
            }

            if (sets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text(
                        text = if (selectedCount == sets.size) "Tout désélect." else "Tout sélect.",
                        color = AccentPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { viewModel.toggleSelectAll() }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sets, key = { it.set.id }) { item ->
                    SetRow(
                        item = item,
                        onClick = { if (!item.isAlreadyImported) viewModel.toggleSelection(item.set.id) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SetRow(item: DiscoveredSetUiItem, onClick: () -> Unit) {
    val alpha = if (item.isAlreadyImported) 0.38f else 1f
    val scColor = Color(0xFFFF5500)
    val tlColor = AccentPrimary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.isAlreadyImported, onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checkbox
        Icon(
            imageVector = if (item.isSelected || item.isAlreadyImported)
                Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = when {
                item.isAlreadyImported -> TextSecondary.copy(alpha = 0.35f)
                item.isSelected -> AccentPrimary
                else -> TextSecondary
            },
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(7.dp))

        // Titre — marquee scrolling si le texte dépasse
        Text(
            text = item.set.title,
            color = TextPrimary.copy(alpha = alpha),
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .basicMarquee(iterations = Int.MAX_VALUE)
        )

        Spacer(Modifier.width(6.dp))

        // Métadonnées à droite : date + source
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val dateShort = formatDateShort(item.set.date)
            if (dateShort.isNotBlank()) {
                Text(
                    text = dateShort,
                    color = TextSecondary.copy(alpha = alpha),
                    fontSize = 9.sp
                )
            }
            Text(
                text = item.sourceLabel,
                color = when (item.sourceLabel) {
                    "SC+TL", "SC" -> scColor.copy(alpha = alpha)
                    else -> tlColor.copy(alpha = alpha)
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
    HorizontalDivider(color = SurfaceSecondary.copy(alpha = 0.4f), thickness = 0.5.dp)
}

/** Formate une date en "MMM YY" (ex: "Mar 23") pour l'affichage compact. */
private fun formatDateShort(date: String): String {
    if (date.isBlank()) return ""
    val formats = listOf("yyyy-MM-dd", "dd MMM yyyy", "MMM dd, yyyy", "dd.MM.yyyy",
        "MM/dd/yyyy", "dd/MM/yyyy", "MMMM dd, yyyy", "dd MMMM yyyy")
    for (fmt in formats) {
        try {
            val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.ENGLISH)
            sdf.isLenient = false
            val parsed = sdf.parse(date) ?: continue
            return java.text.SimpleDateFormat("MMM yy", java.util.Locale.ENGLISH).format(parsed)
        } catch (_: Exception) { }
    }
    // Fallback : retourner les 7 premiers chars bruts si ça ressemble à une date
    return if (date.length >= 7 && date.any { it.isDigit() }) date.take(7) else ""
}
