package com.parachord.android.ui.screens.discover

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.HexagonShape
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.components.hapticCombinedClickable
import com.parachord.android.ui.components.shimmerBrush
import com.parachord.android.ui.theme.FillAccent
import com.parachord.android.ui.theme.ParachordTheme
import com.parachord.android.ui.theme.serviceColor
import com.parachord.shared.recommendations.ListenerItem
import com.parachord.shared.recommendations.RecommendationCopy
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.WeeklyInfo
import com.parachord.shared.ui.icons.AppIcon

/** A card's width in a scrolling row. */
internal val RecCardWidth = 140.dp

/** The narrowest a card gets in an open grid before the grid drops a column. */
internal val RecCardMinGridWidth = 104.dp
private val PlayButton = 32.dp

/** The listener hexagon keeps its 120:140 share of the card. */
private fun listenerAvatar(width: Dp): Dp = width * (120f / 140f)

/** Text on a [FillAccent] or service-colour fill is always white (design system). */
private val OnFill = Color.White

@Composable
private fun CardText(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    tertiary: Boolean = false,
    maxLines: Int = 1,
    center: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = if (primary) 13.sp else 12.sp,
        lineHeight = if (primary) 16.sp else 16.sp,
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            primary -> MaterialTheme.colorScheme.onSurface
            tertiary -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (center) TextAlign.Center else null,
    )
}

@Composable
private fun PlayDisc(onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(PlayButton)
            .clip(CircleShape)
            .background(FillAccent)
            .hapticCombinedClickable(onClick = onPlay),
        contentAlignment = Alignment.Center,
    ) {
        ParachordIcon(AppIcon.Play, contentDescription = "Play", size = 16.dp, tint = OnFill)
    }
}

/** A release: cover, title, artist, year and an optional "Coming …" pill (top right, as desktop). */
@Composable
fun RecAlbumCard(
    item: RecommendationItem,
    year: String,
    comingLabel: String?,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** The card's width; the cover (or hexagon) scales with it. 140 dp in a scroller, wider in an open grid. */
    width: Dp = RecCardWidth,
) {
    Column(
        modifier = modifier
            .width(width)
            .hapticCombinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Box {
            AlbumArtCard(
                artworkUrl = item.imageUrl,
                size = width,
                cornerRadius = 6.dp,
                placeholderName = item.name,
                contentDescription = item.name,
                artist = item.artist,
                album = item.name,
            )
            if (comingLabel != null) {
                Text(
                    text = comingLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OnFill,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(FillAccent)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            PlayDisc(onPlay, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        CardText(item.name, primary = true)
        CardText(item.artist.orEmpty().ifBlank { " " })
        CardText(year.ifBlank { " " }, tertiary = true)
    }
}

/** A recommended artist: a round-cornered photo and the name. */
@Composable
fun RecArtistCard(
    item: RecommendationItem,
    imageUrl: String?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** The card's width; the cover (or hexagon) scales with it. 140 dp in a scroller, wider in an open grid. */
    width: Dp = RecCardWidth,
) {
    Column(
        modifier = modifier
            .width(width)
            .hapticCombinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Box(
            modifier = Modifier
                .size(width)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ParachordIcon(
                    AppIcon.User, contentDescription = null, size = 40.dp,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        CardText(item.name, primary = true)
    }
}

/** A service playlist: image, name and the service it came from. */
@Composable
fun RecPlaylistCard(
    item: RecommendationItem,
    sourceName: String,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** The card's width; the cover (or hexagon) scales with it. 140 dp in a scroller, wider in an open grid. */
    width: Dp = RecCardWidth,
) {
    Column(
        modifier = modifier
            .width(width)
            .hapticCombinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        AlbumArtCard(
            artworkUrl = item.imageUrl,
            size = width,
            cornerRadius = 6.dp,
            placeholderName = item.name,
            contentDescription = item.name,
        )
        Spacer(Modifier.height(8.dp))
        CardText(item.name, primary = true)
        CardText(sourceName.ifBlank { " " })
    }
}

/** A weekly playlist: a 2×2 mosaic of [covers] (one fills it), label and week. */
@Composable
fun RecWeeklyCard(
    item: RecommendationItem,
    covers: List<String>,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    /** The card's width; the cover (or hexagon) scales with it. 140 dp in a scroller, wider in an open grid. */
    width: Dp = RecCardWidth,
) {
    val weekly = item.weekly
    Column(
        modifier = modifier
            .width(width)
            .hapticCombinedClickable(onClick = onOpen),
    ) {
        Box(
            modifier = Modifier
                .size(width)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            when (covers.size) {
                0 -> ParachordIcon(
                    AppIcon.Music, contentDescription = null, size = 40.dp,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.align(Alignment.Center),
                )
                1 -> Cover(covers[0], Modifier.fillMaxSize())
                else -> Column(Modifier.fillMaxSize()) {
                    // 2 or 3 covers fill the grid by repeating in order.
                    for (row in 0..1) {
                        Row(Modifier.weight(1f)) {
                            for (col in 0..1) {
                                Cover(covers[(row * 2 + col) % covers.size], Modifier.weight(1f).fillMaxSize())
                            }
                        }
                    }
                }
            }
            PlayDisc(onPlay, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        CardText(weekly?.label ?: item.name, primary = true)
        CardText(weekly?.weekLabel.orEmpty().ifBlank { " " })
    }
}

@Composable
private fun Cover(url: String, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/** A listener with a similar taste: hexagon avatar, play disc, similarity pill, name, service, top artists. */
@Composable
fun ListenerCard(
    item: ListenerItem,
    serviceName: String,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** The card's width; the cover (or hexagon) scales with it. 140 dp in a scroller, wider in an open grid. */
    width: Dp = RecCardWidth,
) {
    val avatar = listenerAvatar(width)
    val badge = RecommendationCopy.similarityBadge(item.similarity)
    val color = serviceColor(item.service)
    Column(
        modifier = modifier
            .width(width)
            .hapticCombinedClickable(onClick = onOpen, onLongClick = onLongPress),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(avatar), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(avatar)
                    .clip(HexagonShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                if (!item.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.avatarUrl,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = item.name.take(1).uppercase(),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = OnFill,
                    )
                }
            }
            if (badge != null) {
                Text(
                    text = badge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OnFill,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(color)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            // Desktop's friend card plays the person's top tracks.
            PlayDisc(onPlay, Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(8.dp))
        CardText(item.name, primary = true, center = true, modifier = Modifier.fillMaxWidth())
        CardText(serviceName, center = true, modifier = Modifier.fillMaxWidth())
        // Fixed two-line height so every card in the row lines up.
        Box(Modifier.fillMaxWidth().height(32.dp)) {
            CardText(
                item.topArtists.joinToString(", "),
                tertiary = true, maxLines = 2, center = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(shimmerBrush()),
    )
}

/** Placeholder while a row loads: [lines] text lines under the cover (album 3, playlist 2, artist 1). */
@Composable
fun RecCardSkeleton(lines: Int, modifier: Modifier = Modifier, width: Dp = RecCardWidth) {
    Column(modifier = modifier.width(width)) {
        Box(
            Modifier
                .size(width)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmerBrush()),
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(lines.coerceAtLeast(1)) { i ->
                SkeletonBar(if (i == 0) 0.8f else 0.5f, if (i == 0) 12.dp else 10.dp)
            }
        }
    }
}

/** Placeholder for a listener while the Listeners row loads. */
@Composable
fun ListenerCardSkeleton(modifier: Modifier = Modifier, width: Dp = RecCardWidth) {
    val avatar = listenerAvatar(width)
    Column(modifier = modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(avatar)
                .clip(HexagonShape)
                .background(shimmerBrush()),
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SkeletonBar(0.6f, 12.dp)
            SkeletonBar(0.4f, 10.dp)
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────

internal val sampleRecAlbum get() = sampleAlbum

private val sampleAlbum = RecommendationItem(
    type = RecommendationType.ALBUM, id = "a1", name = "A Long Album Title Here",
    artist = "Some Artist", releaseDate = "2026-12-05",
)
private val sampleWeekly = RecommendationItem(
    type = RecommendationType.PLAYLIST, id = "w1", name = "Weekly Jams",
    weekly = WeeklyInfo("w1", "weekly-jams", "Weekly Jams", "Week of Nov 16"),
)
private val sampleListener = ListenerItem(
    id = "similar:listenbrainz:sam", name = "sam", service = "listenbrainz", similarity = 0.88,
    avatarUrl = null, topArtists = listOf("Radiohead", "Portishead", "Massive Attack"),
    resolverId = "listenbrainz", sourceIds = emptyList(),
)

@Composable
private fun CardsPreview(dark: Boolean) {
    ParachordTheme(darkTheme = dark) {
        Row(
            Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RecAlbumCard(sampleAlbum, "2026", "Coming Dec 5", {}, {}, {})
            RecArtistCard(sampleAlbum.copy(type = RecommendationType.ARTIST, name = "Some Artist"), null, {}, {})
            RecPlaylistCard(sampleAlbum.copy(type = RecommendationType.PLAYLIST, name = "Chill Mix"), "Spotify", {}, {})
        }
    }
}

@Composable
private fun CardsPreview2(dark: Boolean) {
    ParachordTheme(darkTheme = dark) {
        Row(
            Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RecWeeklyCard(sampleWeekly, emptyList(), {}, {})
            ListenerCard(sampleListener, "ListenBrainz", {}, {}, {})
            RecCardSkeleton(3)
            ListenerCardSkeleton()
        }
    }
}

@Preview(widthDp = 520, heightDp = 300) @Composable private fun CardsLight() = CardsPreview(false)
@Preview(widthDp = 520, heightDp = 300) @Composable private fun CardsDark() = CardsPreview(true)
@Preview(widthDp = 640, heightDp = 300) @Composable private fun Cards2Light() = CardsPreview2(false)
@Preview(widthDp = 640, heightDp = 300) @Composable private fun Cards2Dark() = CardsPreview2(true)
