package com.parachord.android.ui.screens.home

import com.parachord.android.ui.components.layoutWidthDp
import com.parachord.shared.ui.LayoutRules
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ArtPlayButton
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.theme.FillAccent
import com.parachord.android.ui.theme.current
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.home.HOME_JUMP_TILES
import com.parachord.shared.home.homeSectionVisibility
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.home.HomeState
import com.parachord.shared.home.JumpTile
import com.parachord.shared.home.JumpTileArt
import com.parachord.shared.home.jumpTileKey
import com.parachord.shared.home.jumpTileTitle
import com.parachord.shared.ui.ArtPlayButtonSpec
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.icons.AppIcon

/** Desktop's `HOME_TILE_HEIGHT`: the tile, and its square art. */
private val TileHeight = 72.dp
/** Desktop's `HOME_JUMP_GAP`. */
private val TileGap = 12.dp
private val OnFill = tokenColor(DesignTokens.ON_ACCENT)

/**
 * "Jump back in" (desktop `home-sections.js` `JumpBackIn`): up to four tiles
 * in a 2x2 grid, each with its art on the left and a round Play over the art.
 * The tile opens its item; Play plays it. With no tiles, a new user (no
 * collection, no playlists) gets the "Build your collection" card instead;
 * anyone else gets no section.
 */
@Composable
fun HomeJumpBackIn(
    state: HomeState,
    art: Map<String, JumpTileArt>,
    onOpenTile: (JumpTile) -> Unit,
    onPlayTile: (JumpTile) -> Unit,
    onTileShown: (JumpTile) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tiles = state.tiles.take(HOME_JUMP_TILES)
    if (!homeSectionVisibility(state).showJump) return
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = HomeCopy.JUMP_BACK_IN)
        Column(
            modifier = Modifier.padding(horizontal = DesignTokens.SCREEN_GUTTER.dp),
            verticalArrangement = Arrangement.spacedBy(TileGap),
        ) {
            if (tiles.isEmpty()) {
                ImportCard(onImport)
            } else {
                // Two by two on a phone, one line of four on a wide screen (shared jumpBackInColumns).
                val columns = LayoutRules.jumpBackInColumns(layoutWidthDp())
                tiles.chunked(columns).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                        pair.forEach { tile ->
                            val tileKey = jumpTileKey(tile)
                            key(tileKey) {
                                JumpTileView(
                                    tile = tile,
                                    art = art[tileKey] ?: JumpTileArt(),
                                    onOpen = { onOpenTile(tile) },
                                    onPlay = { onPlayTile(tile) },
                                    onShown = { onTileShown(tile) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        // A short last line keeps its tiles' width.
                        repeat(columns - pair.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpTileView(
    tile: JumpTile,
    art: JumpTileArt,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = jumpTileTitle(tile)
    if (art.isEmpty) LaunchedEffect(jumpTileKey(tile)) { onShown() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(TileHeight)
            .clip(RoundedCornerShape(DesignTokens.RADIUS_CARD.dp))
            .background(DesignTokens.bgSecondary.current())
            .hapticClickable(onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = HomeCopy.openLabel(title) },
    ) {
        Box(modifier = Modifier.size(TileHeight), contentAlignment = Alignment.Center) {
            TileArt(tile, art, TileHeight)
            ArtPlayButton(title = title, onClick = onPlay, size = ArtPlayButtonSpec.TILE_SIZE.dp)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp),
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = DesignTokens.textPrimary.current(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tile.subtitle,
                fontSize = 12.sp,
                color = DesignTokens.textSecondary.current(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One cover, a 2x2 of covers, or the placeholder. */
@Composable
private fun TileArt(tile: JumpTile, art: JumpTileArt, size: Dp) {
    if (art.mosaic.size >= 4) {
        Column(Modifier.size(size)) {
            art.mosaic.take(4).chunked(2).forEach { row ->
                Row(Modifier.weight(1f)) {
                    row.forEach { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                        )
                    }
                }
            }
        }
        return
    }
    val album = tile as? JumpTile.Album
    AlbumArtCard(
        artworkUrl = art.imageUrl ?: art.mosaic.firstOrNull(),
        size = size,
        cornerRadius = 0.dp,
        elevation = 0.dp,
        contentDescription = null,
        artist = album?.album?.artist,
        album = album?.album?.album,
    )
}

/** "Build your collection" (a new user: no collection, no playlists). */
@Composable
private fun ImportCard(onImport: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(DesignTokens.RADIUS_PANEL.dp),
        color = DesignTokens.bgSecondary.current(),
        border = BorderStroke(1.dp, DesignTokens.borderDefault.current()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = HomeCopy.IMPORT_TITLE,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = DesignTokens.textPrimary.current(),
            )
            Spacer(Modifier.size(4.dp))
            Text(text = HomeCopy.IMPORT_BODY, fontSize = 13.sp, color = DesignTokens.textSecondary.current())
            Spacer(Modifier.size(12.dp))
            Button(
                onClick = onImport,
                shape = RoundedCornerShape(DesignTokens.RADIUS_BUTTON.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FillAccent, contentColor = OnFill),
            ) {
                Text(text = HomeCopy.IMPORT_BUTTON, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
