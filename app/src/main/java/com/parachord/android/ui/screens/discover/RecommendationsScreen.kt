package com.parachord.android.ui.screens.discover

import com.parachord.android.resolver.trackKey
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.components.hapticCombinedClickable
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.koin.androidx.compose.koinViewModel
import com.parachord.shared.model.RecommendedArtist
import com.parachord.shared.model.RecommendedTrack
import com.parachord.shared.model.Resource
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.shimmerBrush
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.components.TrackRow

private val recommendationsTabs = listOf("Artists", "Songs")

// Source filter colors matching desktop:
// ListenBrainz = orange, Last.fm = red, All = amber
private val FilterColorAll = Color(0xFFF59E0B)         // amber-500
private val FilterColorListenBrainz = Color(0xFFF97316) // orange-500
private val FilterColorLastFm = Color(0xFFDC2626)       // red-600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationsScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RecommendationsViewModel = koinViewModel(),
) {
    val recommendedArtists by viewModel.recommendedArtists.collectAsState()
    val recommendedTracks by viewModel.recommendedTracks.collectAsState()
    val sourceFilter by viewModel.sourceFilter.collectAsState()
    val sourceCounts by viewModel.sourceCounts.collectAsState()
    val trackResolvers by viewModel.trackResolvers.collectAsState()
    val trackResolverConfidences by viewModel.trackResolverConfidences.collectAsState()

    // Auto-refresh when returning to this screen
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose { }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "RECOMMENDATIONS",
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
            windowInsets = WindowInsets(0),
        )

        // Source filter chips (matching desktop: All | ListenBrainz | Last.fm)
        SourceFilterBar(
            currentFilter = sourceFilter,
            counts = sourceCounts,
            onFilterChange = viewModel::setSourceFilter,
        )

        SwipeableTabLayout(tabs = recommendationsTabs) { page ->
            when (page) {
                0 -> ArtistsTab(
                    artists = recommendedArtists,
                    onRefresh = viewModel::refresh,
                    onArtistClick = onNavigateToArtist,
                    onPlayTopSongs = { viewModel.playArtistTopSongs(it) },
                    onQueueTopSongs = { viewModel.queueArtistTopSongs(it) },
                    onToggleArtistCollection = { name, imageUrl, inCol ->
                        viewModel.toggleArtistCollection(name, imageUrl, inCol)
                    },
                )
                1 -> SongsTab(
                    tracks = recommendedTracks,
                    trackResolvers = trackResolvers,
                    trackResolverConfidences = trackResolverConfidences,
                    onRefresh = viewModel::refresh,
                    onPlayTrack = viewModel::playTrack,
                    onTrackVisible = viewModel::resolveVisibleTrack,
                )
            }
        }
    }
}

// ---------- Source Filter Bar ----------

@Composable
private fun SourceFilterBar(
    currentFilter: String,
    counts: SourceCounts,
    onFilterChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SourceFilterChip(
            label = "All",
            count = counts.total,
            selected = currentFilter == "all",
            activeColor = FilterColorAll,
            onClick = { onFilterChange("all") },
        )
        if (counts.listenbrainz > 0) {
            SourceFilterChip(
                label = "ListenBrainz",
                count = counts.listenbrainz,
                selected = currentFilter == "listenbrainz",
                activeColor = FilterColorListenBrainz,
                onClick = { onFilterChange("listenbrainz") },
            )
        }
        if (counts.lastfm > 0) {
            SourceFilterChip(
                label = "Last.fm",
                count = counts.lastfm,
                selected = currentFilter == "lastfm",
                activeColor = FilterColorLastFm,
                onClick = { onFilterChange("lastfm") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = if (count > 0) "$label ($count)" else label,
                style = MaterialTheme.typography.labelMedium,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = activeColor,
            selectedLabelColor = Color.White,
        ),
    )
}

// ---------- Artists Tab ----------

@Composable
private fun ArtistsTab(
    artists: Resource<List<RecommendedArtist>>,
    onRefresh: () -> Unit,
    onArtistClick: (String) -> Unit = {},
    onPlayTopSongs: (String) -> Unit = {},
    onQueueTopSongs: (String) -> Unit = {},
    onToggleArtistCollection: (name: String, imageUrl: String?, isInCollection: Boolean) -> Unit = { _, _, _ -> },
) {
    when (artists) {
        is Resource.Loading -> {
            // Shimmer grid placeholder
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(12) {
                    // Sized like RecommendedArtistItem (a 96dp square cover with
                    // 12dp corners, then the name), and shimmering: the boxes
                    // used to have no fill, so the tab looked blank while it
                    // loaded (desktop parachord#1104).
                    val brush = shimmerBrush()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(brush),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .width(64.dp)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(brush),
                        )
                    }
                }
            }
        }
        is Resource.Error -> ErrorState(message = artists.message, onRetry = onRefresh)
        is Resource.Success -> {
            if (artists.data.isEmpty()) {
                EmptyState("No recommendations yet.\nListen to more music to get personalized suggestions!")
            } else {
                Column {
                    Text(
                        text = "Based on your listening",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(artists.data, key = { it.name }) { artist ->
                            RecommendedArtistItem(
                                artist = artist,
                                onClick = { onArtistClick(artist.name) },
                                onPlayTopSongs = { onPlayTopSongs(artist.name) },
                                onQueueTopSongs = { onQueueTopSongs(artist.name) },
                                onGoToArtist = { onArtistClick(artist.name) },
                                onToggleCollection = { onToggleArtistCollection(artist.name, artist.imageUrl, false) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendedArtistItem(
    artist: RecommendedArtist,
    onClick: () -> Unit = {},
    onPlayTopSongs: () -> Unit = {},
    onQueueTopSongs: () -> Unit = {},
    onGoToArtist: () -> Unit = {},
    onToggleCollection: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val shareArtist = com.parachord.android.share.rememberShareArtist()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .hapticCombinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true },
            ),
    ) {
        AlbumArtCard(
            artworkUrl = artist.imageUrl,
            size = 96.dp,
            cornerRadius = 12.dp,
            placeholderName = artist.name,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val reason = artist.reason
        if (reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showMenu) {
        com.parachord.android.ui.components.ArtistContextMenu(
            artistName = artist.name,
            imageUrl = artist.imageUrl,
            isInCollection = false,
            onDismiss = { showMenu = false },
            onPlayTopSongs = onPlayTopSongs,
            onQueueTopSongs = onQueueTopSongs,
            onGoToArtist = onGoToArtist,
            onToggleCollection = onToggleCollection,
            onShare = { shareArtist(artist.name, artist.imageUrl) },
        )
    }
}

// ---------- Songs Tab ----------

@Composable
private fun SongsTab(
    tracks: Resource<List<RecommendedTrack>>,
    trackResolvers: Map<String, List<String>>,
    trackResolverConfidences: Map<String, Map<String, Float>>,
    onRefresh: () -> Unit,
    onPlayTrack: (RecommendedTrack) -> Unit = {},
    onTrackVisible: (RecommendedTrack) -> Unit = {},
) {
    when (tracks) {
        is Resource.Loading -> {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(10) { ShimmerTrackRow(modifier = Modifier.padding(horizontal = 16.dp)) }
            }
        }
        is Resource.Error -> ErrorState(message = tracks.message, onRetry = onRefresh)
        is Resource.Success -> {
            if (tracks.data.isEmpty()) {
                EmptyState("No track recommendations yet.\nListen to more music to get personalized suggestions!")
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(tracks.data, key = { "${it.title}-${it.artist}" }) { track ->
                        val resolverKey = trackKey(track.title, track.artist)
                        // Visibility-scoped resolution (#177): a row resolves once it
                        // composes (viewport + overscan). The shared cache dedups, so
                        // scrolling back or reopening the screen fires no new searches.
                        LaunchedEffect(resolverKey) { onTrackVisible(track) }
                        TrackRow(
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            artworkUrl = track.artworkUrl,
                            duration = track.duration,
                            // Content resolver badges (Spotify, Apple Music, …) from the
                            // shared resolver cache — NOT metadata source labels.
                            resolvers = trackResolvers[resolverKey],
                            resolverConfidences = trackResolverConfidences[resolverKey],
                            onClick = { onPlayTrack(track) },
                        )
                    }
                }
            }
        }
    }
}

// ---------- Shared States ----------

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) {
                Text("Try Again")
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}
