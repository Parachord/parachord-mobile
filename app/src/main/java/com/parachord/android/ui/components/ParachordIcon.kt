package com.parachord.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.ui.icons.AppIcon
import com.parachord.shared.ui.icons.IconSpec
import com.parachord.shared.ui.icons.PathCommand
import com.parachord.shared.ui.icons.ServiceLogos

/**
 * An icon from desktop's icon set (the shared [AppIcon], generated from
 * desktop's `icons.js`). Sizes, as desktop: 16 in rows, chips and small
 * buttons; 20 in buttons and dialog titles; 24 by default. [contentDescription]
 * is null for an icon next to text; an icon-only control carries its label.
 */
@Composable
fun ParachordIcon(
    icon: AppIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    val vector = remember(icon, tint) { icon.spec.toImageVector(icon.name, tint) }
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}

/**
 * A service's logo from the shared [ServiceLogos] (desktop's
 * `SERVICE_LOGO_PATHS`, else its large `SERVICE_LOGOS`), drawn in [tint]
 * except where the logo has its own colours. Null for a service with no
 * vector logo (a bitmap one, or none).
 */
fun serviceLogoVector(serviceId: String, tint: Color = Color.White): ImageVector? =
    ServiceLogos.badgeOrLarge(serviceId)?.toImageVector("logo-$serviceId", tint)

/** An [IconSpec] as a Compose vector: tinted parts in [tint], fixed ones in their colour. */
fun IconSpec.toImageVector(name: String, tint: Color): ImageVector {
    val longest = maxOf(width, height)
    return ImageVector.Builder(
        name = name,
        defaultWidth = (24f * width / longest).dp,
        defaultHeight = (24f * height / longest).dp,
        viewportWidth = width,
        viewportHeight = height,
    ).apply {
        group(translationX = -minX, translationY = -minY) {
            parts.forEach { part ->
                val fillColor = part.fillColor?.let { tokenColor(it) } ?: tint
                val strokeColor = part.strokeColor?.let { tokenColor(it) } ?: tint
                path(
                    pathFillType = if (part.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = if (part.fill) SolidColor(fillColor) else null,
                    fillAlpha = part.alpha,
                    stroke = if (part.stroke) SolidColor(strokeColor) else null,
                    strokeAlpha = part.alpha,
                    strokeLineWidth = part.strokeWidth,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    part.commands.forEach { c ->
                        when (c) {
                            is PathCommand.MoveTo -> moveTo(c.x, c.y)
                            is PathCommand.LineTo -> lineTo(c.x, c.y)
                            is PathCommand.CubicTo -> curveTo(c.x1, c.y1, c.x2, c.y2, c.x, c.y)
                            PathCommand.Close -> close()
                        }
                    }
                }
            }
        }
    }.build()
}
