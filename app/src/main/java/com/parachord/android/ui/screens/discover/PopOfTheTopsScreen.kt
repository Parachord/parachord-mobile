package com.parachord.android.ui.screens.discover

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.parachord.android.ui.components.AlbumContextMenu
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.components.hapticCombinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.ChartSong
import com.parachord.shared.model.CHARTS_COUNTRIES
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.components.TrackRow

private val OrangeAccent = Color(0xFFF97316)

private val HeaderGradient = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFFF97316), // orange
        Color(0xFFEC4899), // pink
        Color(0xFF8B5CF6), // purple
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PopOfTheTopsScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PopOfTheTopsViewModel = koinViewModel(),
) {
    val albums by viewModel.albums.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val albumsLoading by viewModel.albumsLoading.collectAsState()
    val songsLoading by viewModel.songsLoading.collectAsState()
    val selectedCountry by viewModel.selectedCountry.collectAsState()
    val songsSource by viewModel.songsSource.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
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
                    text = "POP OF THE TOPS",
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

        // Gradient header
        ChartsHeader(
            albumCount = albums.size,
            songCount = songs.size,
        )

        SwipeableTabLayout(tabs = listOf("Albums", "Songs")) { page ->
            when (page) {
                0 -> AlbumsTab(
                    albums = viewModel.filterAlbums(albums),
                    loading = albumsLoading,
                    selectedCountry = selectedCountry,
                    searchQuery = searchQuery,
                    onCountryChange = viewModel::setCountry,
                    onSearchQueryChange = viewModel::setSearchQuery,
                    onAlbumClick = { album -> onNavigateToAlbum(album.title, album.artist) },
                    onNavigateToArtist = onNavigateToArtist,
                    onQueueAlbum = viewModel::queueAlbumByName,
                    onAddAlbumToCollection = viewModel::addAlbumToCollection,
                )
                1 -> SongsTab(
                    songs = viewModel.filterSongs(songs),
                    loading = songsLoading,
                    selectedCountry = selectedCountry,
                    songsSource = songsSource,
                    searchQuery = searchQuery,
                    trackResolvers = trackResolvers,
                    trackResolverConfidences = trackResolverConfidences,
                    onCountryChange = viewModel::setCountry,
                    onSourceChange = viewModel::setSongsSource,
                    onSearchQueryChange = viewModel::setSearchQuery,
                    onSongClick = { song, allSongs -> viewModel.playSong(song, allSongs) },
                    onArtistClick = onNavigateToArtist,
                )
            }
        }
    }
}

// ── Header ──────────────────────────────────────────────────────────

@Composable
private fun ChartsHeader(albumCount: Int, songCount: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeaderGradient)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .animateContentSize(),
    ) {
        Column {
            Text(
                text = "What's trending around the world",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.9f),
            )
            if (albumCount > 0 || songCount > 0) {
                Text(
                    text = buildString {
                        if (albumCount > 0) append("$albumCount albums")
                        if (albumCount > 0 && songCount > 0) append("  ·  ")
                        if (songCount > 0) append("$songCount songs")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ── Filter Bar ──────────────────────────────────────────────────────

@Composable
private fun ChartsFilterBar(
    selectedCountry: String,
    onCountryChange: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    songsSource: String? = null, // null = albums tab (no source selector)
    onSourceChange: ((String) -> Unit)? = null,
    showGlobalOption: Boolean = false,
) {
    var countryDropdownOpen by remember { mutableStateOf(false) }
    var sourceDropdownOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Country dropdown
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .hapticClickable { countryDropdownOpen = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val countryName = if (showGlobalOption && selectedCountry.isEmpty()) {
                        "Global"
                    } else {
                        CHARTS_COUNTRIES.find { it.code == selectedCountry }?.name ?: "United States"
                    }
                    Text(
                        text = countryName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = countryDropdownOpen,
                    onDismissRequest = { countryDropdownOpen = false },
                ) {
                    if (showGlobalOption) {
                        DropdownMenuItem(
                            text = { Text("Global") },
                            onClick = {
                                onCountryChange("")
                                countryDropdownOpen = false
                            },
                            leadingIcon = {
                                if (selectedCountry.isEmpty()) {
                                    Text("✓", color = OrangeAccent)
                                }
                            },
                        )
                    }
                    CHARTS_COUNTRIES.forEach { country ->
                        DropdownMenuItem(
                            text = { Text(country.name) },
                            onClick = {
                                onCountryChange(country.code)
                                countryDropdownOpen = false
                            },
                            leadingIcon = {
                                if (selectedCountry == country.code) {
                                    Text("✓", color = OrangeAccent)
                                }
                            },
                        )
                    }
                }
            }

            // Source dropdown (Songs tab only)
            if (songsSource != null && onSourceChange != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .hapticClickable { sourceDropdownOpen = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (songsSource == "lastfm") "Last.fm" else "Apple Music",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = sourceDropdownOpen,
                        onDismissRequest = { sourceDropdownOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Apple Music") },
                            onClick = {
                                onSourceChange("apple")
                                sourceDropdownOpen = false
                            },
                            leadingIcon = {
                                if (songsSource == "apple") {
                                    Text("✓", color = OrangeAccent)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Last.fm") },
                            onClick = {
                                onSourceChange("lastfm")
                                sourceDropdownOpen = false
                            },
                            leadingIcon = {
                                if (songsSource == "lastfm") {
                                    Text("✓", color = OrangeAccent)
                                }
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Search toggle
            IconButton(onClick = { searchOpen = !searchOpen }) {
                Icon(
                    if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search",
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Expandable search field
        if (searchOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear",
                        modifier = Modifier
                            .size(18.dp)
                            .hapticClickable { onSearchQueryChange("") },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Albums Tab ───────────────────────────────────────────────────────

@Composable
private fun AlbumsTab(
    albums: List<ChartAlbum>,
    loading: Boolean,
    selectedCountry: String,
    searchQuery: String,
    onCountryChange: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onAlbumClick: (ChartAlbum) -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    onQueueAlbum: (albumTitle: String, albumArtist: String) -> Unit = { _, _ -> },
    onAddAlbumToCollection: (title: String, artist: String, artworkUrl: String?) -> Unit = { _, _, _ -> },
) {
    val shareAlbumLite = com.parachord.android.share.rememberShareAlbumLite()
    Column(modifier = Modifier.fillMaxSize()) {
        ChartsFilterBar(
            selectedCountry = selectedCountry,
            onCountryChange = onCountryChange,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
        )

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OrangeAccent)
            }
        } else if (albums.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (searchQuery.isNotBlank()) "No results for \"$searchQuery\"" else "No albums available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(albums, key = { it.id }) { album ->
                    var showMenu by remember { mutableStateOf(false) }
                    AlbumGridItem(
                        album = album,
                        onClick = { onAlbumClick(album) },
                        onLongClick = { showMenu = true },
                    )
                    if (showMenu) {
                        AlbumContextMenu(
                            albumTitle = album.title,
                            artistName = album.artist,
                            artworkUrl = album.artworkUrl,
                            isInCollection = false,
                            onDismiss = { showMenu = false },
                            onQueueAlbum = { onQueueAlbum(album.title, album.artist) },
                            onGoToAlbum = { onAlbumClick(album) },
                            onGoToArtist = { onNavigateToArtist(album.artist) },
                            onToggleCollection = {
                                onAddAlbumToCollection(album.title, album.artist, album.artworkUrl)
                            },
                            onShare = { shareAlbumLite(album.title, album.artist, album.artworkUrl) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumGridItem(album: ChartAlbum, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .hapticCombinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            AlbumArtCard(
                artworkUrl = album.artworkUrl,
                artist = album.artist,
                album = album.title,
                size = 180.dp,
                cornerRadius = 8.dp,
                placeholderName = album.title,
            )
            // Rank badge
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .align(Alignment.TopStart),
            ) {
                Text(
                    text = "#${album.rank}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

// ── Songs Tab ────────────────────────────────────────────────────────

@Composable
private fun SongsTab(
    songs: List<ChartSong>,
    loading: Boolean,
    selectedCountry: String,
    songsSource: String,
    searchQuery: String,
    trackResolvers: Map<String, List<String>>,
    trackResolverConfidences: Map<String, Map<String, Float>> = emptyMap(),
    onCountryChange: (String) -> Unit,
    onSourceChange: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSongClick: (ChartSong, List<ChartSong>) -> Unit,
    onArtistClick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ChartsFilterBar(
            selectedCountry = selectedCountry,
            onCountryChange = onCountryChange,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            songsSource = songsSource,
            onSourceChange = onSourceChange,
            showGlobalOption = songsSource == "lastfm",
        )

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OrangeAccent)
            }
        } else if (songs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (searchQuery.isNotBlank()) "No results for \"$searchQuery\"" else "No songs available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(songs, key = { it.id }) { song ->
                    val key = "${song.title.lowercase().trim()}|${song.artist.lowercase().trim()}"
                    TrackRow(
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        artworkUrl = song.artworkUrl,
                        trackNumber = song.rank,
                        resolvers = trackResolvers[key]?.ifEmpty { null },
                        resolverConfidences = trackResolverConfidences[key],
                        onClick = { onSongClick(song, songs) },
                    )
                }
            }
        }
    }
}

private fun formatListeners(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}.${(count % 1_000_000) / 100_000}M"
    count >= 1_000 -> "${count / 1_000}.${(count % 1_000) / 100}K"
    else -> "$count"
}
