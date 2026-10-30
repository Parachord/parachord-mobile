package com.parachord.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.parachord.android.ui.theme.ParachordTheme

/**
 * Warns before Spotify's 6-month sign-in expiry (#352), in the last 7 days of
 * the grant. Low urgency, so it can be dismissed; it comes back at most once a
 * day. Same look as [SpotifyReauthBanner], which takes over if the grant does
 * expire.
 */
@Composable
fun SpotifyExpiryWarningBanner(
    message: String,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val isDark = ParachordTheme.isDark
    val bg = if (isDark) Color(0xFF2A1D4A) else Color(0xFFEFE8FD)
    val fg = if (isDark) Color(0xFFF3F4F6) else Color(0xFF111827)
    val accent = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)

    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleSmall,
                    color = fg,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onReconnect()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(text = "Reconnect Spotify", color = accent)
                }
            }
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDismiss()
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = fg.copy(alpha = 0.7f),
                )
            }
        }
    }
}
