package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared playlist long-press context menu. Used by every screen that
 * surfaces playlist cards (PlaylistsScreen, HomeScreen → Your Playlists,
 * HomeScreen → Weekly Jams/Exploration). Originally lived as a private
 * helper inside `PlaylistsScreen`; lifted here so the Home carousels
 * could share the exact same menu without duplicating ~90 lines per
 * call site.
 *
 * All action callbacks except [onPlayPlaylist] are nullable — pass null
 * to hide the corresponding row. This lets us reuse the menu for
 * ephemeral playlists (e.g. ListenBrainz weekly jams, where Delete
 * doesn't apply but Save does) without forking the component.
 *
 * The menu auto-dismisses after every action so callers don't need to
 * call `onDismiss` themselves; just wire the action callback to fire
 * the work and the sheet collapses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistContextMenu(
    playlistName: String,
    artworkUrl: String?,
    trackCount: Int,
    onDismiss: () -> Unit,
    onPlayPlaylist: () -> Unit,
    onShare: (() -> Unit)? = null,
    onOpenSync: (() -> Unit)? = null,
    onSavePlaylist: (() -> Unit)? = null,
    onDeletePlaylist: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ModalBg,
        scrimColor = Color.Black.copy(alpha = 0.4f),
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
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(ModalBg, ModalBgDarker)))
                .padding(bottom = 32.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtCard(
                    artworkUrl = artworkUrl,
                    size = 48.dp,
                    cornerRadius = 4.dp,
                    elevation = 1.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlistName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ModalTextActive,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$trackCount tracks",
                        fontSize = 13.sp,
                        color = ModalTextPrimary,
                    )
                }
            }

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 8.dp))

            ContextMenuItem(
                icon = Icons.Filled.PlayArrow,
                label = "Play Playlist",
                onClick = { onPlayPlaylist(); onDismiss() },
            )

            if (onShare != null) {
                HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                ContextMenuItem(
                    icon = Icons.Filled.Share,
                    label = "Share",
                    onClick = { onShare(); onDismiss() },
                )
            }

            if (onOpenSync != null) {
                HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                ContextMenuItem(
                    icon = Icons.Filled.Sync,
                    label = "Sync…",
                    onClick = { onOpenSync(); onDismiss() },
                )
            }

            if (onSavePlaylist != null) {
                HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                ContextMenuItem(
                    icon = Icons.Filled.BookmarkAdd,
                    label = "Save to Library",
                    onClick = { onSavePlaylist(); onDismiss() },
                )
            }

            if (onDeletePlaylist != null) {
                HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                ContextMenuItem(
                    icon = Icons.Filled.Delete,
                    label = "Delete Playlist",
                    onClick = { onDeletePlaylist(); onDismiss() },
                )
            }
        }
    }
}
