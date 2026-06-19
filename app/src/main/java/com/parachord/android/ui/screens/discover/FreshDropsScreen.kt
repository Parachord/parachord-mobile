package com.parachord.android.ui.screens.discover

import com.parachord.android.ui.screens.artist.releaseTypeBadgeColor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.parachord.android.ui.components.hapticClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WaterDrop
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
import com.parachord.android.data.repository.FreshDrop
import com.parachord.android.data.repository.displayDate
import com.parachord.android.data.repository.isUpcoming
import com.parachord.shared.model.Resource
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.SpinningRefreshIcon
import com.parachord.android.ui.components.shimmerBrush

// Desktop gradient: emerald → teal → cyan
private val HeaderGradient = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF10B981), // emerald-500
        Color(0xFF14B8A6), // teal-500
        Color(0xFF06B6D4), // cyan-500
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreshDropsScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: FreshDropsViewModel = koinViewModel(),
) {
    val releasesResource by viewModel.releases.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterType by viewModel.filterType.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var searchOpen by remember { mutableStateOf(false) }

    // Auto-refresh when returning to this screen if cache is stale
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose { }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "FRESH DROPS",
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
        FreshDropsHeader(
            releaseCount = (releasesResource as? Resource.Success)?.data?.size ?: 0,
        )

        // Sticky filter bar: filter chips + search + refresh
        FreshDropsFilterBar(
            searchOpen = searchOpen,
            onToggleSearch = { searchOpen = !searchOpen },
            searchQuery = searchQuery,
            onSearchQueryChange = viewModel::setSearchQuery,
            filterType = filterType,
            onFilterTypeChange = viewModel::setFilterType,
            onRefresh = viewModel::refresh,
            isRefreshing = isRefreshing,
        )

        // Content
        when (val resource = releasesResource) {
            is Resource.Loading -> {
                // Animated shimmer skeleton rows
                val brush = shimmerBrush()
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
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(brush),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .width(160.dp)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(brush),
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(brush),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .width(80.dp)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(brush),
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
                val displayReleases = viewModel.filterReleases(resource.data)
                if (displayReleases.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "No results for \"$searchQuery\""
                            } else if (filterType != "all") {
                                "No ${filterType}s found"
                            } else {
                                "No new releases found.\nAdd artists to your library or connect Last.fm/ListenBrainz."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                } else {
                    ReleaseList(
                        releases = displayReleases,
                        onReleaseClick = { release ->
                            onNavigateToAlbum(release.title, release.artist)
                        },
                        onArtistClick = { release ->
                            onNavigateToArtist(release.artist)
                        },
                    )
                }
            }
        }
    }
}

// ---------- Header ----------

@Composable
private fun FreshDropsHeader(releaseCount: Int) {
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
                imageVector = Icons.Filled.WaterDrop,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color.White.copy(alpha = 0.9f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "New releases from artists you listen to",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                )
                if (releaseCount > 0) {
                    Text(
                        text = "$releaseCount releases",
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
private fun FreshDropsFilterBar(
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filterType: String,
    onFilterTypeChange: (String) -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean = false,
) {
    val filters = listOf("all" to "All", "album" to "Albums", "ep" to "EPs", "single" to "Singles")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Filter chips row with search + refresh icons on the right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filters.forEach { (key, label) ->
                    // Per-type color so the selected filter chip matches the row
                    // badge (#247); "all" falls through to gray.
                    val chipColor = releaseTypeBadgeColor(key)
                    FilterChip(
                        selected = filterType == key,
                        onClick = { onFilterTypeChange(key) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = chipColor.copy(alpha = 0.20f),
                            selectedLabelColor = chipColor,
                        ),
                    )
                }
            }
            IconButton(onClick = onToggleSearch) {
                Icon(
                    if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search",
                    modifier = Modifier.size(20.dp),
                )
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
                                    text = "Search releases or artists\u2026",
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

// ---------- Release List ----------

@Composable
private fun ReleaseList(
    releases: List<FreshDrop>,
    onReleaseClick: (FreshDrop) -> Unit,
    onArtistClick: (FreshDrop) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(releases, key = { "${it.artist}|${it.title}" }) { release ->
            FreshDropRow(
                release = release,
                onReleaseClick = { onReleaseClick(release) },
                onArtistClick = { onArtistClick(release) },
            )
        }
    }
}

@Composable
private fun FreshDropRow(
    release: FreshDrop,
    onReleaseClick: () -> Unit,
    onArtistClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(onClick = onReleaseClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AlbumArtCard(
            artworkUrl = release.albumArt,
            size = 80.dp,
            cornerRadius = 8.dp,
            placeholderName = release.title,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Title
            Text(
                text = release.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Artist (NOT tappable — the whole row navigates to the album.
            // Nested clickables combined with progressive list updates can fire
            // both the row click AND the text click with stale closures,
            // navigating to one release's album and a different release's
            // artist. Users can open the artist from the album page.)
            Text(
                text = release.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Date + type badge row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Release type badge — shared desktop-colored map, matches the
                // filter chips below (#247). Was a divergent purple/cyan/amber set.
                val badgeColor = releaseTypeBadgeColor(release.releaseType)
                Text(
                    text = release.releaseType.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = badgeColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                // Date
                if (release.displayDate.isNotBlank()) {
                    Text(
                        text = release.displayDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (release.isUpcoming) {
                            Color(0xFF10B981) // emerald for upcoming
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                    )
                }
            }
            // Source indicator
            if (release.artistSource.isNotBlank()) {
                Text(
                    text = when (release.artistSource) {
                        "library" -> "From your library"
                        "history" -> "From your listening history"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}
