package com.parachord.android.ui.components

import com.parachord.android.ui.theme.FillAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.parachord.android.data.db.entity.PlaylistEntity

private enum class PickerSort(val label: String) {
    RECENT("Recent"),
    NAME("Name"),
    MODIFIED("Modified"),
}

/**
 * Bottom sheet that shows available playlists for adding a track to.
 * Includes a search field and sort options to quickly find playlists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerSheet(
    playlists: List<PlaylistEntity>,
    onSelectPlaylist: (PlaylistEntity) -> Unit,
    onCreateNewPlaylist: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var searchQuery by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(PickerSort.RECENT) }
    val listState = rememberLazyListState()

    // Scroll to top when sort or search changes
    LaunchedEffect(sort, searchQuery) {
        listState.animateScrollToItem(0)
    }

    val filteredPlaylists by remember {
        derivedStateOf {
            val filtered = if (searchQuery.isBlank()) {
                playlists
            } else {
                playlists.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
            when (sort) {
                PickerSort.RECENT -> filtered.sortedByDescending { it.createdAt }
                PickerSort.NAME -> filtered.sortedBy { it.name.lowercase() }
                PickerSort.MODIFIED -> filtered.sortedByDescending {
                    if (it.lastModified > 0L) it.lastModified else it.updatedAt
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ModalBg,
        scrimColor = ModalScrim,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = "Add to Playlist",
                style = MaterialTheme.typography.titleMedium,
                color = ModalTextActive,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search playlists") },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
            )

            // Sort chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PickerSort.entries.forEach { option ->
                    TextButton(onClick = { sort = option }) {
                        Text(
                            text = option.label,
                            color = if (sort == option) {
                                FillAccent
                            } else {
                                ModalTextSecondary
                            },
                            style = if (sort == option) {
                                MaterialTheme.typography.labelLarge
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                        )
                    }
                }
            }

            HorizontalDivider(color = ModalDivider)

            // Create new playlist option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCreateNewPlaylist(); onDismiss() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Create new playlist",
                    tint = FillAccent,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "New Playlist\u2026",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FillAccent,
                )
            }

            HorizontalDivider(color = ModalDivider)

            LazyColumn(state = listState) {
                items(filteredPlaylists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPlaylist(playlist); onDismiss() }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AlbumArtCard(
                            artworkUrl = playlist.artworkUrl,
                            size = 40.dp,
                            cornerRadius = 4.dp,
                            elevation = 0.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = ModalTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${playlist.trackCount} tracks",
                                style = MaterialTheme.typography.bodySmall,
                                color = ModalTextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
