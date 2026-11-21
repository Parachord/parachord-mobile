package com.parachord.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.parachord.android.ui.theme.ParachordTheme
import com.parachord.shared.api.Announcement
import com.parachord.shared.home.BannerRole
import com.parachord.shared.home.bannerRole

/**
 * Achordion announcements banner (#127).
 *
 * Renders a single [Announcement] at the top of the home screen. Severity
 * picks a background tint:
 *
 *  - `info` / null / unknown → brand-purple (matches desktop's accent)
 *  - `success` → green
 *  - `warn` → amber
 *  - `error` → red
 *
 * `iconUrl` wins over `icon` if both are present (server-side validation
 * caps `icon` at 4 chars, but a remote PNG is a stronger signal than an
 * emoji fallback). The CTA is optional — when absent, the banner is a
 * read-only headline + body + dismiss button.
 *
 * Dark-mode tints are slightly desaturated so the foreground text stays
 * readable. Foreground color is fixed (`#F3F4F6` dark / `#111827` light)
 * because Material's `onSurface` doesn't track our custom severity tints.
 */
@Composable
fun AnnouncementBanner(
    announcement: Announcement,
    onDismiss: () -> Unit,
    onCtaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val isDark = ParachordTheme.isDark
    val bg = when (bannerRole(announcement.severity)) {
        BannerRole.ERROR -> if (isDark) Color(0xFF4A1212) else Color(0xFFFDECEC)
        BannerRole.WARNING -> if (isDark) Color(0xFF3F2E10) else Color(0xFFFFF4E0)
        BannerRole.SUCCESS -> if (isDark) Color(0xFF103D24) else Color(0xFFE8F7EF)
        BannerRole.INFO -> if (isDark) Color(0xFF2A1D4A) else Color(0xFFEFE8FD) // info / unknown → brand purple
    }
    val fg = if (isDark) Color(0xFFF3F4F6) else Color(0xFF111827)

    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(12.dp),
        ) {
            // Optional icon (iconUrl wins over emoji icon if both present).
            val iconUrl = announcement.iconUrl
            val icon = announcement.icon
            when {
                !iconUrl.isNullOrBlank() -> {
                    AsyncImage(
                        model = iconUrl,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                !icon.isNullOrBlank() -> {
                    Text(
                        text = icon,
                        fontSize = 20.sp,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = announcement.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = fg,
                )
                val body = announcement.body
                if (!body.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = fg.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                announcement.cta?.let { cta ->
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCtaClick(cta.url)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(text = cta.label, color = fg)
                    }
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
