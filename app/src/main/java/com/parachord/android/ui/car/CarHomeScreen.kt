package com.parachord.android.ui.car

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.R
import com.parachord.android.resolver.trackKey
import com.parachord.android.ui.MainViewModel
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ParachordAlbumCard
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.components.ResolveWhileVisible
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.screens.discover.ListenerCard
import com.parachord.android.ui.screens.discover.RecAlbumCard
import com.parachord.android.ui.screens.discover.RecArtistCard
import com.parachord.android.ui.screens.discover.RecCardSkeleton
import com.parachord.android.ui.screens.discover.RecPlaylistCard
import com.parachord.android.ui.screens.discover.RecWeeklyCard
import com.parachord.android.ui.screens.discover.RecommendationsViewModel
import com.parachord.android.ui.theme.FillAccent
import com.parachord.android.ui.theme.tokenColor
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.androidx.compose.koinViewModel
import com.parachord.shared.playback.resolverDisplayName
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.recommendations.RecommendationCopy
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationPageBlock
import com.parachord.shared.recommendations.PageStatus
import com.parachord.shared.recommendations.RecommendationRow
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.activeSourceFilter
import com.parachord.shared.recommendations.filterRowBySource
import com.parachord.shared.recommendations.itemSourceName
import com.parachord.shared.recommendations.pageStatus
import com.parachord.shared.recommendations.recommendationPageLayout
import com.parachord.shared.recommendations.recommendedSongTrack
import com.parachord.shared.recommendations.releaseYear
import com.parachord.shared.recommendations.upcomingLabel
import com.parachord.shared.repository.RecommendationPageState
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.icons.AppIcon

/** A card's width in the car's rows; phone rows are 140 dp, car rows read larger. */
internal val CarCardWidth = 180.dp

private val Gutter = DesignTokens.SCREEN_GUTTER.dp
private val CardGap = 12.dp

/** White on fill-accent buttons (design system: never on the theme accent). */
private val OnFill = tokenColor(DesignTokens.ON_ACCENT)

/**
 * The car home: the app's own AAOS landing screen. The body is the shared
 * recommendations page (categories as rows of cards, previews only — no See
 * all, no source chips, no long-press menus in the car); the top bar offers
 * search and the settings gear (parked only); the bottom is the now-playing
 * bar. Tapping any entry plays it: a song plays its row's queue, an album its
 * tracks, an artist their top songs, a playlist its tracks, a listener their
 * top tracks — every play action is [RecommendationsViewModel]'s, unmodified.
 */
@Composable
fun CarHomeScreen(
    mainViewModel: MainViewModel,
    restricted: Boolean,
    onOpenSearch: () -> Unit,
    onOpenFullUi: () -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val viewModel: RecommendationsViewModel = koinViewModel()
    val libraryViewModel: CarHomeLibraryViewModel = koinViewModel()
    val playlists by libraryViewModel.playlists.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A car has no snackbar corner: the page's messages (queue loads, follows) show
    // as toasts. Play failures show through CarRoot (the controller reports them
    // as notices and/or the state's playbackError, which CarRoot dedupes).
    LaunchedEffect(Unit) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) {
        libraryViewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        CarHomeTopBar(restricted = restricted, onOpenSearch = onOpenSearch, onOpenFullUi = onOpenFullUi)
        Box(Modifier.weight(1f)) {
            CarRecommendations(
                viewModel = viewModel,
                page = page,
                restricted = restricted,
                onOpenFullUi = onOpenFullUi,
                libraryViewModel = libraryViewModel,
                playlists = playlists,
            )
        }
        CarNowPlayingBar(mainViewModel = mainViewModel, onClick = onOpenNowPlaying)
    }
}

@Composable
private fun CarHomeTopBar(restricted: Boolean, onOpenSearch: () -> Unit, onOpenFullUi: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        // Search stays offered while driving (the car spec keeps it on the driving home) —
        // a labeled button, not a corner icon: corner affordances proved croppable on a
        // car display (and in an emulator window).
        OutlinedButton(
            onClick = onOpenSearch,
            modifier = Modifier.height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(24.dp),
        ) {
            ParachordIcon(AppIcon.Search, contentDescription = null, size = 22.dp)
            Spacer(Modifier.width(6.dp))
            Text(text = "Search", fontSize = 14.sp)
        }
        IconButton(
            onClick = onOpenFullUi,
            enabled = !restricted,
            modifier = Modifier.size(56.dp),
        ) {
            ParachordIcon(
                AppIcon.Settings,
                contentDescription = if (restricted) "Park to open settings" else "Settings",
                size = 28.dp,
                tint = if (restricted) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
        }
    }
}

@Composable
private fun CarRecommendations(
    viewModel: RecommendationsViewModel,
    page: RecommendationPageState,
    restricted: Boolean,
    onOpenFullUi: () -> Unit,
    libraryViewModel: CarHomeLibraryViewModel,
    playlists: List<com.parachord.android.data.db.entity.PlaylistEntity>,
) {
    // No source chips in the car: every category shows, from every source.
    val filter = activeSourceFilter(null, page.sources)
    Column(Modifier.fillMaxSize()) {
        // The Library is the user's own: it shows whatever the recommendations
        // are doing — loading, failed, or not configured.
        CarLibrarySection(libraryViewModel = libraryViewModel, playlists = playlists)
        Box(Modifier.weight(1f)) {
            when (val status = pageStatus(page, filter)) {
        PageStatus.Loading -> CarPageSkeleton()
        PageStatus.NotConfigured -> CarPageMessage(
            title = RecommendationCopy.NOT_CONFIGURED_TITLE,
            body = RecommendationCopy.NOT_CONFIGURED_BODY,
            buttonText = "Open settings",
            buttonEnabled = !restricted,
            onButton = onOpenFullUi,
            hint = if (restricted) "Park to open settings" else null,
        )
        is PageStatus.Failed -> CarPageMessage(
            title = RecommendationCopy.FAILED_TITLE,
            body = status.text,
            buttonText = RecommendationCopy.TRY_AGAIN,
            onButton = { viewModel.retry() },
        )
        is PageStatus.Empty -> CarPageMessage(
            title = RecommendationCopy.EMPTY_TITLE,
            body = RecommendationCopy.EMPTY_BODY,
            buttonText = RecommendationCopy.TRY_AGAIN,
            onButton = { viewModel.retry() },
        )
        PageStatus.Content -> CarRecRows(viewModel = viewModel, page = page, filter = filter)
            }
        }
    }
}

/**
 * The Library section: Collection Radio and the saved playlists. It renders
 * whatever the recommendations are doing — loading, failed, or not configured —
 * because it is the user's own music, not a service's answer.
 */
@Composable
private fun CarLibrarySection(libraryViewModel: CarHomeLibraryViewModel, playlists: List<com.parachord.android.data.db.entity.PlaylistEntity>) {
    SectionHeader(title = "Library")
    LazyRow(
        contentPadding = PaddingValues(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        item(key = "car:collection-radio") {
            CarCollectionRadioTile(onPlay = libraryViewModel::playCollectionRadio)
        }
        items(playlists, key = { it.id }) { playlist ->
            com.parachord.android.ui.components.ParachordPlaylistCard(
                title = playlist.name,
                lines = listOf("${playlist.trackCount} track${if (playlist.trackCount != 1) "s" else ""}"),
                width = CarCardWidth,
                onOpen = { libraryViewModel.playPlaylist(playlist) },
            ) { side ->
                AlbumArtCard(
                    artworkUrl = playlist.artworkUrl,
                    size = side,
                    cornerRadius = 0.dp,
                    elevation = 0.dp,
                    placeholderName = playlist.name,
                    contentDescription = playlist.name,
                )
            }
        }
    }
}

// ── Rows ───────────────────────────────────────────────────────────// ── Rows ───────────────────────────────────────────────────────────

/** The recommendations blocks of a loaded page, one row of cards per category. */
@Composable
private fun CarRecRows(
    viewModel: RecommendationsViewModel,
    page: RecommendationPageState,
    filter: String?,
) {
    val trackArtwork by viewModel.trackArtwork.collectAsStateWithLifecycle()
    // Read on every composition, so a page left open past midnight updates its "Coming" pills.
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val blocks = recommendationPageLayout(page, filter, emptySet())

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        blocks.forEach { block ->
            when (block) {
                is RecommendationPageBlock.SectionTitle ->
                    item(key = "car:section:${block.section.type?.wire ?: "listeners"}:header") {
                        SectionHeader(title = block.section.title)
                    }
                is RecommendationPageBlock.Row -> carRow(block, viewModel, filter, trackArtwork, today)
                RecommendationPageBlock.PersonalSkeleton -> {
                    item(key = "car:skeleton:personal:header") {
                        SectionHeader(title = RecommendationCopy.BASED_ON_LISTENING, sub = true)
                    }
                    carSkeletonCards("car:skeleton:personal", lines = 3)
                }
                RecommendationPageBlock.AlbumsSkeleton -> {
                    item(key = "car:skeleton:albums:header") { SectionHeader(title = RecommendationCopy.ALBUMS) }
                    carSkeletonCards("car:skeleton:albums", lines = 3)
                }
                RecommendationPageBlock.PendingSkeleton -> carSkeletonCards("car:skeleton:pending", lines = 3)
                RecommendationPageBlock.ListenersSkeleton -> {
                    item(key = "car:skeleton:listeners:header") { SectionHeader(title = RecommendationCopy.LISTENERS) }
                    carSkeletonCards("car:skeleton:listeners", lines = 1)
                }
            }
        }
    }
}

/** One row: its header, then its songs or cards — a card row per category (the car is a browser of rows). */
private fun LazyListScope.carRow(
    block: RecommendationPageBlock.Row,
    viewModel: RecommendationsViewModel,
    filter: String?,
    trackArtwork: Map<String, String>,
    today: LocalDate,
) {
    val row = block.row
    val rv = block.view
    item(key = "car:${row.key}:header") {
        SectionHeader(title = block.title, sub = block.subtitle)
    }
    when {
        row.type == RecommendationType.SONG -> item(key = "car:${row.key}:songs") {
            // The queue after a tapped song is the rest of the row's FULL filtered list.
            val filtered = filterRowBySource(row, filter).items
            LazyRow(
                contentPadding = PaddingValues(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                itemsIndexed(rv.items, key = { i, item -> "${row.key}:song:$i:${item.id}" }) { i, item ->
                    CarSongCard(item = item, width = CarCardWidth, trackArtwork = trackArtwork, viewModel = viewModel) {
                        viewModel.playSong(filtered, i, row)
                    }
                }
            }
        }
        row.type == null -> item(key = "car:${row.key}:listeners") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                items(rv.listeners, key = { it.id }) { listener ->
                    ListenerCard(
                        item = listener,
                        serviceName = resolverDisplayName(listener.service),
                        onOpen = { viewModel.playListener(listener) },
                        onPlay = { viewModel.playListener(listener) },
                        onLongPress = {},
                        width = CarCardWidth,
                    )
                }
            }
        }
        else -> item(key = "car:${row.key}:cards") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                items(rv.items, key = { it.id }) { item ->
                    CarEntryCard(
                        item = item,
                        row = row,
                        viewModel = viewModel,
                        trackArtwork = trackArtwork,
                        today = today,
                        width = CarCardWidth,
                    )
                }
            }
        }
    }
}

/** A recommended entry as a car card: tap plays it (no detail views in the car). */
@Composable
private fun CarEntryCard(
    item: RecommendationItem,
    row: RecommendationRow,
    viewModel: RecommendationsViewModel,
    trackArtwork: Map<String, String>,
    today: LocalDate,
    width: Dp,
) {
    when (item.type) {
        RecommendationType.ALBUM -> {
            LaunchedEffect(item.id) { viewModel.cardShown(item) }
            RecAlbumCard(
                item = item,
                year = releaseYear(item.releaseDate),
                comingLabel = upcomingLabel(item.releaseDate, today),
                onOpen = { viewModel.playAlbum(item) },
                onPlay = { viewModel.playAlbum(item) },
                onLongPress = {},
                width = width,
            )
        }
        RecommendationType.ARTIST -> {
            LaunchedEffect(item.id) { viewModel.cardShown(item) }
            RecArtistCard(
                item = item,
                imageUrl = item.imageUrl,
                onOpen = { viewModel.playArtistTopSongs(item.name) },
                onLongPress = {},
                width = width,
            )
        }
        RecommendationType.PLAYLIST -> {
            val weekly = item.weekly
            if (weekly != null) {
                val covers by remember(weekly.id) { viewModel.weeklyCovers(weekly.id) }.collectAsStateWithLifecycle()
                RecWeeklyCard(
                    item = item,
                    covers = covers,
                    onOpen = { viewModel.playWeekly(item) },
                    onPlay = { viewModel.playWeekly(item) },
                    width = width,
                )
            } else {
                RecPlaylistCard(
                    item = item,
                    sourceName = itemSourceName(item, row),
                    onOpen = { viewModel.playServicePlaylist(item) },
                    onLongPress = {},
                    width = width,
                )
            }
        }
        RecommendationType.SONG -> {
            // Song rows render through the SONG branch above; this keeps the
            // card honest if a song ever lands in a cards row. The car home
            // never filters by source, so the row's own list is the queue.
            val index = row.items.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
            CarSongCard(item = item, width = width, trackArtwork = trackArtwork, viewModel = viewModel) {
                viewModel.playSong(row.items, index, row)
            }
        }
    }
}

/** A recommended song as a card; its art falls back to what its resolved sources carry. */
@Composable
private fun CarSongCard(
    item: RecommendationItem,
    width: Dp,
    trackArtwork: Map<String, String>,
    viewModel: RecommendationsViewModel,
    onPlay: () -> Unit,
) {
    val track = remember(item) { recommendedSongTrack(item) }
    if (track != null) ResolveWhileVisible(track.id) { viewModel.resolveVisible(track) }
    ParachordAlbumCard(
        title = item.name,
        artist = item.artist,
        artworkUrl = item.imageUrl ?: trackArtwork[trackKey(item.name, item.artist.orEmpty())],
        width = width,
        onOpen = onPlay,
        onPlay = onPlay,
    )
}

private fun LazyListScope.carSkeletonCards(key: String, lines: Int) {
    item(key = "$key:cards") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
            userScrollEnabled = false,
        ) {
            items(6) { RecCardSkeleton(lines, width = CarCardWidth) }
        }
    }
}

/**
 * The Library row's Collection Radio tile: the shared radio icon on the fill
 * accent. Tapping it plays the collection, shuffled (the shared HomeQueues
 * collection-radio path -- the same thing Home's header button and the Android
 * Auto tree's "Collection Radio" play).
 */
@Composable
private fun CarCollectionRadioTile(onPlay: () -> Unit, width: Dp = CarCardWidth) {
    // The shared card box, so the tile's width matches the playlist cards beside it.
    com.parachord.android.ui.components.ParachordCardBox(width = width, onClick = onPlay) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(FillAccent),
            contentAlignment = Alignment.Center,
        ) {
            ParachordIcon(AppIcon.Radio, contentDescription = null, size = 48.dp, tint = OnFill)
        }
        Text(
            text = HomeCopy.COLLECTION_RADIO,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text(
            text = "Everything you have, shuffled",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

// ── Page states ───────────────────────────────────────────────────

/** The car home's skeleton while nothing has loaded. */
@Composable
private fun CarPageSkeleton() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "car:skeleton:top:header") { SectionHeader(title = RecommendationCopy.TITLE) }
        carSkeletonCards("car:skeleton:songs", lines = 2)
        carSkeletonCards("car:skeleton:albums-page", lines = 3)
        carSkeletonCards("car:skeleton:artists-page", lines = 1)
    }
}

/** A centered message with one (optionally disabled) button — the phone page's `PageMessage` shape. */
@Composable
private fun CarPageMessage(
    title: String,
    body: String,
    buttonText: String,
    buttonEnabled: Boolean = true,
    onButton: () -> Unit,
    hint: String? = null,
) {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontSize = 20.sp,
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
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onButton,
                enabled = buttonEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = FillAccent, contentColor = OnFill),
                shape = RoundedCornerShape(DesignTokens.RADIUS_BUTTON.dp),
            ) { Text(buttonText, fontSize = 14.sp) }
            if (hint != null && !buttonEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = hint,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
