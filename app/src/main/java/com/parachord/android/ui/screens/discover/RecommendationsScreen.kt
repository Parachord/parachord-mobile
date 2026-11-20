package com.parachord.android.ui.screens.discover

import com.parachord.shared.recommendations.playlistTapOpensView
import com.parachord.shared.ui.UiMessages
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.parachord.android.resolver.trackKey
import com.parachord.android.ui.components.AlbumContextMenu
import com.parachord.android.ui.components.ArtistContextMenu
import com.parachord.android.ui.components.ContextMenuItem
import com.parachord.android.ui.components.FilterChipItem
import com.parachord.android.ui.components.FilterChips
import com.parachord.android.ui.components.ModalBg
import com.parachord.android.ui.components.ModalBgDarker
import com.parachord.android.ui.components.ModalDivider
import com.parachord.android.ui.components.ModalIconTint
import com.parachord.android.ui.components.ModalTextActive
import com.parachord.android.ui.components.ModalTextPrimary
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.components.ResolveWhileVisible
import com.parachord.android.ui.components.SectionAction
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.components.rememberTrackContextMenuState
import com.parachord.android.ui.components.shimmerBrush
import com.parachord.android.ui.components.toImageVector
import com.parachord.android.ui.theme.FillAccent
import com.parachord.android.ui.theme.composeGradient
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.playback.resolverDisplayName
import com.parachord.shared.recommendations.CARD_PREVIEW
import com.parachord.shared.recommendations.ListenerItem
import com.parachord.shared.recommendations.PageStatus
import com.parachord.shared.recommendations.RecommendationCopy
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationPageBlock
import com.parachord.shared.recommendations.key
import com.parachord.shared.recommendations.recommendationPageLayout
import com.parachord.shared.recommendations.recommendationGridColumns
import com.parachord.shared.recommendations.RecommendationRow
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.RowView
import com.parachord.shared.recommendations.SHUFFLEUPAGUS_ID
import com.parachord.shared.recommendations.SONG_PREVIEW
import com.parachord.shared.recommendations.achordionUserUrl
import com.parachord.shared.recommendations.activeSourceFilter
import com.parachord.shared.recommendations.RECOMMENDATIONS_CONTEXT
import com.parachord.shared.recommendations.showsAchordionLink
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.parachord.shared.recommendations.filterRowBySource
import com.parachord.shared.recommendations.itemSourceName
import com.parachord.shared.recommendations.pageStatus
import com.parachord.shared.recommendations.recommendedSongTrack
import com.parachord.shared.recommendations.releaseYear
import com.parachord.shared.recommendations.upcomingLabel
import com.parachord.shared.repository.RecommendationPageState
import com.parachord.shared.repository.RecommendationsRepository
import com.parachord.shared.repository.unsavedFriendId
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.ServiceColors
import com.parachord.shared.ui.icons.AppIcon
import com.parachord.shared.ui.icons.PageIcon
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.androidx.compose.koinViewModel

private val CardGap = 12.dp
private val Gutter = DesignTokens.SCREEN_GUTTER.dp
private const val LISTENER_SKELETONS = 6

/** White on the page band and on fill-accent buttons (design system: never on the theme accent). */
private val OnFill = tokenColor(DesignTokens.ON_ACCENT)

private val HeroGradient = Brush.horizontalGradient(DesignTokens.Page.RECOMMENDATIONS.composeGradient)

/**
 * Desktop's Recommendations page (`recommendations-page.js`): the hero, the
 * source filter, then the shared page's sections. Every rule (which rows show,
 * previews, skeletons, the page's state) is the shared `RecommendationPageView`;
 * this only draws it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationsScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToWeekly: (playlistId: String, contextType: String) -> Unit = { _, _ -> },
    onNavigateToServicePlaylist: (url: String, name: String, source: String, image: String?) -> Unit = { _, _, _, _ -> },
    onNavigateToFriend: (friendId: String) -> Unit = {},
    onNavigateToPluginSettings: (pluginId: String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RecommendationsViewModel = koinViewModel(),
) {
    val page by viewModel.page.collectAsState()
    val selected by viewModel.sourceFilter.collectAsState()
    val expandedRows by viewModel.expandedRows.collectAsState()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val filter = activeSourceFilter(selected, page.sources)
    val status = pageStatus(page, filter)

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = RecommendationCopy.TITLE.uppercase(),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = UiMessages.BACK)
                }
            },
            windowInsets = WindowInsets(0),
        )
        RecommendationsHero(page.hero)

        if (status != PageStatus.NotConfigured) {
            FilterChips(
                chips = listOf(FilterChipItem(ALL_CHIP, RecommendationCopy.ALL)) + page.sources.map { source ->
                    FilterChipItem(
                        source.id,
                        source.name,
                        if (source.id == SHUFFLEUPAGUS_ID) DesignTokens.FILL_ACCENT else ServiceColors.color(source.id),
                    )
                },
                selectedId = filter ?: ALL_CHIP,
                onSelect = { id -> viewModel.setSourceFilter(id.takeIf { it != ALL_CHIP }) },
                pageFill = DesignTokens.Page.RECOMMENDATIONS.fill,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        when (status) {
            PageStatus.Loading -> PageSkeleton()
            PageStatus.NotConfigured -> PageMessage(
                title = RecommendationCopy.NOT_CONFIGURED_TITLE,
                body = RecommendationCopy.NOT_CONFIGURED_BODY,
                primary = RecommendationCopy.CONNECT_LISTENBRAINZ to { onNavigateToPluginSettings(RecommendationsRepository.LISTENBRAINZ) },
                secondary = RecommendationCopy.CONNECT_LASTFM to { onNavigateToPluginSettings(RecommendationsRepository.LASTFM) },
            )
            is PageStatus.Failed -> PageMessage(
                title = RecommendationCopy.FAILED_TITLE,
                body = status.text,
                primary = RecommendationCopy.TRY_AGAIN to viewModel::retry,
            )
            is PageStatus.Empty -> PageMessage(
                title = if (status.filteringOne) RecommendationCopy.EMPTY_SOURCE_TITLE else RecommendationCopy.EMPTY_TITLE,
                body = if (status.filteringOne) RecommendationCopy.EMPTY_SOURCE_BODY else RecommendationCopy.EMPTY_BODY,
                primary = RecommendationCopy.TRY_AGAIN to viewModel::retry,
            )
            PageStatus.Content -> PageContent(
                page = page,
                filter = filter,
                expandedRows = expandedRows,
                viewModel = viewModel,
                onNavigateToArtist = onNavigateToArtist,
                onNavigateToAlbum = onNavigateToAlbum,
                onNavigateToWeekly = onNavigateToWeekly,
                onNavigateToServicePlaylist = onNavigateToServicePlaylist,
                onNavigateToFriend = onNavigateToFriend,
            )
        }
    }
}

private const val ALL_CHIP = "all"

@Composable
private fun RecommendationsHero(hero: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeroGradient)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ParachordIcon(PageIcon.RECOMMENDATIONS.icon, contentDescription = null, size = 32.dp, tint = OnFill)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = hero,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = OnFill,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = RecommendationCopy.SUBLINE,
                fontSize = 12.sp,
                color = OnFill,
            )
        }
    }
}

// ── Page states ─────────────────────────────────────────────────────

@Composable
private fun PageMessage(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = primary.second,
                colors = ButtonDefaults.buttonColors(containerColor = FillAccent, contentColor = OnFill),
                shape = RoundedCornerShape(DesignTokens.RADIUS_BUTTON.dp),
            ) { Text(primary.first) }
            if (secondary != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = secondary.second,
                    shape = RoundedCornerShape(DesignTokens.RADIUS_BUTTON.dp),
                ) { Text(secondary.first, color = MaterialTheme.colorScheme.onBackground) }
            }
        }
    }
}

/** The page skeleton while nothing has loaded: a songs section and two card sections. */
@Composable
private fun PageSkeleton() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        songsSkeletonSection("skeleton:songs", null)
        cardsSkeletonSection("skeleton:albums", null, lines = 3)
        cardsSkeletonSection("skeleton:artists", null, lines = 1)
    }
}

@Composable
private fun SkeletonTitle() {
    Box(
        Modifier
            .padding(start = Gutter, top = 16.dp, bottom = 12.dp)
            .width(140.dp)
            .height(18.dp)
            .clip(RoundedCornerShape(DesignTokens.RADIUS_BADGE.dp))
            .background(shimmerBrush()),
    )
}

/** A section skeleton: [title] as a real header, or a shimmer bar when the title isn't known. */
private fun LazyListScope.skeletonHeader(key: String, title: String?) {
    item(key = "$key:header") {
        if (title != null) SectionHeader(title = title) else SkeletonTitle()
    }
}

private fun LazyListScope.songsSkeletonSection(key: String, title: String?) {
    skeletonHeader(key, title)
    items(SONG_PREVIEW, key = { "$key:$it" }) { ShimmerTrackRow() }
}

private fun LazyListScope.cardsSkeletonSection(key: String, title: String?, lines: Int) {
    skeletonHeader(key, title)
    item(key = "$key:cards") { SkeletonCardRow(lines) }
}

@Composable
private fun SkeletonCardRow(lines: Int, count: Int = CARD_PREVIEW) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
        userScrollEnabled = false,
    ) {
        items(count) { RecCardSkeleton(lines) }
    }
}

// ── Content ─────────────────────────────────────────────────────────

/** The lines under an album card (title, artist, year), a playlist card (title, source) and an artist card (name). */
private fun cardLines(type: RecommendationType?): Int = when (type) {
    RecommendationType.ALBUM -> 3
    RecommendationType.PLAYLIST -> 2
    else -> 1
}

@Composable
private fun PageContent(
    page: RecommendationPageState,
    filter: String?,
    expandedRows: Set<String>,
    viewModel: RecommendationsViewModel,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String, String) -> Unit,
    onNavigateToWeekly: (String, String) -> Unit,
    onNavigateToServicePlaylist: (String, String, String, String?) -> Unit,
    onNavigateToFriend: (String) -> Unit,
) {
    val playback by viewModel.playbackState.collectAsState()
    val trackResolvers by viewModel.trackResolvers.collectAsState()
    val trackConfidences by viewModel.trackResolverConfidences.collectAsState()
    val trackArtwork by viewModel.trackArtwork.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val trackMenu = rememberTrackContextMenuState()
    val context = LocalContext.current
    // Read on every composition, so a page left open past midnight updates its "Coming" pills.
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val scope = rememberCoroutineScope()

    var albumMenu by remember { mutableStateOf<RecommendationItem?>(null) }
    var artistMenu by remember { mutableStateOf<RecommendationItem?>(null) }
    var playlistMenu by remember { mutableStateOf<RecommendationItem?>(null) }
    var listenerMenu by remember { mutableStateOf<ListenerItem?>(null) }

    // The page's blocks in drawing order: the shared layout iOS draws too.
    val blocks = recommendationPageLayout(page, filter, expandedRows)

    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val grid = remember(maxWidth, density) {
        gridCardWidth(maxWidth - Gutter * 2, CardGap, density).let { (cols, w) -> GridSpec(cols, w) }
    }

    val cards = CardActions(
        viewModel = viewModel,
        today = today,
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToAlbum = onNavigateToAlbum,
        onNavigateToWeekly = onNavigateToWeekly,
        onNavigateToServicePlaylist = onNavigateToServicePlaylist,
        onNavigateToFriend = onNavigateToFriend,
        onAlbumMenu = { albumMenu = it },
        onArtistMenu = { artistMenu = it },
        onPlaylistMenu = { playlistMenu = it },
        onListenerMenu = { listenerMenu = it },
        grid = grid,
    )

    val songRow: @Composable (RecommendationRow, List<RecommendationItem>, Int, RecommendationItem) -> Unit = { row, items, index, item ->
        SongRow(
            row = row,
            items = items,
            index = index,
            item = item,
            viewModel = viewModel,
            isPlaying = { track ->
                val ctx = playback.playbackContext
                ctx?.type == RECOMMENDATIONS_CONTEXT && ctx.id == row.key && playback.currentTrack?.id == track.id
            },
            resolvers = { key -> trackResolvers[key] },
            confidences = { key -> trackConfidences[key] },
            artwork = { key -> trackArtwork[key] },
            onLongPress = { track ->
                scope.launch {
                    // A loved song's menu offers Remove from Collection.
                    val loved = viewModel.isTrackInCollection(track.title, track.artist)
                    trackMenu.show(
                        TrackContextInfo(
                            title = track.title,
                            artist = track.artist,
                            artworkUrl = track.artworkUrl,
                            duration = track.duration,
                            isInCollection = loved,
                        ),
                        track,
                    )
                }
            },
        )
    }

    // Blocks arrive one provider at a time, and the shared layout can put a new one
    // above those on screen (an earlier section, the Albums slot). A keyed LazyColumn
    // keeps the first visible item where it was, so the page looked scrolled down.
    // While the user hasn't scrolled away from the top, keep it there as blocks
    // arrive (iOS's page does the same); once they have, never move them.
    val listState = rememberLazyListState()
    var pinnedToTop by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // Only the user's own scrolling decides; an insertion that moves the first
        // visible item isn't a scroll in progress.
        snapshotFlow {
            Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (scrolling, index, offset) ->
            if (scrolling) pinnedToTop = index == 0 && offset == 0
        }
    }
    val blockKeys = blocks.map { it.key }
    LaunchedEffect(blockKeys) {
        if (pinnedToTop && blockKeys.isNotEmpty()) listState.requestScrollToItem(0)
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        blocks.forEach { block ->
            when (block) {
                is RecommendationPageBlock.SectionTitle ->
                    item(key = "section:${block.section.type?.wire ?: "listeners"}:header") {
                        SectionHeader(title = block.section.title)
                    }
                is RecommendationPageBlock.Row -> row(block, filter, cards, songRow)
                RecommendationPageBlock.PersonalSkeleton -> personalPendingRow()
                RecommendationPageBlock.AlbumsSkeleton ->
                    cardsSkeletonSection("skeleton:albums-slot", RecommendationCopy.ALBUMS, lines = 3)
                // Providers still answering with nothing on screen: one unlabeled
                // skeleton after the content and before Listeners (desktop).
                RecommendationPageBlock.PendingSkeleton -> cardsSkeletonSection("skeleton:pending", null, lines = 3)
                RecommendationPageBlock.ListenersSkeleton -> {
                    item(key = "skeleton:listeners:header") { SectionHeader(title = RecommendationCopy.LISTENERS) }
                    item(key = "skeleton:listeners:cards") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Gutter),
                            horizontalArrangement = Arrangement.spacedBy(CardGap),
                            userScrollEnabled = false,
                        ) { items(LISTENER_SKELETONS) { ListenerCardSkeleton() } }
                    }
                }
            }
        }
    }
    }

    TrackContextMenuHost(
        state = trackMenu,
        playlists = playlists,
        onPlayNext = viewModel::playNext,
        onAddToQueue = viewModel::addToQueue,
        onAddToPlaylist = viewModel::addToPlaylist,
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToAlbum = onNavigateToAlbum,
        onToggleCollection = viewModel::toggleTrackCollection,
    )

    albumMenu?.let { item ->
        val artist = item.artist.orEmpty()
        val inCollection by remember(item.id) { viewModel.isAlbumInCollection(item.name, artist) }.collectAsState(false)
        AlbumContextMenu(
            albumTitle = item.name,
            artistName = artist,
            artworkUrl = item.imageUrl,
            isInCollection = inCollection,
            onDismiss = { albumMenu = null },
            onPlayAlbum = { albumMenu = null; viewModel.playAlbum(item) },
            onQueueAlbum = { albumMenu = null; viewModel.queueAlbum(item) },
            onGoToArtist = if (artist.isNotBlank()) {
                { albumMenu = null; onNavigateToArtist(artist) }
            } else null,
            onToggleCollection = { albumMenu = null; viewModel.toggleAlbumCollection(item, inCollection) },
        )
    }
    artistMenu?.let { item ->
        val inCollection by remember(item.name) { viewModel.isArtistInCollection(item.name) }.collectAsState(false)
        ArtistContextMenu(
            artistName = item.name,
            imageUrl = item.imageUrl,
            isInCollection = inCollection,
            onDismiss = { artistMenu = null },
            onPlayTopSongs = { artistMenu = null; viewModel.playArtistTopSongs(item.name) },
            onQueueTopSongs = { artistMenu = null; viewModel.queueArtistTopSongs(item.name) },
            onGoToArtist = { artistMenu = null; onNavigateToArtist(item.name) },
            onToggleCollection = {
                artistMenu = null
                viewModel.toggleArtistCollection(item.name, item.imageUrl, inCollection)
            },
        )
    }
    playlistMenu?.let { item ->
        MenuSheet(
            title = item.name,
            subtitle = null,
            onDismiss = { playlistMenu = null },
            entries = listOf(
                MenuEntry(AppIcon.Play, RecommendationCopy.PLAY) { viewModel.playServicePlaylist(item) },
                MenuEntry(AppIcon.QueueAdd, RecommendationCopy.ADD_TO_QUEUE) { viewModel.queueServicePlaylist(item) },
            ),
        )
    }
    listenerMenu?.let { item ->
        MenuSheet(
            title = item.name,
            subtitle = resolverDisplayName(item.service),
            onDismiss = { listenerMenu = null },
            entries = listOfNotNull(
                MenuEntry(AppIcon.Clock, RecommendationCopy.VIEW_HISTORY) {
                    onNavigateToFriend(unsavedFriendId(item.service, item.name))
                },
                MenuEntry(AppIcon.Plus, RecommendationCopy.FOLLOW) { viewModel.followListener(item) },
                if (showsAchordionLink(item.service)) {
                    MenuEntry(AppIcon.ExternalLink, RecommendationCopy.VIEW_ON_ACHORDION) {
                        openUrl(context, achordionUserUrl(item.name))
                    }
                } else null,
            ),
        )
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // No browser: nothing to open it with.
    }
}

/** What a card tap, play or long-press does; one bundle so the lazy list's item lambdas stay small. */
private class CardActions(
    val viewModel: RecommendationsViewModel,
    val today: LocalDate,
    val onNavigateToArtist: (String) -> Unit,
    val onNavigateToAlbum: (String, String) -> Unit,
    val onNavigateToWeekly: (String, String) -> Unit,
    val onNavigateToServicePlaylist: (String, String, String, String?) -> Unit,
    val onNavigateToFriend: (String) -> Unit,
    val onAlbumMenu: (RecommendationItem) -> Unit,
    val onArtistMenu: (RecommendationItem) -> Unit,
    val onPlaylistMenu: (RecommendationItem) -> Unit,
    val onListenerMenu: (ListenerItem) -> Unit,
    /** An open row's grid, from the page's width. */
    val grid: GridSpec,
)

/** Shuffleupagus's placeholder row in a categorized Albums section. */
private fun LazyListScope.personalPendingRow() {
    item(key = "albums:personal-pending:header") {
        SectionHeader(title = RecommendationCopy.BASED_ON_LISTENING, sub = true)
    }
    item(key = "albums:personal-pending:cards") { SkeletonCardRow(lines = 3) }
}

/** One row: its header, then its songs or cards (desktop's `renderSubsection`). */
private fun LazyListScope.row(
    block: RecommendationPageBlock.Row,
    filter: String?,
    cards: CardActions,
    songRow: @Composable (RecommendationRow, List<RecommendationItem>, Int, RecommendationItem) -> Unit,
) {
    val row = block.row
    val rv: RowView = block.view
    val expanded = block.expanded
    item(key = "${row.key}:header") {
        SectionHeader(
            title = block.title,
            sub = block.subtitle,
            action = if (rv.showToggle) SectionAction(rv.toggleLabel) { cards.viewModel.toggleRow(row.key) } else null,
        )
    }
    when {
        row.type == RecommendationType.SONG -> {
            // The queue after a tapped song is the rest of the row's FULL filtered list.
            val filtered = filterRowBySource(row, filter).items
            itemsIndexed(rv.items, key = { i, item -> "${row.key}:song:$i:${item.id}" }) { i, item ->
                songRow(row, filtered, i, item)
            }
            if (rv.loadingTail) items(SONG_PREVIEW, key = { "${row.key}:song-more:$it" }) { ShimmerTrackRow() }
        }
        row.type == null -> cardsBody(
            rowKey = "${row.key}:listeners",
            expanded = expanded,
            tail = 0,
            lines = 1,
            grid = cards.grid,
            cardList = CardList().apply {
                rv.listeners.forEach { listener -> card(listener.id) { w -> ListenerCardFor(listener, cards, w) } }
            },
        )
        else -> cardsBody(
            rowKey = "${row.key}:cards",
            expanded = expanded,
            tail = (if (rv.loadingTail) CARD_PREVIEW else 0) + rv.aiTail,
            lines = cardLines(row.type),
            grid = cards.grid,
            cardList = CardList().apply {
                rv.items.forEach { item -> card(item.id) { w -> CardFor(item, row, cards, w) } }
            },
        )
    }
}

/** Cards collected for [cardsBody], each with a lazy-list key. */
private class CardList {
    val cards = mutableListOf<Pair<String, @Composable (Dp) -> Unit>>()
    private val seen = mutableSetOf<String>()
    /** [content] draws one card at the width it's given; [key] is the item's id (made unique within the row). */
    fun card(key: String, content: @Composable (width: Dp) -> Unit) {
        val unique = if (seen.add(key)) key else "$key#${cards.size}"
        seen += unique
        cards += unique to content
    }
}

/** An open row's grid: how many cards per line and each card's width. */
private data class GridSpec(val columns: Int, val cardWidth: Dp)

/**
 * An open row's grid: the shared [recommendationGridColumns] rule, each card
 * stretched so the row fills [available] edge to edge with [gap] between.
 * The width is floored to a whole pixel so rounding can't push a card onto
 * the next line.
 */
private fun gridCardWidth(available: Dp, gap: Dp, density: androidx.compose.ui.unit.Density): Pair<Int, Dp> {
    val columns = recommendationGridColumns(available.value.toDouble(), gap.value.toDouble())
    val px = with(density) { ((available - gap * (columns - 1)).toPx() / columns).toInt() }
    return columns to with(density) { px.toDp() }
}

/**
 * Collapsed: one scrolling row of 140 dp cards, keyed by item. Expanded: a grid whose
 * cards fill the width, emitted as one lazy-list item per grid line, so only the lines
 * on screen compose (each card asks for its release date and image as it composes).
 * Then [tail] skeletons.
 */
private fun LazyListScope.cardsBody(
    rowKey: String,
    expanded: Boolean,
    tail: Int,
    lines: Int,
    grid: GridSpec,
    cardList: CardList,
) {
    if (expanded) {
        val slots: List<Pair<String, @Composable (Dp) -> Unit>> = cardList.cards +
            List(tail) { i -> "skeleton:$i" to @Composable { w: Dp -> RecCardSkeleton(lines, width = w) } }
        slots.chunked(grid.columns).forEach { line ->
            item(key = "$rowKey:grid:${line.first().first}") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Gutter, end = Gutter, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(CardGap),
                ) {
                    line.forEach { (_, draw) -> draw(grid.cardWidth) }
                }
            }
        }
    } else {
        item(key = rowKey) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                items(cardList.cards, key = { it.first }) { (_, draw) -> draw(RecCardWidth) }
                items(tail) { RecCardSkeleton(lines) }
            }
        }
    }
}

@Composable
private fun CardFor(item: RecommendationItem, row: RecommendationRow, cards: CardActions, width: Dp = RecCardWidth) {
    val vm = cards.viewModel
    when (item.type) {
        RecommendationType.ALBUM -> {
            LaunchedEffect(item.id) { vm.cardShown(item) }
            val artist = item.artist.orEmpty()
            RecAlbumCard(
                width = width,
                item = item,
                year = releaseYear(item.releaseDate),
                comingLabel = upcomingLabel(item.releaseDate, cards.today),
                onOpen = { cards.onNavigateToAlbum(item.name, artist) },
                onPlay = { vm.playAlbum(item) },
                onLongPress = { cards.onAlbumMenu(item) },
            )
        }
        RecommendationType.ARTIST -> {
            LaunchedEffect(item.id) { vm.cardShown(item) }
            RecArtistCard(
                width = width,
                item = item,
                imageUrl = item.imageUrl,
                onOpen = { cards.onNavigateToArtist(item.name) },
                onLongPress = { cards.onArtistMenu(item) },
            )
        }
        RecommendationType.PLAYLIST -> {
            val weekly = item.weekly
            if (weekly != null) {
                val covers by remember(weekly.id) { vm.weeklyCovers(weekly.id) }.collectAsState()
                RecWeeklyCard(
                    width = width,
                    item = item,
                    covers = covers,
                    onOpen = { cards.onNavigateToWeekly(weekly.id, weekly.contextType) },
                    onPlay = { vm.playWeekly(item) },
                )
            } else {
                RecPlaylistCard(
                    width = width,
                    item = item,
                    sourceName = itemSourceName(item, row),
                    onOpen = {
                        val url = item.url
                        if (playlistTapOpensView(item) && url != null) {
                            cards.onNavigateToServicePlaylist(url, item.name, itemSourceName(item, row), item.imageUrl)
                        } else {
                            vm.playServicePlaylist(item)
                        }
                    },
                    onLongPress = { cards.onPlaylistMenu(item) },
                )
            }
        }
        RecommendationType.SONG -> Unit
    }
}

@Composable
private fun ListenerCardFor(item: ListenerItem, cards: CardActions, width: Dp = RecCardWidth) {
    ListenerCard(
        width = width,
        item = item,
        serviceName = resolverDisplayName(item.service),
        onOpen = { cards.onNavigateToFriend(unsavedFriendId(item.service, item.name)) },
        onPlay = { cards.viewModel.playListener(item) },
        onLongPress = { cards.onListenerMenu(item) },
    )
}

@Composable
private fun SongRow(
    row: RecommendationRow,
    items: List<RecommendationItem>,
    index: Int,
    item: RecommendationItem,
    viewModel: RecommendationsViewModel,
    isPlaying: (com.parachord.shared.model.Track) -> Boolean,
    resolvers: (String) -> List<String>?,
    confidences: (String) -> Map<String, Float>?,
    artwork: (String) -> String?,
    onLongPress: (com.parachord.shared.model.Track) -> Unit,
) {
    val track = remember(item) { recommendedSongTrack(item) }
    if (track != null) ResolveWhileVisible(track.id) { viewModel.resolveVisible(track) }
    val key = trackKey(item.name, item.artist.orEmpty())
    TrackRow(
        title = item.name,
        artist = item.artist.orEmpty(),
        artworkUrl = item.imageUrl ?: artwork(key),
        resolvers = resolvers(key),
        resolverConfidences = confidences(key),
        duration = track?.duration,
        isPlaying = track != null && isPlaying(track),
        onClick = { if (track != null) viewModel.playSong(items, index, row) },
        onLongClick = track?.let { t -> { onLongPress(t) } },
    )
}

// ── Long-press sheets with no shared component ──────────────────────

private class MenuEntry(val icon: AppIcon, val label: String, val onClick: () -> Unit)

/** The always-dark sheet of [com.parachord.android.ui.components.AlbumContextMenu], for the playlist and listener menus. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(title: String, subtitle: String?, onDismiss: () -> Unit, entries: List<MenuEntry>) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ModalBg,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(color = ModalDivider, shape = RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(ModalBg, ModalBgDarker)))
                .padding(bottom = 32.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = ModalTextActive,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(text = subtitle, fontSize = 13.sp, color = ModalTextPrimary, maxLines = 1)
                }
            }
            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 8.dp))
            entries.forEach { entry ->
                ContextMenuItem(
                    icon = remember(entry.icon) { entry.icon.spec.toImageVector(entry.icon.name, ModalIconTint) },
                    label = entry.label,
                    onClick = {
                        onDismiss()
                        entry.onClick()
                    },
                )
            }
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────

/** An open Albums row on a 412 dp phone: three cards across, filling the row, then skeletons. */
@androidx.compose.ui.tooling.preview.Preview(widthDp = 412, heightDp = 640)
@Composable
private fun ExpandedGridPreview() {
    com.parachord.android.ui.theme.ParachordTheme(darkTheme = true) {
        val density = LocalDensity.current
        val grid = gridCardWidth(412.dp - Gutter * 2, CardGap, density).let { (c, w) -> GridSpec(c, w) }
        LazyColumn(Modifier.background(MaterialTheme.colorScheme.background)) {
            cardsBody(
                rowKey = "preview", expanded = true, tail = 2, lines = 3, grid = grid,
                cardList = CardList().apply {
                    repeat(4) { i ->
                        card("a$i") { w ->
                            RecAlbumCard(sampleRecAlbum.copy(id = "a$i"), "2026", null, {}, {}, {}, width = w)
                        }
                    }
                },
            )
        }
    }
}
