package com.parachord.android.ui.screens.discover

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.parachord.android.data.repository.ConcertEvent
import com.parachord.android.data.repository.displayDate
import com.parachord.android.data.repository.displayTime
import com.parachord.android.data.repository.locationString
import com.parachord.shared.model.Resource
import com.parachord.android.data.repository.TicketSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** Concert accent color — teal, matching the desktop's concert feature. */
private val ConcertTeal = Color(0xFF10C9B4)

/** Source-specific badge colors matching desktop */
private val TicketmasterColor = Color(0xFF026CDF)
private val SeatGeekColor = Color(0xFFFC4C02)

private val RADIUS_OPTIONS = listOf(10, 25, 50, 100, 200)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConcertsScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ConcertsViewModel = koinViewModel(),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val locationCity by viewModel.locationCity.collectAsStateWithLifecycle()
    val radiusMiles by viewModel.radiusMiles.collectAsStateWithLifecycle()
    val hasLocation by viewModel.hasLocation.collectAsStateWithLifecycle()
    val isDetectingLocation by viewModel.isDetectingLocation.collectAsStateWithLifecycle()
    val showGeoIpConfirm by viewModel.showGeoIpConfirm.collectAsStateWithLifecycle()
    var showLocationPicker by remember { mutableStateOf(false) }

    // Runtime COARSE-location permission request. Whether granted or denied, we
    // still call detectLocation() — it uses GPS when granted, geoIP when not.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        viewModel.detectLocation(userInitiated = true)
    }

    // Detect entry point: skip straight to detect if already granted, otherwise
    // prompt for COARSE permission first. Always user-initiated (a Detect tap), so
    // the geoIP fallback surfaces a confirmable suggestion (vs. the silent cold-
    // launch auto-detect, which only auto-commits a trustworthy GPS fix).
    val requestDetect: () -> Unit = {
        if (viewModel.hasLocationPermission()) {
            viewModel.detectLocation(userInitiated = true)
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    // When a geoIP fallback produced a confirmable suggestion, open the picker so
    // the user can tap it to confirm (GPS commits directly; geoIP confirms).
    LaunchedEffect(showGeoIpConfirm) {
        if (showGeoIpConfirm) {
            showLocationPicker = true
            viewModel.consumeGeoIpConfirm()
        }
    }

    // Close the picker as soon as a location is committed (GPS detect, manual pick,
    // or a confirmed geoIP suggestion) — otherwise a GPS-commit-via-Detect leaves
    // the modal open behind an updated location bar.
    LaunchedEffect(Unit) {
        viewModel.locationCommitted.collect { showLocationPicker = false }
    }

    // Auto-refresh when returning to this screen if cache is stale
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose { }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "CONCERTS",
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

        // Location bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLocationPicker = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = ConcertTeal,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isDetectingLocation -> "Detecting location…"
                    locationCity != null -> locationCity!!
                    else -> "Set your location"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (locationCity != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            if (isDetectingLocation) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = ConcertTeal,
                )
            } else {
                Text(
                    text = "${radiusMiles}mi",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Radius filter chips
        if (hasLocation) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(RADIUS_OPTIONS, key = { it }) { radius ->
                    FilterChip(
                        selected = radiusMiles == radius,
                        onClick = { viewModel.setRadius(radius) },
                        label = { Text("${radius}mi") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ConcertTeal.copy(alpha = 0.15f),
                            selectedLabelColor = ConcertTeal,
                        ),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Content
        if (!hasLocation && !isDetectingLocation) {
            // No location set — show prompt with detect button
            LocationDetectPrompt(
                onDetect = requestDetect,
                onPickManually = { showLocationPicker = true },
            )
        } else {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val state = events) {
                    is Resource.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = ConcertTeal)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Finding concerts from your artists…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    is Resource.Error -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.ConfirmationNumber,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    is Resource.Success -> {
                        if (state.data.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.ConfirmationNumber,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "No upcoming concerts found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "Add artists to your collection or connect Last.fm",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        } else {
                            ConcertEventList(
                                events = state.data,
                                onNavigateToArtist = onNavigateToArtist,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLocationPicker) {
        LocationSearchDialog(
            currentCity = locationCity,
            isDetecting = isDetectingLocation,
            onDetect = requestDetect,
            onDismiss = {
                showLocationPicker = false
                viewModel.clearLocationSuggestions()
            },
            onSearch = { viewModel.searchLocation(it) },
            suggestions = viewModel.locationSuggestions.collectAsStateWithLifecycle().value,
            onLocationSelected = { geo ->
                viewModel.setLocation(geo.lat, geo.lng, geo.displayName)
                showLocationPicker = false
                viewModel.clearLocationSuggestions()
            },
        )
    }
}

@Composable
private fun LocationDetectPrompt(
    onDetect: () -> Unit,
    onPickManually: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = ConcertTeal,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Set your location to discover concerts",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            TextButton(onClick = onDetect) {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Detect my location", color = ConcertTeal)
            }
            TextButton(onClick = onPickManually) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Search for a city", color = ConcertTeal)
            }
        }
    }
}

/**
 * Location search dialog with Nominatim geocoding (any city, matching desktop).
 */
@Composable
private fun LocationSearchDialog(
    currentCity: String?,
    isDetecting: Boolean,
    onDetect: () -> Unit,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    suggestions: List<com.parachord.shared.api.GeoLocation>,
    onLocationSelected: (com.parachord.shared.api.GeoLocation) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Set concert location", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                currentCity?.takeIf { it.isNotBlank() }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn, contentDescription = null,
                            modifier = Modifier.size(14.dp), tint = ConcertTeal,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Currently: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // Primary action — use current location (GPS commits; geoIP surfaces
                // below as a confirmable suggestion). Prominent tonal button.
                FilledTonalButton(
                    onClick = onDetect,
                    enabled = !isDetecting,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = ConcertTeal.copy(alpha = 0.14f),
                        contentColor = ConcertTeal,
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (isDetecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = ConcertTeal,
                        )
                    } else {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isDetecting) "Detecting…" else "Use my current location",
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "  or search  ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                }
                Spacer(Modifier.height(16.dp))

                // Search input (Nominatim typeahead).
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it; onSearch(it) },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(ConcertTeal),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(searchQuery) }),
                    decorationBox = { inner ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Search, contentDescription = null,
                                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search any city…",
                                        style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp),
                                    )
                                }
                                inner()
                            }
                        }
                    },
                )

                // Suggestion list (typeahead results + the geoIP confirm row).
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        suggestions.forEach { location ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onLocationSelected(location) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.LocationOn, contentDescription = null,
                                    modifier = Modifier.size(18.dp), tint = ConcertTeal.copy(alpha = 0.85f),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = location.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {},
    )
}

/**
 * Concert event list grouped by month, with date sidebar matching desktop layout.
 * Events are sorted soonest-first (by date ascending).
 */
@Composable
private fun ConcertEventList(
    events: List<ConcertEvent>,
    onNavigateToArtist: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    // Group events by month (YYYY-MM), matching desktop
    val grouped = remember(events) {
        events
            .sortedBy { it.date ?: "" }
            .groupBy { event ->
                val d = event.date ?: return@groupBy "Unknown"
                try {
                    val parsed = LocalDate.parse(d)
                    parsed.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                } catch (_: Exception) { "Unknown" }
            }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        grouped.forEach { (monthLabel, monthEvents) ->
            // Month header (e.g. "March 2026")
            item(key = "header-$monthLabel") {
                Text(
                    text = monthLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            items(monthEvents, key = { it.id }) { event ->
                ConcertEventCard(
                    event = event,
                    onArtistClick = {
                        event.artistName?.let { onNavigateToArtist(it) }
                    },
                    onTicketClick = { url ->
                        uriHandler.openUri(url)
                    },
                )
            }
        }
    }
}

/**
 * Concert event card with date sidebar, matching desktop's layout:
 * [Date Column] [Artist Image] [Event Details] [Tickets Button]
 */
@Composable
private fun ConcertEventCard(
    event: ConcertEvent,
    onArtistClick: () -> Unit,
    onTicketClick: (String) -> Unit,
) {
    // Parse date for sidebar display
    val dateInfo = remember(event.date) {
        event.date?.let { d ->
            try {
                val parsed = LocalDate.parse(d)
                Triple(
                    parsed.month.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()).uppercase(),
                    parsed.dayOfMonth.toString(),
                    parsed.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()),
                )
            } catch (_: Exception) { null }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onArtistClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // Date sidebar (matching desktop: month + day + weekday)
        if (dateInfo != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(44.dp),
            ) {
                Text(
                    text = dateInfo.first,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = ConcertTeal,
                    letterSpacing = 0.5.sp,
                )
                Text(
                    text = dateInfo.second,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = dateInfo.third,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
        }

        // Event image
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (event.imageUrl != null) {
                AsyncImage(
                    model = event.imageUrl,
                    contentDescription = event.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.ConfirmationNumber,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Artist name + ticket button on same line
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.artistName ?: event.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TicketButton(event = event, onTicketClick = onTicketClick)
            }

            // Source badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                val sources = event.ticketSources.ifEmpty {
                    listOf(TicketSource(event.source, event.ticketUrl ?: "", sourceLabel(event.source)))
                }
                sources.forEach { src ->
                    SourceBadge(source = src.source)
                }
            }

            // Venue
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

            // Location + time
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (event.locationString.isNotBlank()) {
                    Text(
                        text = event.locationString,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (event.displayTime.isNotBlank()) {
                    Text(
                        text = event.displayTime,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            // Artist source reason (matching desktop: "In your collection" / "From your listening history")
            val reason = when (event.artistSource) {
                "collection" -> "In your collection"
                "library" -> "In your library"
                "history" -> "From your listening history"
                else -> null
            }
            if (reason != null) {
                Text(
                    text = reason,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Source badge (TM, SG) with provider-specific colors matching desktop.
 */
@Composable
private fun SourceBadge(source: String) {
    val (color, label) = when (source) {
        "ticketmaster" -> TicketmasterColor to "TM"
        "seatgeek" -> SeatGeekColor to "SG"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to source.take(2).uppercase()
    }
    Text(
        text = label,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * Ticket button — single source opens direct, multiple sources show dropdown
 * (matching desktop's flyout behavior).
 */
@Composable
private fun TicketButton(
    event: ConcertEvent,
    onTicketClick: (String) -> Unit,
) {
    val sources = event.ticketSources.filter { it.ticketUrl.isNotBlank() }
    if (sources.isEmpty() && event.ticketUrl == null) return

    if (sources.size <= 1) {
        // Single source — direct link
        val url = sources.firstOrNull()?.ticketUrl ?: event.ticketUrl ?: return
        IconButton(
            onClick = { onTicketClick(url) },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = "Buy tickets",
                modifier = Modifier.size(18.dp),
                tint = ConcertTeal,
            )
        }
    } else {
        // Multiple sources — dropdown
        var expanded by remember { mutableStateOf(false) }
        Box {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.ConfirmationNumber,
                    contentDescription = "Buy tickets",
                    modifier = Modifier.size(18.dp),
                    tint = ConcertTeal,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                sources.forEach { src ->
                    val color = when (src.source) {
                        "ticketmaster" -> TicketmasterColor
                        "seatgeek" -> SeatGeekColor
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    DropdownMenuItem(
                        text = {
                            Text(src.label, color = color, fontWeight = FontWeight.Medium)
                        },
                        onClick = {
                            expanded = false
                            onTicketClick(src.ticketUrl)
                        },
                    )
                }
            }
        }
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "ticketmaster" -> "Ticketmaster"
    "seatgeek" -> "SeatGeek"
    else -> source.replaceFirstChar { it.uppercase() }
}
