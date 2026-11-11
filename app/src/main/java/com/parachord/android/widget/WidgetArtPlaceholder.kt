package com.parachord.android.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import com.parachord.android.ui.components.placeholderGradient
import com.parachord.android.ui.components.placeholderInitials

/**
 * The app's art placeholder (`GradientPlaceholder`: a gradient picked from the
 * name, with its initials) drawn into a bitmap, for the widget. A RemoteViews
 * can't host Compose, so this draws the same palette and pattern with
 * `android.graphics`.
 *
 * Compose gradient offsets are absolute pixels, so the bitmap is drawn at the
 * size the widget shows it, [sizePx], with the same offsets: it then looks
 * like the in-app mini player's placeholder at that size.
 */
internal fun widgetArtPlaceholder(name: String, sizePx: Int, fontSizePx: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val gradient = placeholderGradient(name)
    val colors = gradient.colors
    val shader: Shader = when (gradient.pattern) {
        0 -> LinearGradient(0f, 0f, 1000f, 1000f, colors, null, Shader.TileMode.CLAMP)
        1 -> RadialGradient(500f, 500f, 700f, colors, null, Shader.TileMode.CLAMP)
        2 -> LinearGradient(0f, 1000f, 1000f, 0f, colors, null, Shader.TileMode.CLAMP)
        3 -> RadialGradient(300f, 300f, 800f, colors.reversedArray(), null, Shader.TileMode.CLAMP)
        4 -> LinearGradient(0f, 0f, 0f, 1000f, colors, null, Shader.TileMode.CLAMP)
        else -> LinearGradient(0f, 500f, 1000f, 500f, colors + colors.reversedArray(), null, Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), Paint().apply { this.shader = shader })

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb((0.85f * 255).toInt(), 255, 255, 255)
        textSize = fontSizePx
        textAlign = Paint.Align.CENTER
        typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, 600, false)
        } else {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
    val baseline = sizePx / 2f - (text.descent() + text.ascent()) / 2f
    canvas.drawText(placeholderInitials(name), sizePx / 2f, baseline, text)
    return bitmap
}
