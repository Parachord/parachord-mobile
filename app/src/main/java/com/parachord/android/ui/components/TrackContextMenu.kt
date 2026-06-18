package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Always-dark modal/menu colors (matching desktop) ────────────────
// Desktop uses: linear-gradient(180deg, rgba(30,30,35,0.98), rgba(20,20,25,0.98))
// These are used by all modals, dialogs, and context menus for a consistent always-dark look.

/** Primary background for modals and context menus. */
val ModalBg = Color(0xFA1E1E23)              // ~rgba(30,30,35,0.98)
/** Darker gradient end for context menus. */
val ModalBgDarker = Color(0xFA141419)         // ~rgba(20,20,25,0.98)
/** Primary text in always-dark modals. */
val ModalTextPrimary = Color(0xB3FFFFFF)      // rgba(255,255,255,0.7)
/** Active/title text in always-dark modals. */
val ModalTextActive = Color(0xFFFFFFFF)       // white
/** Icon tint in always-dark modals. */
val ModalIconTint = Color(0x99FFFFFF)         // rgba(255,255,255,0.6)
/** Divider color in always-dark modals. */
val ModalDivider = Color(0x0FFFFFFF)          // rgba(255,255,255,0.06)
/** Secondary/muted text in always-dark modals. */
val ModalTextSecondary = Color(0x66FFFFFF)    // rgba(255,255,255,0.4)
/** Scrim overlay for modals. */
val ModalScrim = Color(0x66000000)            // rgba(0,0,0,0.4)

/**
 * Context data for a track long-press action.
 * Contains all info needed to build the context menu and dispatch actions.
 */
data class TrackContextInfo(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val duration: Long? = null,
    /** Whether this track is already in the user's collection. */
    val isInCollection: Boolean = false,
    /** If this track is in a playlist, the playlist ID (enables "Remove from Playlist"). */
    val playlistId: String? = null,
    /** Position in playlist (for removal). */
    val playlistPosition: Int? = null,
)

/**
 * Modal bottom sheet context menu for tracks — always-dark styling matching
 * the desktop app's right-click menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackContextMenu(
    track: TrackContextInfo,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onGoToArtist: () -> Unit,
    onGoToAlbum: (() -> Unit)? = null,
    onToggleCollection: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onEditMetadata: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ModalBg,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        dragHandle = {
            // Subtle drag handle matching the dark theme
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.2f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(ModalBg, ModalBgDarker),
                    ),
                )
                .padding(bottom = 32.dp),
        ) {
            // Track header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtCard(
                    artworkUrl = track.artworkUrl,
                    size = 48.dp,
                    cornerRadius = 4.dp,
                    elevation = 1.dp,
                    placeholderName = track.artist.ifBlank { track.title },
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ModalTextActive,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        fontSize = 13.sp,
                        color = ModalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 8.dp))

            // Favorite / heart toggle
            if (track.isInCollection) {
                ContextMenuItem(
                    icon = Icons.Filled.Favorite,
                    label = "Remove from Collection",
                    onClick = { onToggleCollection(); onDismiss() },
                )
            } else {
                ContextMenuItem(
                    icon = Icons.Filled.FavoriteBorder,
                    label = "Add to Collection",
                    onClick = { onToggleCollection(); onDismiss() },
                )
            }

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))

            // Menu items
            ContextMenuItem(
                icon = Icons.Filled.SkipNext,
                label = "Play Next",
                onClick = { onPlayNext(); onDismiss() },
            )
            ContextMenuItem(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = "Add to Queue",
                onClick = { onAddToQueue(); onDismiss() },
            )
            ContextMenuItem(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                label = "Add to Playlist\u2026",
                onClick = { onAddToPlaylist() },
            )

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))

            ContextMenuItem(
                icon = Icons.Filled.Person,
                label = "Go to Artist",
                onClick = { onGoToArtist(); onDismiss() },
            )
            if (onGoToAlbum != null) {
                ContextMenuItem(
                    icon = Icons.Filled.Album,
                    label = "Go to Album",
                    onClick = { onGoToAlbum(); onDismiss() },
                )
            }

            // Edit Metadata — local files only (gated by the host); editing a
            // streaming track's title/artist would break resolution.
            if (onEditMetadata != null) {
                ContextMenuItem(
                    icon = Icons.Filled.Edit,
                    label = "Edit Metadata",
                    onClick = { onEditMetadata() },
                )
            }

            if (onShare != null) {
                HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                ContextMenuItem(
                    icon = Icons.Filled.Share,
                    label = "Share",
                    onClick = { onShare(); onDismiss() },
                )
            }

            if (onRemoveFromPlaylist != null) {
                HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                ContextMenuItem(
                    icon = Icons.Filled.PlaylistRemove,
                    label = "Remove from Playlist",
                    onClick = { onRemoveFromPlaylist(); onDismiss() },
                )
            }
        }
    }
}

/**
 * A single row in an always-dark context menu.
 * Desktop: px-3 py-2, white text at 70%, icons at 60%.
 */
@Composable
fun ContextMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = ModalIconTint,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hapticClickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp),
            tint = tint,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = ModalTextPrimary,
        )
    }
}
