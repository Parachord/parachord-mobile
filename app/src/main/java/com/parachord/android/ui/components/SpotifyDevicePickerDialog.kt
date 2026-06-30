package com.parachord.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.parachord.shared.api.SpDevice
import com.parachord.android.playback.handlers.SpotifyPlaybackHandler

/**
 * Device picker dialog matching the desktop app's Spotify device picker.
 *
 * Shown when multiple Spotify devices are found but no preferred device is set
 * (or the preferred device is unavailable). Each device is shown as a clickable
 * row with a type-based emoji icon, device name, and volume level.
 */
@Composable
fun SpotifyDevicePickerDialog(
    devices: List<SpDevice>,
    onDeviceSelected: (SpDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Spotify Device") },
        text = {
            Column {
                Text(
                    text = "Where would you like to play?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        onClick = { onDeviceSelected(device) },
                    )
                }
                // #285: set expectations for the "This device" cold-launch —
                // Spotify foregrounds once to start, then the queue plays
                // silently via Connect (parity with the iOS picker footer).
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Choosing \"This device\" opens Spotify once to start playback — then come back to Parachord to keep listening. The rest of your queue plays without leaving the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DeviceRow(
    device: SpDevice,
    onClick: () -> Unit,
) {
    val isLocal = device.id == SpotifyPlaybackHandler.LOCAL_DEVICE_ID
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .hapticClickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = deviceTypeEmoji(device.type),
                modifier = Modifier.size(24.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isLocal) "This device" else device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isLocal) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    device.volumePercent?.let { vol ->
                        Text(
                            text = "Volume $vol%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (device.isActive) {
                Text(
                    text = "Active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Type-based emoji icon matching the desktop app's device picker. */
private fun deviceTypeEmoji(type: String): String = when (type) {
    "Computer" -> "\uD83D\uDCBB"       // 💻
    "Smartphone" -> "\uD83D\uDCF1"     // 📱
    "Speaker" -> "\uD83D\uDD0A"        // 🔊
    "TV" -> "\uD83D\uDCFA"             // 📺
    "CastVideo" -> "\uD83D\uDCFA"      // 📺
    "CastAudio" -> "\uD83D\uDD0A"      // 🔊
    "GameConsole" -> "\uD83C\uDFAE"    // 🎮
    "AVR" -> "\uD83C\uDFB5"            // 🎵
    "Tablet" -> "\uD83D\uDCF1"         // 📱
    else -> "\uD83C\uDFB6"             // 🎶
}
