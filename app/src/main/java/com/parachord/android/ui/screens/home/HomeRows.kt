package com.parachord.android.ui.screens.home

import com.parachord.android.ui.components.playlistMosaicFallback
import com.parachord.android.ui.components.ReasonBadge
import com.parachord.android.ui.components.ReasonMark
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ParachordPlaylistCard
import com.parachord.android.ui.components.ArtPlayButton
import com.parachord.android.ui.components.FilterChipItem
import com.parachord.android.ui.components.FilterChips
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.components.hapticCombinedClickable
import com.parachord.android.ui.screens.discover.CardText
import com.parachord.android.ui.screens.discover.CoverMosaic
import com.parachord.android.ui.components.ArtPlayInset
import com.parachord.android.ui.screens.discover.RecAlbumCard
import com.parachord.android.ui.screens.discover.RecCardSkeleton
import com.parachord.android.ui.screens.discover.RecCardWidth
import com.parachord.android.ui.components.rowCardWidth
import com.parachord.android.ui.components.serviceLogoVector
import com.parachord.android.ui.theme.current
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.home.FilterChipSource
import com.parachord.shared.home.HOME_ROW_CARDS
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.home.HomeMixCard
import com.parachord.shared.home.HomePlaylistCard
import com.parachord.shared.home.HomeState
import com.parachord.shared.home.albumReasonBadge
import com.parachord.shared.home.homeAlbumCardKey
import com.parachord.shared.home.homeSectionVisibility
import com.parachord.shared.home.homeSourceChips
import com.parachord.shared.home.mixCardArt
import com.parachord.shared.home.mixCardKey
import com.parachord.shared.home.mixCardSubtitle
import com.parachord.shared.home.showSourceChips
import com.parachord.shared.model.Playlist
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.SHUFFLEUPAGUS_ID
import com.parachord.shared.recommendations.releaseYear
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.icons.AppIcon
import kotlinx.coroutines.launch

/** Desktop's `HOME_ROW_GAP`. */
private val CardGap = 16.dp
private val Gutter = DesignTokens.SCREEN_GUTTER.dp

/** A Home row card's width: 140 on a phone (the row scrolls), wider on a tablet so six fill the row. */
@Composable
internal fun homeRowCardWidth(): Dp = rowCardWidth(cards = HOME_ROW_CARDS, gutter = Gutter, gap = CardGap)
private val ArtShape = RoundedCornerShape(DesignTokens.RADIUS_ART.dp)

// The three card rows of desktop's Home (`home-sections.js`): Made from your
// listening, Your playlists freshest first, Albums you might love. What each
// card shows is worked out in shared code (`HomeSession`, `HomeRows.kt`);
// these only draw it.

/** A row's source chips ("All" first), shown with any source ([showSourceChips]). */
@Composable
private fun SourceChips(sources: List<FilterChipSource>, selected: String, onSelect: (String) -> Unit) {
    if (!showSourceChips(sources)) return
    FilterChips(
        chips = homeSourceChips(sources).map { FilterChipItem(it.id, it.label, it.colorRgb) },
        selectedId = selected,
        onSelect = onSelect,
        pageFill = DesignTokens.Page.HOME.fill,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

/**
 * "Made from your listening": Recent Loves first (under All), then the
 * services' history mixes. A card opens its playlist; its Play plays it.
 */
@Composable
fun HomeMixesRow(
    state: HomeState,
    onSelectSource: (String) -> Unit,
    onOpenLoves: () -> Unit,
    onPlayLoves: () -> Unit,
    onOpenMix: (HomeMixCard.Mix) -> Unit,
    onPlayMix: (HomeMixCard.Mix) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!homeSectionVisibility(state).showMixes) return
    val cardWidth = homeRowCardWidth()
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = HomeCopy.MIXES_TITLE, subtitle = HomeCopy.MIXES_SUBTITLE)
        SourceChips(state.mixSources, state.mixSource, onSelectSource)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            items(state.mixRow, key = { mixCardKey(it) }) { card ->
                val art = mixCardArt(card)
                val title = when (card) {
                    is HomeMixCard.Loves -> HomeCopy.RECENT_LOVES
                    is HomeMixCard.Mix -> card.mix.title
                }
                ParachordPlaylistCard(
                    title = title,
                    // A description clamps to two lines (desktop `CLAMP2`).
                    lines = listOf(mixCardSubtitle(card)),
                    width = cardWidth,
                    onOpen = { if (card is HomeMixCard.Mix) onOpenMix(card) else onOpenLoves() },
                    onPlay = { if (card is HomeMixCard.Mix) onPlayMix(card) else onPlayLoves() },
                ) { size ->
                    CoverMosaic(
                        covers = art.imageUrl?.let { listOf(it) } ?: art.mosaic,
                        modifier = Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        }
    }
}

/**
 * "Your playlists, freshest first": the "Δ N" badge for the latest change's
 * new tracks and "Refreshed …". Long-press opens the playlist menu.
 */
@Composable
fun HomePlaylistsRow(
    state: HomeState,
    onOpen: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
    onLongPress: (Playlist) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!homeSectionVisibility(state).showPlaylists) return
    val cardWidth = homeRowCardWidth()
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = HomeCopy.PLAYLISTS_TITLE,
            subtitle = HomeCopy.PLAYLISTS_SUBTITLE,
            page = DesignTokens.Page.PLAYLISTS,
            onSeeAll = onSeeAll,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            items(state.playlists, key = { it.playlist.id }) { card ->
                PlaylistCard(card, onOpen, onPlay, onLongPress, cardWidth)
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    card: HomePlaylistCard,
    onOpen: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
    onLongPress: (Playlist) -> Unit,
    width: Dp,
) {
    val playlist = card.playlist
    ParachordPlaylistCard(
        title = playlist.name,
        lines = listOf(card.subtitle.refreshed),
        width = width,
        onOpen = { onOpen(playlist) },
        onPlay = { onPlay(playlist) },
        onLongPress = { onLongPress(playlist) },
        badge = card.badge,
    ) { size ->
        AlbumArtCard(
            artworkUrl = playlist.artworkUrl,
            size = size,
            cornerRadius = 0.dp,
            elevation = 0.dp,
            placeholderName = playlist.name,
            contentDescription = null,
            fallbackUrl = playlistMosaicFallback(playlist.id),
        )
    }
}

/**
 * "Albums you might love": six albums from the plug-ins and Shuffleupagus, with
 * source chips. Each card asks for its release date and missing cover as it
 * comes on screen ([onShown]); Shuffleupagus's picks carry a reason badge.
 */
@Composable
fun HomeAlbumsRow(
    state: HomeState,
    onSelectSource: (String) -> Unit,
    onShown: (RecommendationItem) -> Unit,
    onOpen: (RecommendationItem) -> Unit,
    onPlay: (RecommendationItem) -> Unit,
    onLongPress: (RecommendationItem) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibility = homeSectionVisibility(state)
    val loading = visibility.albumsLoading
    if (!visibility.showAlbums) return
    val cardWidth = homeRowCardWidth()
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = HomeCopy.ALBUMS_TITLE,
            page = DesignTokens.Page.RECOMMENDATIONS,
            onSeeAll = onSeeAll,
        )
        SourceChips(state.albumSources, state.albumSource, onSelectSource)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            if (loading) {
                items(HOME_ROW_CARDS, key = { "skeleton-$it" }) { RecCardSkeleton(lines = 2, width = cardWidth) }
            } else {
                // Artist + title is unique in the list (homeRecommendationAlbums dedups by it); an id may be blank.
                items(state.albums, key = { homeAlbumCardKey(it) }) { item ->
                    LaunchedEffect(item.id) { onShown(item) }
                    val reason = albumReasonBadge(item)
                    RecAlbumCard(
                        item = item,
                        width = cardWidth,
                        year = releaseYear(item.releaseDate),
                        // Desktop's Home album card has no "Coming" pill.
                        comingLabel = null,
                        onOpen = { onOpen(item) },
                        onPlay = { onPlay(item) },
                        onLongPress = { onLongPress(item) },
                        coverBadge = reason?.let { text -> { ReasonBadge(text, Modifier.align(Alignment.BottomStart)) } },
                    )
                }
            }
        }
    }
}

