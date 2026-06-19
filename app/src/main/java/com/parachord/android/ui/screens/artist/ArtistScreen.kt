package com.parachord.android.ui.screens.artist

import com.parachord.android.resolver.trackKey
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.data.db.entity.TrackEntity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.AlbumArtCardFill
import com.parachord.android.ui.components.AlbumContextMenu
import com.parachord.android.ui.components.ArtistContextMenu
import com.parachord.android.ui.components.FaceAwareImage
import com.parachord.android.ui.components.hapticCombinedClickable
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.ShimmerDiscographyCard
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.shimmerBrush
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.components.rememberTrackContextMenuState
import com.parachord.android.data.metadata.AlbumSearchResult
import com.parachord.android.data.metadata.SimilarArtist
import com.parachord.android.data.metadata.TrackSearchResult
import com.parachord.android.data.repository.ConcertEvent
import com.parachord.android.data.repository.displayDate
import com.parachord.android.data.repository.displayTime
import com.parachord.android.data.repository.locationString
import com.parachord.shared.model.Resource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArtistScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    initialTab: String? = null,
    modifier: Modifier = Modifier,
    viewModel: ArtistViewModel = koinViewModel(),
) {
    val artistInfo by viewModel.artistInfo.collectAsStateWithLifecycle()
    val topTracks by viewModel.topTracks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val albumsLoading by viewModel.albumsLoading.collectAsStateWithLifecycle()
    val albumsError by viewModel.albumsError.collectAsStateWithLifecycle()
    val trackResolvers by viewModel.trackResolvers.collectAsStateWithLifecycle()
    val trackResolverConfidences by viewModel.trackResolverConfidences.collectAsStateWithLifecycle()
    val trackArtwork by viewModel.trackArtwork.collectAsStateWithLifecycle()
    val isSaved by viewModel.isSaved.collectAsStateWithLifecycle()
    val tourDates by viewModel.tourDates.collectAsStateWithLifecycle()
    val isOnTour by viewModel.isOnTour.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val contextMenuState = rememberTrackContextMenuState()

    // Context menu host
    TrackContextMenuHost(
        state = contextMenuState,
        playlists = playlists,
        onPlayNext = { viewModel.playNext(it) },
        onAddToQueue = { viewModel.addToQueue(it) },
        onAddToPlaylist = { playlist, track -> viewModel.addToPlaylist(playlist, track) },
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToAlbum = onNavigateToAlbum,
        onToggleCollection = { track, isInCollection ->
            if (!isInCollection) viewModel.addToCollection(track)
        },
    )

    val density = LocalDensity.current

    // Track the max (expanded) height of the hero image in pixels
    var imageMaxHeightPx by remember { mutableFloatStateOf(0f) }
    // Current collapse offset: 0 = fully expanded, imageMaxHeightPx = fully collapsed
    var collapseOffset by remember { mutableFloatStateOf(0f) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (imageMaxHeightPx <= 0f) return Offset.Zero
                val delta = available.y
                if (delta < 0f) {
                    // Scrolling up — collapse the image first
                    val oldOffset = collapseOffset
                    collapseOffset = (collapseOffset - delta).coerceIn(0f, imageMaxHeightPx)
                    val consumed = oldOffset - collapseOffset
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (imageMaxHeightPx <= 0f) return Offset.Zero
                val delta = available.y
                if (delta > 0f) {
                    // Scrolling down and child didn't consume it — expand the image
                    val oldOffset = collapseOffset
                    collapseOffset = (collapseOffset - delta).coerceIn(0f, imageMaxHeightPx)
                    val consumed = oldOffset - collapseOffset
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = (artistInfo?.name ?: viewModel.artistName).uppercase(),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { viewModel.toggleSaved() }) {
                    Icon(
                        imageVector = if (isSaved) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (isSaved) "Remove from collection" else "Save to collection",
                        tint = if (isSaved) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            windowInsets = WindowInsets(0),
        )

        if (isLoading) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                repeat(5) { ShimmerTrackRow() }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection),
            ) {
                // Hero image — show skeleton placeholder immediately, then the
                // real image eases in on top via FaceAwareImage's built-in Coil
                // crossfade. Visible while waiting for artistInfo OR when we have an image.
                val imageUrl = artistInfo?.imageUrl
                val hasImage = !imageUrl.isNullOrBlank()

                val visibleHeightDp = with(density) {
                    (imageMaxHeightPx - collapseOffset).coerceAtLeast(0f).toDp()
                }
                if (hasImage || artistInfo == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (imageMaxHeightPx > 0f) Modifier.height(visibleHeightDp)
                                else Modifier
                            )
                            .clipToBounds(),
                    ) {
                        // Skeleton shimmer — always behind, covered by the image once loaded
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(shimmerBrush())
                                .onSizeChanged { size ->
                                    if (size.height > 0 && imageMaxHeightPx == 0f) {
                                        imageMaxHeightPx = size.height.toFloat()
                                    }
                                },
                        )
                        if (hasImage) {
                            FaceAwareImage(
                                imageUrl = imageUrl!!,
                                contentDescription = "Artist image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .onSizeChanged { size ->
                                        if (size.height > 0 && imageMaxHeightPx == 0f) {
                                            imageMaxHeightPx = size.height.toFloat()
                                        }
                                    },
                                loading = {
                                    // Skeleton behind shows through
                                },
                                error = {
                                    // Skeleton behind shows through on error too
                                },
                            )
                        }
                    }
                }

                // Tabs + pager — show "On Tour" tab when artist has upcoming dates
                // Desktop puts "On Tour" last: Music | Biography | Related Artists | On Tour
                // Appending at end avoids shifting existing tab indices when it appears
                val tabs = remember(isOnTour) {
                    buildList {
                        add("Discography")
                        add("Top Tracks")
                        add("Biography")
                        add("Related Artists")
                        if (isOnTour) add("On Tour")
                    }
                }

                val initialPage = initialTab?.let { tabName ->
                    tabs.indexOf(tabName).takeIf { it >= 0 }
                } ?: 0

                SwipeableTabLayout(
                    tabs = tabs,
                    modifier = Modifier.fillMaxSize(),
                    initialPage = initialPage,
                ) { page ->
                    val tabName = tabs.getOrNull(page) ?: return@SwipeableTabLayout
                    when (tabName) {
                        "Discography" -> DiscographyTab(
                            albums = albums,
                            // Use the discography-specific flag, NOT the
                            // page-wide [isLoading] — the latter flips
                            // false as soon as ANY section completes
                            // (artist info / top tracks), causing the
                            // empty state to flash before MusicBrainz
                            // returns the actual album list.
                            isLoading = albumsLoading,
                            isError = albumsError,
                            onRetry = { viewModel.reloadDiscography() },
                            onNavigateToAlbum = onNavigateToAlbum,
                            onNavigateToArtist = onNavigateToArtist,
                            onQueueAlbum = { title, artist -> viewModel.queueAlbumByName(title, artist) },
                            onAddAlbumToCollection = { title, artist, artworkUrl, year ->
                                viewModel.addAlbumToCollection(title, artist, artworkUrl, year)
                            },
                            onRemoveAlbumFromCollection = { title, artist ->
                                viewModel.removeAlbumFromCollection(title, artist)
                            },
                        )
                        "Top Tracks" -> TopTracksTab(
                            topTracks = topTracks,
                            isLoading = isLoading,
                            trackResolvers = trackResolvers,
                            trackResolverConfidences = trackResolverConfidences,
                            trackArtwork = trackArtwork,
                            onPlayTopTrack = { index -> viewModel.playTopTrack(index) },
                            onTrackLongClick = { track ->
                                val entity = viewModel.trackSearchResultToEntity(track)
                                contextMenuState.show(
                                    TrackContextInfo(
                                        title = track.title,
                                        artist = track.artist,
                                        album = track.album,
                                        artworkUrl = track.artworkUrl,
                                        duration = track.duration,
                                    ),
                                    entity,
                                )
                            },
                        )
                        "On Tour" -> OnTourTab(tourDates = tourDates)
                        "Biography" -> BiographyTab(
                            bio = artistInfo?.bio,
                            bioSource = artistInfo?.bioSource,
                            bioUrl = artistInfo?.bioUrl,
                            tags = artistInfo?.tags ?: emptyList(),
                            provider = artistInfo?.provider,
                        )
                        "Related Artists" -> RelatedArtistsTab(
                            similarArtists = artistInfo?.similarArtists ?: emptyList(),
                            onNavigateToArtist = onNavigateToArtist,
                            onPlayTopSongs = { viewModel.playArtistTopSongs(it) },
                            onQueueTopSongs = { viewModel.queueArtistTopSongs(it) },
                            onToggleCollection = { name, imageUrl, inCollection ->
                                viewModel.toggleArtistCollection(name, imageUrl)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Release type filters for the discography tab.
 * "all" shows everything; other values match AlbumSearchResult.releaseType.
 */
private data class ReleaseFilter(val key: String, val label: String)

private val RELEASE_FILTERS = listOf(
    ReleaseFilter("all", "All"),
    ReleaseFilter("album", "Studio Albums"),
    ReleaseFilter("single", "Singles"),
    ReleaseFilter("ep", "EPs"),
    ReleaseFilter("live", "Live"),
    ReleaseFilter("compilation", "Compilations"),
)

/** Per-release-type color — desktop `badgeStyles` map (app.js), the source of
 *  truth (#247). Shared by the artist discography filters/badges AND Fresh Drops. */
fun releaseTypeBadgeColor(type: String?): Color = when (type?.lowercase()) {
    "album" -> Color(0xFF6366F1)        // indigo
    "ep" -> Color(0xFFA855F7)           // purple
    "single" -> Color(0xFFDB2777)       // rose
    "live" -> Color(0xFFD97706)         // amber
    "compilation" -> Color(0xFF0EB3A0)  // teal
    else -> Color(0xFF9CA3AF)           // gray
}

@Composable
private fun DiscographyTab(
    albums: List<AlbumSearchResult>,
    isLoading: Boolean = false,
    isError: Boolean = false,
    onRetry: () -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    onQueueAlbum: (title: String, artist: String) -> Unit = { _, _ -> },
    onAddAlbumToCollection: (title: String, artist: String, artworkUrl: String?, year: Int?) -> Unit = { _, _, _, _ -> },
    onRemoveAlbumFromCollection: (title: String, artist: String) -> Unit = { _, _ -> },
) {
    var selectedFilter by remember { mutableStateOf("all") }
    val shareAlbumLite = com.parachord.android.share.rememberShareAlbumLite()

    // Calculate counts per type — only count albums with an explicit releaseType
    val typeCounts = remember(albums) {
        albums.filter { it.releaseType != null }
            .groupBy { it.releaseType!!.lowercase() }
            .mapValues { it.value.size }
    }

    val availableFilters = remember(albums, typeCounts) {
        val types = typeCounts.keys
        RELEASE_FILTERS.filter { it.key == "all" || it.key in types }
    }

    val filteredAlbums = remember(albums, selectedFilter) {
        if (selectedFilter == "all") albums
        else albums.filter { it.releaseType?.lowercase() == selectedFilter }
    }

    val cardBg = MaterialTheme.colorScheme.surfaceVariant

    // Show shimmer skeletons while albums are still loading
    val showAlbumSkeletons = albums.isEmpty() && isLoading

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Filter chips row (only show if we have more than one filter available)
        if (availableFilters.size > 1) {
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lazyItems(availableFilters, key = { it.key }) { filter ->
                        val chipColor = releaseTypeBadgeColor(
                            if (filter.key == "all") null else filter.key,
                        )
                        val count = if (filter.key == "all") null else typeCounts[filter.key]
                        val chipLabel = if (count != null) "${filter.label} ($count)" else filter.label
                        FilterChip(
                            selected = selectedFilter == filter.key,
                            onClick = { selectedFilter = filter.key },
                            label = { Text(chipLabel) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipColor.copy(alpha = 0.20f),
                                selectedLabelColor = chipColor,
                            ),
                        )
                    }
                }
            }
        }

        // Skeleton album cards while loading
        if (showAlbumSkeletons) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            repeat(2) {
                                Box(modifier = Modifier.weight(1f)) {
                                    ShimmerDiscographyCard()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (filteredAlbums.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    filteredAlbums.chunked(2).forEach { rowAlbums ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowAlbums.forEach { album ->
                                Box(modifier = Modifier.weight(1f)) {
                                    var showAlbumMenu by remember { mutableStateOf(false) }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(cardBg)
                                            .hapticCombinedClickable(
                                                onClick = { onNavigateToAlbum(album.title, album.artist) },
                                                onLongClick = { showAlbumMenu = true },
                                            )
                                            .padding(10.dp),
                                    ) {
                                        AlbumArtCardFill(
                                            artworkUrl = album.artworkUrl,
                                            modifier = Modifier.fillMaxWidth(),
                                            cornerRadius = 6.dp,
                                            elevation = 1.dp,
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = album.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            album.releaseType?.let { type ->
                                                val badgeColor = releaseTypeBadgeColor(type)
                                                Text(
                                                    text = type.uppercase(),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    letterSpacing = 0.5.sp,
                                                    color = badgeColor,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(badgeColor.copy(alpha = 0.18f))
                                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                                )
                                            }
                                            album.year?.let { year ->
                                                Text(
                                                    text = "$year",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                )
                                            }
                                        }
                                    }
                                    if (showAlbumMenu) {
                                        AlbumContextMenu(
                                            albumTitle = album.title,
                                            artistName = album.artist,
                                            artworkUrl = album.artworkUrl,
                                            isInCollection = false,
                                            onDismiss = { showAlbumMenu = false },
                                            onQueueAlbum = {
                                                showAlbumMenu = false
                                                onQueueAlbum(album.title, album.artist)
                                            },
                                            onGoToAlbum = {
                                                showAlbumMenu = false
                                                onNavigateToAlbum(album.title, album.artist)
                                            },
                                            onGoToArtist = {
                                                showAlbumMenu = false
                                                onNavigateToArtist(album.artist)
                                            },
                                            onToggleCollection = {
                                                showAlbumMenu = false
                                                onAddAlbumToCollection(album.title, album.artist, album.artworkUrl, album.year)
                                            },
                                            onShare = {
                                                shareAlbumLite(album.title, album.artist, album.artworkUrl)
                                            },
                                        )
                                    }
                                }
                            }
                            // Fill empty space if odd number
                            if (rowAlbums.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // A provider failed (e.g. MusicBrainz) → friendly error + retry, NOT the
        // misleading "No discography available" empty state.
        if (!isLoading && isError && filteredAlbums.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Couldn't load discography",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Something went wrong reaching the music database. Please try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("Try again") }
                }
            }
        } else if (!isLoading && filteredAlbums.isEmpty()) {
            // Genuinely no releases for this artist.
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No discography available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Dedicated tab for the artist's top tracks (matches desktop's "Top Tracks"
 * section). Tapping a row plays that track and queues the rest of the list,
 * so Next plays the track below it — same UX as album / playlist tap.
 */
@Composable
private fun TopTracksTab(
    topTracks: List<TrackSearchResult>,
    isLoading: Boolean = false,
    trackResolvers: Map<String, List<String>> = emptyMap(),
    trackResolverConfidences: Map<String, Map<String, Float>> = emptyMap(),
    trackArtwork: Map<String, String> = emptyMap(),
    onPlayTopTrack: (Int) -> Unit = {},
    onTrackLongClick: (TrackSearchResult) -> Unit = {},
) {
    val showSkeletons = topTracks.isEmpty() && isLoading

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (topTracks.isNotEmpty()) {
            itemsIndexed(topTracks) { index, track ->
                TrackRow(
                    title = track.title,
                    artist = track.album ?: "",
                    // Prefer resolved album art (#188) — Top Tracks arrive art-less
                    // from Last.fm; the resolver supplies real Apple Music / Spotify art.
                    artworkUrl = trackArtwork[trackKey(track.title, track.artist)] ?: track.artworkUrl,
                    resolvers = trackResolvers[trackKey(track.title, track.artist)]?.ifEmpty { null },
                    resolverConfidences = trackResolverConfidences[trackKey(track.title, track.artist)],
                    onClick = { onPlayTopTrack(index) },
                    onLongClick = { onTrackLongClick(track) },
                )
            }
        } else if (showSkeletons) {
            items(8) { ShimmerTrackRow() }
        } else {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No top tracks available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BiographyTab(
    bio: String?,
    bioSource: String?,
    bioUrl: String?,
    tags: List<String>,
    provider: String?,
) {
    val uriHandler = LocalUriHandler.current

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (!bio.isNullOrBlank()) {
            item {
                Text(
                    text = bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            // Bio source attribution with optional "Read more" link
            if (bioSource != null) {
                item {
                    val sourceName = when (bioSource) {
                        "wikipedia" -> "Wikipedia"
                        "discogs" -> "Discogs"
                        "lastfm" -> "Last.fm"
                        else -> bioSource
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "From $sourceName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (bioUrl != null) {
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                text = "Read more",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { uriHandler.openUri(bioUrl) },
                            )
                        }
                    }
                }
            }
        }

        if (tags.isNotEmpty()) {
            item {
                if (!bio.isNullOrBlank()) HorizontalDivider()
                SectionHeader("TAGS")
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { tag ->
                        AssistChip(
                            onClick = {},
                            label = { Text(tag) },
                        )
                    }
                }
            }
        }

        provider?.let { providers ->
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sources: ${providers.split("+").distinct().joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        if (bio.isNullOrBlank() && tags.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No biography available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelatedArtistsTab(
    similarArtists: List<SimilarArtist>,
    onNavigateToArtist: (String) -> Unit,
    onPlayTopSongs: (String) -> Unit = {},
    onQueueTopSongs: (String) -> Unit = {},
    onToggleCollection: (name: String, imageUrl: String?, isInCollection: Boolean) -> Unit = { _, _, _ -> },
) {
    val shareArtist = com.parachord.android.share.rememberShareArtist()

    if (similarArtists.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No related artists",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                count = similarArtists.size,
                key = { idx -> "similar-$idx-${similarArtists[idx].name}" },
            ) { idx ->
                val artist = similarArtists[idx]
                var showMenu by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .hapticCombinedClickable(
                            onClick = { onNavigateToArtist(artist.name) },
                            onLongClick = { showMenu = true },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (artist.imageUrl != null) {
                            AsyncImage(
                                model = artist.imageUrl,
                                contentDescription = artist.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                text = artist.name.take(1).uppercase(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (showMenu) {
                    com.parachord.android.ui.components.ArtistContextMenu(
                        artistName = artist.name,
                        imageUrl = artist.imageUrl,
                        isInCollection = false,
                        onDismiss = { showMenu = false },
                        onPlayTopSongs = { onPlayTopSongs(artist.name) },
                        onQueueTopSongs = { onQueueTopSongs(artist.name) },
                        onGoToArtist = { onNavigateToArtist(artist.name) },
                        onToggleCollection = { onToggleCollection(artist.name, artist.imageUrl, false) },
                        onShare = { shareArtist(artist.name, artist.imageUrl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnTourTab(
    tourDates: Resource<List<ConcertEvent>>,
) {
    val uriHandler = LocalUriHandler.current

    when (tourDates) {
        is Resource.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color(0xFF7C3AED),
                )
            }
        }
        is Resource.Error -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tourDates.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is Resource.Success -> {
            if (tourDates.data.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No upcoming tour dates",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    items(tourDates.data, key = { it.id }) { event ->
                        TourDateRow(
                            event = event,
                            onTicketClick = {
                                event.ticketUrl?.let { uriHandler.openUri(it) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TourDateRow(
    event: ConcertEvent,
    onTicketClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Date column
        Column(
            modifier = Modifier.width(56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val dateParts = event.date?.split("-")
            val month = dateParts?.getOrNull(1)?.toIntOrNull()?.let {
                java.time.Month.of(it).name.take(3)
            } ?: ""
            val day = dateParts?.getOrNull(2) ?: ""
            Text(
                text = month,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF10C9B4), // Concert teal
                letterSpacing = 0.5.sp,
            )
            Text(
                text = day,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val venueName = event.venueName
            if (venueName != null) {
                Text(
                    text = venueName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (event.locationString.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = event.locationString,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    if (event.displayTime.isNotBlank()) {
                        Text(
                            text = " · ${event.displayTime}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        // Ticket link
        if (event.ticketUrl != null) {
            IconButton(
                onClick = onTicketClick,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = "Tickets",
                    modifier = Modifier.size(18.dp),
                    tint = Color(0xFF10C9B4), // Concert teal
                )
            }
        }
    }
}
