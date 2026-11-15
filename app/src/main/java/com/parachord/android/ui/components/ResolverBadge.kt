package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.parachord.android.ui.theme.serviceColorOrNull

/**
 * Small colored pill showing the resolver source (Spotify, YouTube, etc.).
 * The service's manifest colour (shared `ServiceColors`) as text on a
 * 20% tint of itself, the desktop's resolver badge pattern.
 */
@Composable
fun ResolverBadge(
    resolver: String,
    modifier: Modifier = Modifier,
) {
    val color = serviceColorOrNull(resolver) ?: return
    val shape = RoundedCornerShape(4.dp)

    Text(
        text = resolver.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
