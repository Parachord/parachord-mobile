package com.parachord.android.ui.screens.discover

import com.parachord.shared.metadata.hasArtistPage
import com.parachord.android.ui.components.EmptyState
import com.parachord.android.ui.components.ErrorState
import com.parachord.shared.ui.icons.AppIcon
import com.parachord.shared.ui.DesignTokens
import com.parachord.android.ui.components.PageHero
import com.parachord.shared.ui.UiMessages
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
        PageHero(
            page = DesignTokens.Page.CRITICAL_DARLINGS,
            line = com.parachord.shared.ui.PageHero.line(DesignTokens.Page.CRITICAL_DARLINGS, (albumsResource as? Resource.Success)?.data?.size),
            navigation = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            },
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
                ErrorState(title = resource.message, onRetry = viewModel::refresh)
            }

            is Resource.Success -> {
                val displayAlbums = viewModel.filterAndSort(resource.data)
                if (displayAlbums.isEmpty()) {
                    EmptyState(
                        title = if (searchQuery.isNotBlank()) {
                                UiMessages.noResultsFor(searchQuery)
                            } else {
                                UiMessages.CRITICS_PICKS_EMPTY
                            },
                        icon = if (searchQuery.isNotBlank()) AppIcon.Search else AppIcon.Music,
                    )
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
                modifier = if (hasArtistPage(album.artist)) Modifier.hapticClickable(onClick = onArtistClick) else Modifier,
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
