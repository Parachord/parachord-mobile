package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.em
import com.parachord.shared.ui.ArtistPattern
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.theme.ParachordTheme
import com.parachord.android.ui.theme.current
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.ui.CardPill
import com.parachord.shared.ui.CardSpec
import com.parachord.shared.ui.DesignTokens

/**
 * The card box every album and playlist card sits in (desktop's
 * `HOME_CARD_BOX`): the card colour, a small shadow, [CardSpec.RADIUS]
 * corners and [CardSpec.PADDING] inside. [width] is the art's; the card is
 * [CardSpec.cardWidth] of it, so the cover keeps its size.
 */
@Composable
fun ParachordCardBox(
    width: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(CardSpec.RADIUS.dp)
    Column(
        modifier = modifier
            .width(width + (CardSpec.PADDING * 2).dp)
            .shadow(2.dp, shape)
            .clip(shape)
            .background(ParachordTheme.colors.cardBackground)
            .then(if (onClick != null) Modifier.hapticCombinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier)
            .padding(CardSpec.PADDING.dp),
    ) { content() }
}

/** The art's side in a card given [width]: the width is the art's. */
fun cardArtSize(width: Dp): Dp = width

/**
 * A card's title, then its lines (desktop `album-card.js`: 13 / 500 primary,
 * 12 secondary, a third line tertiary). A blank line keeps its height so a row
 * of cards lines up.
 */
@Composable
fun CardLines(title: String, lines: List<String>, tertiaryFrom: Int = 1, lineMaxLines: Int = 1) {
    Spacer(Modifier.height(CardSpec.TEXT_GAP.dp))
    Text(
        text = title,
        fontSize = CardSpec.TITLE_SIZE.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        color = DesignTokens.textPrimary.current(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    lines.forEachIndexed { i, line ->
        Spacer(Modifier.height(CardSpec.LINE_GAP.dp))
        Text(
            text = line.ifBlank { " " },
            fontSize = CardSpec.LINE_SIZE.sp,
            lineHeight = 16.sp,
            color = if (i >= tertiaryFrom) DesignTokens.textTertiary.current() else DesignTokens.textSecondary.current(),
            maxLines = if (i == 0) lineMaxLines else 1,
            // A wrapping first line keeps its full height, so a row of cards lines up.
            minLines = if (i == 0) lineMaxLines else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A pill at the art's top right (the release type, "Coming …"): white text on its colour. */
@Composable
fun CardPillBadge(pill: CardPill, modifier: Modifier = Modifier) {
    Text(
        text = pill.text,
        fontSize = CardSpec.PILL_SIZE.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .padding(CardSpec.BADGE_INSET.dp)
            .clip(RoundedCornerShape(DesignTokens.RADIUS_BADGE.dp))
            .background(tokenColor(pill.color, pill.alpha))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** History's and a friend's rank at the art's top left (desktop `AlbumRankBadge`). */
@Composable
fun CardRankBadge(rank: Int, modifier: Modifier = Modifier) {
    Text(
        text = CardSpec.rankLabel(rank),
        fontSize = CardSpec.LINE_SIZE.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White,
        modifier = modifier
            .padding(CardSpec.BADGE_INSET.dp)
            .clip(RoundedCornerShape(DesignTokens.RADIUS_BADGE.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The album card (desktop `album-card.js`), used by every row and grid of
 * albums: the card box, the cover at [CardSpec.ART_RADIUS] with an optional
 * [pill] top right, [rank] top left, the play disc bottom right and
 * [artBadge] (a reason badge) over it, then the title, artist and [subtitle].
 */
@Composable
fun ParachordAlbumCard(
    title: String,
    artist: String?,
    artworkUrl: String?,
    /** The cover's width (the card adds its padding); null fills the space it's given (a grid cell). */
    width: Dp?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    subtitle: String? = null,
    pill: CardPill? = null,
    rank: Int? = null,
    artBadge: (@Composable BoxScope.() -> Unit)? = null,
) {
    if (width == null) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            ParachordAlbumCard(title, artist, artworkUrl, maxWidth - (CardSpec.PADDING * 2).dp, onOpen, Modifier, onLongPress, onPlay, subtitle, pill, rank, artBadge)
        }
        return
    }
    ParachordCardBox(width, modifier, onClick = onOpen, onLongClick = onLongPress) {
        Box {
            AlbumArtCard(
                artworkUrl = artworkUrl,
                size = cardArtSize(width),
                cornerRadius = CardSpec.ART_RADIUS.dp,
                elevation = 0.dp,
                placeholderName = title,
                contentDescription = title,
                artist = artist,
                album = title,
            )
            pill?.let { CardPillBadge(it, Modifier.align(Alignment.TopEnd)) }
            rank?.let { CardRankBadge(it, Modifier.align(Alignment.TopStart)) }
            artBadge?.invoke(this)
            onPlay?.let { ArtPlayButton(title, it, Modifier.align(Alignment.BottomEnd).padding(ArtPlayInset)) }
        }
        CardLines(title, listOfNotNull(artist, subtitle))
    }
}

/**
 * The playlist card (desktop `playlist-card.js`): the card box, the art drawn
 * by [art] at the art's size, the "Δ N" [badge] top left, [cornerBadge] top
 * right (Hosted), the play disc, then the title and [lines] (the first may
 * wrap to two lines).
 */
@Composable
fun ParachordPlaylistCard(
    title: String,
    lines: List<String>,
    width: Dp,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    badge: String? = null,
    cornerBadge: (@Composable BoxScope.() -> Unit)? = null,
    art: @Composable (Dp) -> Unit,
) {
    ParachordCardBox(width, modifier, onClick = onOpen, onLongClick = onLongPress) {
        val side = cardArtSize(width)
        Box(Modifier.size(side).clip(RoundedCornerShape(CardSpec.ART_RADIUS.dp))) {
            art(side)
            badge?.let {
                Text(
                    text = it,
                    fontSize = CardSpec.PILL_SIZE.sp,
                    fontWeight = FontWeight.Bold,
                    color = DesignTokens.textPrimary.current(),
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(CardSpec.BADGE_INSET.dp)
                        .clip(CircleShape)
                        .background(DesignTokens.bgPrimary.current())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            cornerBadge?.invoke(this)
            onPlay?.let { ArtPlayButton(title, it, Modifier.align(Alignment.BottomEnd).padding(ArtPlayInset)) }
        }
        CardLines(title, lines, tertiaryFrom = Int.MAX_VALUE, lineMaxLines = 2)
    }
}

/** A card's shimmer stand-in (desktop `home-skeletons.js`): the same box, art and [lines] text bars. */
@Composable
fun CardSkeleton(width: Dp?, modifier: Modifier = Modifier, lines: Int = 2) {
    if (width == null) {
        // A grid cell: the same card as one that fills it (ParachordAlbumCard's width null).
        BoxWithConstraints(modifier.fillMaxWidth()) { CardSkeleton(maxWidth - (CardSpec.PADDING * 2).dp, Modifier, lines) }
        return
    }
    val brush = shimmerBrush()
    ParachordCardBox(width, modifier) {
        Box(Modifier.size(cardArtSize(width)).clip(RoundedCornerShape(CardSpec.ART_RADIUS.dp)).background(brush))
        Spacer(Modifier.height(CardSpec.TEXT_GAP.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(brush))
        repeat(lines) {
            Spacer(Modifier.height(CardSpec.LINE_GAP.dp + 2.dp))
            Box(Modifier.fillMaxWidth(0.55f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(brush))
        }
    }
}

/** An artist's stand-in art (desktop `lib/artist-pattern.js`): the name's gradient and initials at 40%. */
@Composable
fun ArtistPatternArt(name: String, modifier: Modifier = Modifier, initialsSize: androidx.compose.ui.unit.TextUnit = 48.sp) {
    val pattern = androidx.compose.runtime.remember(name) { ArtistPattern.of(name) }
    Box(
        modifier = modifier.drawBehind {
            val stops = pattern.gradient.stops.map { it.at to tokenColor(it.color) }.toTypedArray()
            val brush = when (val g = pattern.gradient) {
                is ArtistPattern.Gradient.Linear -> {
                    val (s, e) = g.endpoints(size.width, size.height)
                    Brush.linearGradient(*stops, start = Offset(s.first, s.second), end = Offset(e.first, e.second))
                }
                is ArtistPattern.Gradient.Radial -> Brush.radialGradient(
                    *stops,
                    center = Offset(g.centerX * size.width, g.centerY * size.height),
                    radius = g.radius(size.width, size.height).coerceAtLeast(1f),
                )
            }
            drawRect(brush)
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = pattern.initials,
            fontSize = initialsSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.05.em,
            color = tokenColor(pattern.textColor).copy(alpha = ArtistPattern.INITIALS_ALPHA),
            maxLines = 1,
        )
    }
}

/**
 * The artist card (desktop `artist-card.js`), used by every artist grid and
 * row: a rounded card with the card colour and shadow, the square photo
 * full-bleed (the name's pattern and initials when there's none or it won't
 * load), [rank] at its top left, then the name (14 / 500) and [subtitle]
 * (12 tertiary). [width] null fills a grid cell. The artist's top songs are
 * in its long-press menu.
 */
@Composable
fun ParachordArtistCard(
    name: String,
    imageUrl: String?,
    width: Dp?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    subtitle: String? = null,
    rank: Int? = null,
) {
    val shape = RoundedCornerShape(CardSpec.ARTIST_RADIUS.dp)
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .shadow(2.dp, shape)
            .clip(shape)
            .background(ParachordTheme.colors.cardBackground)
            .hapticCombinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(DesignTokens.placeholderBg.current())) {
            var failed by androidx.compose.runtime.remember(imageUrl) { androidx.compose.runtime.mutableStateOf(false) }
            if (imageUrl.isNullOrBlank() || failed) {
                ArtistPatternArt(name, Modifier.fillMaxSize())
            } else {
                coil.compose.AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            rank?.let { CardRankBadge(it, Modifier.align(Alignment.TopStart)) }
        }
        Column(Modifier.padding(CardSpec.ARTIST_TEXT_PADDING.dp)) {
            Text(
                text = name,
                fontSize = CardSpec.ARTIST_NAME_SIZE.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = DesignTokens.textPrimary.current(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Spacer(Modifier.height(CardSpec.LINE_GAP.dp))
                Text(
                    text = it,
                    fontSize = CardSpec.LINE_SIZE.sp,
                    lineHeight = 16.sp,
                    color = DesignTokens.textTertiary.current(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** An artist card's stand-in (desktop `ArtistCardSkeleton`): the same box, the photo and [lines] text bars. */
@Composable
fun ArtistCardSkeleton(width: Dp?, modifier: Modifier = Modifier, lines: Int = 1) {
    val brush = shimmerBrush()
    val shape = RoundedCornerShape(CardSpec.ARTIST_RADIUS.dp)
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .shadow(2.dp, shape)
            .clip(shape)
            .background(ParachordTheme.colors.cardBackground),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(brush))
        Column(Modifier.padding(CardSpec.ARTIST_TEXT_PADDING.dp)) {
            Box(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            if (lines > 1) {
                Spacer(Modifier.height(CardSpec.LINE_GAP.dp + 2.dp))
                Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            }
        }
    }
}
