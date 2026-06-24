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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.ui.screens.sync.PlaylistSyncChannelsViewModel
import com.parachord.android.ui.theme.ParachordTheme
import com.parachord.android.ui.theme.PurpleDark
import com.parachord.android.ui.theme.PurpleLight
import com.parachord.shared.sync.PlaylistSyncChannel
import org.koin.androidx.compose.koinViewModel

/**
 * Per-playlist "Sync" sheet — parity with iOS's `PlaylistSyncChannelsSheet`
 * (`PlaylistDetailView.swift`). Lets the user choose which services a playlist
 * syncs with; the per-playlist channel override is AUTHORITATIVE.
 *
 * A row is interactive when `connected && (available || enabled)`:
 *  - not connected   → "Connect in Settings to sync here" (toggle disabled)
 *  - connected, not available, not enabled → "Can't mirror this playlist here"
 *  - otherwise        → toggle works
 *
 * Toggle ON applies directly. Toggle OFF opens a keep/delete confirmation; if
 * the chosen "delete from <service> too" path is UNSUPPORTED (Apple Music), a
 * "Heads up" dialog tells the user to remove it manually.
 *
 * All channel logic lives in the shared [com.parachord.shared.sync.PlaylistSyncChannelManager]
 * via [PlaylistSyncChannelsViewModel] — this is pure UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSyncChannelsSheet(
    playlistId: String,
    playlistName: String,
    onDismiss: () -> Unit,
    viewModel: PlaylistSyncChannelsViewModel = koinViewModel(),
) {
    androidx.compose.runtime.LaunchedEffect(playlistId) { viewModel.load(playlistId) }

    val accent = if (ParachordTheme.isDark) PurpleDark else PurpleLight
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val pendingOff by viewModel.pendingOff.collectAsStateWithLifecycle()
    val headsUp by viewModel.headsUp.collectAsStateWithLifecycle()

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
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = "Sync",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ModalTextActive,
                )
                Spacer(modifier = Modifier.size(2.dp))
                Text(
                    text = "Choose which services “$playlistName” syncs with.",
                    fontSize = 13.sp,
                    color = ModalTextPrimary,
                )
            }

            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = accent)
                }
            } else {
                for (channel in channels) {
                    ChannelRow(
                        channel = channel,
                        accent = accent,
                        onEnable = { viewModel.enableChannel(channel.providerId) },
                        onRequestDisable = { viewModel.requestDisable(channel) },
                    )
                }
                Text(
                    text = "Turning a service off keeps the playlist in Parachord but " +
                        "stops syncing it there. Turning one on mirrors it to that " +
                        "service on the next sync.",
                    fontSize = 11.sp,
                    color = ModalTextSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }

    // Toggle OFF → keep on the service, or delete it there too?
    pendingOff?.let { ch ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDisable() },
            title = { Text("Stop syncing to ${ch.displayName}?") },
            text = {
                Text(
                    "Keep “$playlistName” on ${ch.displayName} and just stop " +
                        "syncing it, or delete it from ${ch.displayName} too?",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDisable(ch, deleteRemote = true) }) {
                    Text("Delete from ${ch.displayName} too", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmDisable(ch, deleteRemote = false) }) {
                    Text("Just stop syncing")
                }
            },
        )
    }

    // AM-can't-delete "Heads up"
    headsUp?.let { service ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissHeadsUp() },
            title = { Text("Heads up") },
            text = {
                Text(
                    "$service doesn't allow deletion via its API — remove " +
                        "“$playlistName” manually in the $service app.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissHeadsUp() }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun ChannelRow(
    channel: PlaylistSyncChannel,
    accent: Color,
    onEnable: () -> Unit,
    onRequestDisable: () -> Unit,
) {
    // Toggleable when connected AND (can mirror here OR already on — so you can
    // always turn an enabled channel off).
    val interactive = channel.connected && (channel.available || channel.enabled)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = channelColor(channel.providerId), shape = CircleShape),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.displayName,
                fontSize = 15.sp,
                color = if (interactive) ModalTextActive else ModalTextSecondary,
            )
            val subtitle = when {
                !channel.connected -> "Connect in Settings to sync here"
                !channel.available && !channel.enabled -> "Can't mirror this playlist here"
                else -> null
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = ModalTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = channel.enabled,
            onCheckedChange = { on -> if (on) onEnable() else onRequestDisable() },
            enabled = interactive,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent,
                checkedThumbColor = Color.White,
            ),
        )
    }
}

/** Service brand dots — matches iOS's `channelColor`. */
private fun channelColor(providerId: String): Color = when (providerId) {
    "spotify" -> Color(0xFF1DB954)
    "applemusic" -> Color(0xFFFA243C)
    "listenbrainz" -> Color(0xFFEB743B)
    else -> ModalTextSecondary
}
