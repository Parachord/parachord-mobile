package com.parachord.android.ui.components

import com.parachord.shared.metadata.hasArtistPage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistContextMenu(
    artistName: String,
    imageUrl: String?,
    isInCollection: Boolean,
    onDismiss: () -> Unit,
    onPlayTopSongs: (() -> Unit)? = null,
    onQueueTopSongs: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    onToggleCollection: () -> Unit,
    onShare: (() -> Unit)? = null,
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
                    artworkUrl = imageUrl,
                    size = 48.dp,
                    cornerRadius = 24.dp,
                    elevation = 1.dp,
                    placeholderName = artistName,
                    modifier = Modifier.clip(CircleShape),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = artistName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = ModalTextActive,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 8.dp))

            if (onPlayTopSongs != null) {
                ContextMenuItem(
                    icon = Icons.Filled.PlayArrow,
                    label = "Play Top Songs",
                    onClick = { onPlayTopSongs(); onDismiss() },
                )
            }
            if (onQueueTopSongs != null) {
                ContextMenuItem(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = "Queue Top Songs",
                    onClick = { onQueueTopSongs(); onDismiss() },
                )
            }
            if (onGoToArtist != null && hasArtistPage(artistName)) {
                ContextMenuItem(
                    icon = Icons.Filled.Person,
                    label = "Go to Artist",
                    onClick = { onGoToArtist(); onDismiss() },
                )
            }

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))

            // Various Artists can't be added (#840); one added before can be removed.
            if (isInCollection || hasArtistPage(artistName)) {
                ContextMenuItem(
                    icon = if (isInCollection) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    label = if (isInCollection) "Remove from Collection" else "Add to Collection",
                    onClick = { onToggleCollection(); onDismiss() },
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
        }
    }
}
