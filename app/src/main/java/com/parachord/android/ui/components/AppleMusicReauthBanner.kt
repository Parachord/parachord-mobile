package com.parachord.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Surfaces a stale Apple Music Music-User-Token (`SyncEngine.appleMusicReauthRequired`).
 * The AM library API (the me/library endpoints) 401s when the MUT expires — catalog resolution
 * and streaming keep working (they don't use the MUT), so this is recoverable, not a
 * crash. Mirrors [SpotifyReauthBanner]'s visual language (info-purple, not error-red).
 *
 * Non-dismissible — resolves by reconnecting via the CTA (which re-mints the MUT and
 * clears the flag) or a process restart.
 */
@Composable
fun AppleMusicReauthBanner(
    onReconnect: () -> Unit,
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
                    text = "Apple Music session expired — tap to reconnect",
                    style = MaterialTheme.typography.titleMedium,
                    color = fg,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Reconnect to keep your Apple Music library and playlists syncing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = fg.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onReconnect()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(text = "Reconnect Apple Music", color = accent)
                }
            }
        }
    }
}
