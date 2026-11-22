package com.parachord.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.theme.composeGradient
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.PageHero as Hero

/**
 * The page hero (desktop `page-hero.js`, collapsed): the page's gradient,
 * top-left to bottom-right, with its pattern tiled over it, the title in light
 * tracked capitals and, when the page has one, a line under it
 * ([Hero.line]). [navigation] is the leading button (the drawer's menu, or
 * back), [actions] the trailing ones. Every page with a hero draws this one;
 * the sizes, words and patterns are the shared [Hero]. iOS: `PCPageHero`.
 */
@Composable
fun PageHero(
    page: DesignTokens.Page,
    modifier: Modifier = Modifier,
    line: String? = null,
    title: String = page.title,
    navigation: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = (if (line != null) Hero.HEIGHT_WITH_LINE else Hero.HEIGHT).dp)
            .background(Brush.linearGradient(page.composeGradient))
            .clipToBounds(),
    ) {
        HeroPatternTiles(page, Modifier.matchParentSize())
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .matchParentSize()
                .padding(start = 4.dp, end = 4.dp),
        ) {
            navigation()
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    text = title.uppercase(),
                    fontSize = Hero.TITLE_SIZE.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = Hero.TITLE_TRACKING.em,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (line != null) {
                    Text(
                        text = line,
                        fontSize = Hero.LINE_SIZE.sp,
                        color = Color.White.copy(alpha = Hero.LINE_ALPHA),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}

/** The page's pattern ([Hero.pattern]), one tile repeated over the hero. */
@Composable
private fun HeroPatternTiles(page: DesignTokens.Page, modifier: Modifier) {
    val pattern = remember(page) { Hero.pattern(page) } ?: return
    val vector = remember(page) { pattern.tile.toImageVector("hero-${page.name}", Color.White) }
    val painter = rememberVectorPainter(vector)
    Canvas(modifier) {
        val tile = pattern.tile.width.dp.toPx()
        val tileSize = Size(tile, tile)
        var x = 0f
        while (x < size.width) {
            var y = 0f
            while (y < size.height) {
                translate(x, y) { with(painter) { draw(tileSize, alpha = pattern.opacity) } }
                y += tile
            }
            x += tile
        }
    }
}
