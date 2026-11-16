package com.parachord.android.ui.screens.playlists

import com.parachord.android.ui.theme.FillAccent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.components.rememberDragHaptics
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPlaylistScreen(
    onBack: () -> Unit,
    onPlaylistDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditPlaylistViewModel = koinViewModel(),
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val tracks by viewModel.editableTracks.collectAsStateWithLifecycle()
    val editName by viewModel.editName.collectAsStateWithLifecycle()
    val hasChanges by viewModel.hasChanges.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Phase 6.5 — Decision D8: surface a toast when sync-aware delete
    // returns Unsupported (Apple Music's 401 on DELETE).
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Drag state
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 64.dp.toPx() }
    val dragHaptics = rememberDragHaptics()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "EDIT PLAYLIST",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                TextButton(
                    onClick = {
                        if (hasChanges) {
                            viewModel.save { onBack() }
                        } else {
                            onBack()
                        }
                    },
                ) {
                    Text(
                        text = if (hasChanges) "Save" else "Done",
                        color = if (hasChanges) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (hasChanges) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            },
            windowInsets = WindowInsets(0),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Playlist name field
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "Playlist Name",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    BasicTextField(
                        value = editName,
                        onValueChange = { viewModel.updateName(it) },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }

            // Delete playlist button
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .hapticClickable { showDeleteConfirm = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Delete Playlist",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }

            // Track count header
            item {
                Text(
                    text = "${tracks.size} track${if (tracks.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Track list with drag-to-reorder
            itemsIndexed(
                tracks,
                key = { _, track -> "${track.trackTitle}|${track.trackArtist}|${track.addedAt}" },
            ) { index, track ->
                val isDragging = draggingIndex == index
                val elevation by animateDpAsState(
                    targetValue = if (isDragging) 8.dp else 0.dp,
                    label = "dragElevation",
                )
                val bgColor by animateColorAsState(
                    targetValue = if (isDragging) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    label = "dragBg",
                )

                // Purple drop indicator above the row that's about to receive the drop
                val showDropIndicator = draggingIndex >= 0 && !isDragging &&
                    index == (draggingIndex + (dragOffsetY / rowHeightPx).roundToInt())
                        .coerceIn(0, tracks.size - 1) &&
                    index != draggingIndex
                if (showDropIndicator) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(FillAccent),
                    )
                }

                EditableTrackRow(
                    track = track,
                    index = index,
                    isDragging = isDragging,
                    dragOffsetY = if (isDragging) dragOffsetY else 0f,
                    elevation = elevation,
                    bgColor = bgColor,
                    onRemove = { viewModel.removeTrack(index) },
                    onDragStart = {
                        draggingIndex = index
                        dragOffsetY = 0f
                        dragHaptics.onDragStart()
                    },
                    onDrag = { deltaY ->
                        dragOffsetY += deltaY
                        // Calculate target index based on offset
                        val shift = (dragOffsetY / rowHeightPx).roundToInt()
                        val targetIndex = (draggingIndex + shift).coerceIn(0, tracks.size - 1)
                        if (targetIndex != draggingIndex) {
                            viewModel.moveTrack(draggingIndex, targetIndex)
                            draggingIndex = targetIndex
                            dragOffsetY -= shift * rowHeightPx
                            dragHaptics.onDragMove()
                        }
                    },
                    onDragEnd = {
                        draggingIndex = -1
                        dragOffsetY = 0f
                    },
                )
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        playlist?.let { pl ->
            com.parachord.android.ui.components.DeletePlaylistDialog(
                playlistId = pl.id,
                playlistName = pl.name,
                loadMirrors = viewModel::getDeletableMirrors,
                onConfirm = { fromProviders ->
                    showDeleteConfirm = false
                    viewModel.deletePlaylist(fromProviders) { onPlaylistDeleted() }
                },
                onDismiss = { showDeleteConfirm = false },
            )
        }
    }
}

@Composable
private fun EditableTrackRow(
    track: PlaylistTrackEntity,
    index: Int,
    isDragging: Boolean,
    dragOffsetY: Float,
    elevation: androidx.compose.ui.unit.Dp,
    bgColor: Color,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .then(
                if (isDragging) {
                    Modifier.offset { IntOffset(0, dragOffsetY.roundToInt()) }
                } else Modifier,
            )
            .shadow(elevation, RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Drag handle
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = "Drag to reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .size(36.dp)
                .padding(6.dp)
                .pointerInput(index) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, offset ->
                            change.consume()
                            onDrag(offset.y)
                        },
                    )
                },
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Album art
        AlbumArtCard(
            artworkUrl = track.trackArtworkUrl,
            size = 44.dp,
            cornerRadius = 4.dp,
            elevation = 1.dp,
            placeholderName = track.trackArtist.ifBlank { track.trackTitle },
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Title and artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.trackTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.trackArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Remove button
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove track",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
