package com.parachord.android.ui.screens.discover

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.parachord.android.ui.components.hapticClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.parachord.android.data.repository.CriticsPickAlbum
import com.parachord.shared.model.Resource
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.SpinningRefreshIcon

// Desktop gradient: amber → orange → red
private val HeaderGradient = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFFF59E0B), // amber-500
        Color(0xFFF97316), // orange-500
        Color(0xFFEF4444), // red-500
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CriticalDarlingsScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CriticalDarlingsViewModel = koinViewModel(),
) {
    val albumsResource by viewModel.albums.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var searchOpen by remember { mutableStateOf(false) }
    var sortDropdownOpen by remember { mutableStateOf(false) }

    // Auto-refresh when returning to this screen if cache is stale
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose { }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "CRITICAL DARLINGS",
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

        // Gradient header banner
        CriticsHeader(
            albumCount = (albumsResource as? Resource.Success)?.data?.size ?: 0,
        )

        // Sticky filter bar: search + sort + refresh
        CriticsFilterBar(
            searchOpen = searchOpen,
            onToggleSearch = { searchOpen = !searchOpen },
            searchQuery = searchQuery,
            onSearchQueryChange = viewModel::setSearchQuery,
            sortMode = sortMode,
            onSortModeChange = viewModel::setSortMode,
            sortDropdownOpen = sortDropdownOpen,
            onSortDropdownToggle = { sortDropdownOpen = it },
            onRefresh = viewModel::refresh,
            isRefreshing = isRefreshing,
        )

        // Content
        when (val resource = albumsResource) {
            is Resource.Loading -> {
                // Shimmer rows matching the list layout
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(6) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    ),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .width(160.dp)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        ),
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        ),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                        ),
                                )
                            }
                        }
                    }
                }
            }

            is Resource.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = resource.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = viewModel::refresh) {
                            Text("Try Again")
                        }
                    }
                }
            }

            is Resource.Success -> {
                val displayAlbums = viewModel.filterAndSort(resource.data)
                if (displayAlbums.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "No results for \"$searchQuery\""
                            } else {
                                "No critics' picks available"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    AlbumList(
                        albums = displayAlbums,
                        onAlbumClick = { album -> onNavigateToAlbum(album.title, album.artist) },
                        onArtistClick = { album -> onNavigateToArtist(album.artist) },
                    )
                }
            }
        }
    }
}

// ---------- Header ----------

@Composable
private fun CriticsHeader(albumCount: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeaderGradient)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.EmojiEvents,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color.White.copy(alpha = 0.9f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Top-rated albums from leading music publications",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                )
                if (albumCount > 0) {
                    Text(
                        text = "$albumCount albums",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

// ---------- Filter Bar ----------

@Composable
private fun CriticsFilterBar(
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    sortMode: String,
    onSortModeChange: (String) -> Unit,
    sortDropdownOpen: Boolean,
    onSortDropdownToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit = {},
    isRefreshing: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Icon row: search + sort + refresh
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search",
                    modifier = Modifier.size(20.dp),
                )
            }
            Box {
                IconButton(onClick = { onSortDropdownToggle(true) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "Sort",
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = sortDropdownOpen,
                    onDismissRequest = { onSortDropdownToggle(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text("Most Recent") },
                        onClick = {
                            onSortModeChange("recent")
                            onSortDropdownToggle(false)
                        },
                        leadingIcon = {
                            if (sortMode == "recent") {
                                Text("\u2713", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Artist A\u2013Z") },
                        onClick = {
                            onSortModeChange("artist")
                            onSortDropdownToggle(false)
                        },
                        leadingIcon = {
                            if (sortMode == "artist") {
                                Text("\u2713", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                }
            }
            SpinningRefreshIcon(
                isLoading = isRefreshing,
                onClick = onRefresh,
            )
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
                                    text = "Search albums or artists\u2026",
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

// ---------- Album List ----------

@Composable
private fun AlbumList(
    albums: List<CriticsPickAlbum>,
    onAlbumClick: (CriticsPickAlbum) -> Unit,
    onArtistClick: (CriticsPickAlbum) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            CriticsPickRow(
                album = album,
                onAlbumClick = { onAlbumClick(album) },
                onArtistClick = { onArtistClick(album) },
            )
        }
    }
}

@Composable
private fun CriticsPickRow(
    album: CriticsPickAlbum,
    onAlbumClick: () -> Unit,
    onArtistClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(onClick = onAlbumClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AlbumArtCard(
            artworkUrl = album.albumArt,
            artist = album.artist,
            album = album.title,
            size = 100.dp,
            cornerRadius = 8.dp,
            placeholderName = album.title,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.hapticClickable(onClick = onArtistClick),
            )
            if (album.blurb.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = album.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}
