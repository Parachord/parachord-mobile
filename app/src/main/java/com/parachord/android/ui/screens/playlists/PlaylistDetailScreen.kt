package com.parachord.android.ui.screens.playlists

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import com.parachord.android.ui.components.HostedBadge
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ContextMenuItem
import com.parachord.android.ui.components.ModalBg
import com.parachord.android.ui.components.ModalBgDarker
import com.parachord.android.ui.components.ModalDivider
import com.parachord.android.ui.components.ModalTextActive
import com.parachord.android.ui.components.ModalTextPrimary
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.components.rememberTrackContextMenuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToEdit: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = koinViewModel(),
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val mirrors by viewModel.mirrors.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val nowPlayingTitle by viewModel.nowPlayingTitle.collectAsStateWithLifecycle()
    val resolverOrder by viewModel.resolverOrder.collectAsStateWithLifecycle()
    val trackResolvers by viewModel.trackResolvers.collectAsStateWithLifecycle()
    val trackResolverConfidences by viewModel.trackResolverConfidences.collectAsStateWithLifecycle()

    // Re-check for remote updates every time the screen is displayed,
    // not just on ViewModel init (which doesn't re-run on back navigation).
    LaunchedEffect(Unit) {
        viewModel.checkForRemoteUpdate()
    }

    // Phase 6.5 — Decision D8: surface a toast when a sync-aware
    // delete returns Unsupported from a provider (e.g. Apple Music's
    // 401 on DELETE means the AM mirror persists; the user has to
    // remove it manually in the Music app).
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val hasRemoteUpdate by viewModel.hasRemoteUpdate.collectAsStateWithLifecycle()
    val isPulling by viewModel.isPulling.collectAsStateWithLifecycle()
    val contextMenuState = rememberTrackContextMenuState()
    val sharePlaylist = com.parachord.android.share.rememberSharePlaylist()
    var showPlaylistMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSyncSheet by remember { mutableStateOf(false) }

    // Context menu host with Remove from Playlist support
    TrackContextMenuHost(
        state = contextMenuState,
        playlists = allPlaylists,
        onPlayNext = { viewModel.playNext(it) },
        onAddToQueue = { viewModel.addToQueue(it) },
        onAddToPlaylist = { targetPlaylist, track -> viewModel.addToPlaylist(targetPlaylist, track) },
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToAlbum = onNavigateToAlbum,
        onToggleCollection = { track, isInCollection ->
            if (!isInCollection) viewModel.addToCollection(track)
        },
        onRemoveFromPlaylist = { _, position -> viewModel.removeFromPlaylist(position) },
    )

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = playlist?.name?.uppercase() ?: "PLAYLIST",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            windowInsets = WindowInsets(0),
        )

        // Remote update banner — matches desktop's "Updated on Spotify" banner
        AnimatedVisibility(
            visible = hasRemoteUpdate || isPulling,
            enter = expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF7C3AED).copy(alpha = 0.15f), Color(0xFF7C3AED).copy(alpha = 0.08f))
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isPulling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF7C3AED),
                        )
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = Color(0xFF7C3AED),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = if (isPulling) "Updating…" else "Updated on Spotify",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (!isPulling) {
                    Row {
                        Text(
                            text = "Pull",
                            color = Color(0xFF7C3AED),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { viewModel.pullRemoteChanges() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { viewModel.dismissRemoteUpdate() },
                        )
                    }
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Header with artwork + play all button
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        if (!playlist?.artworkUrl.isNullOrBlank()) {
                            AlbumArtCard(
                                artworkUrl = playlist?.artworkUrl,
                                size = 160.dp,
                                cornerRadius = 8.dp,
                                elevation = 4.dp,
                            )
                        } else {
                            PlaylistMosaic(
                                trackArtworkUrls = tracks.mapNotNull { it.trackArtworkUrl },
                                size = 160.dp,
                            )
                        }
                        // Hosted chip overlays the artwork's bottom-left corner
                        // so it's discoverable at a glance — matches how
                        // streaming apps tag "Live", "Mix", etc. on cover art.
                        if (playlist?.sourceUrl != null) {
                            HostedBadge(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = playlist?.name ?: "",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )

                    // Author and source line
                    val metaParts = buildList {
                        playlist?.ownerName?.let { add("by $it") }
                        // Source chip:
                        //   - Hosted XSPF → hostname-derived label
                        //     (spinitron.com → "Spinitron").
                        //   - Local playlist (id starts with "local-") →
                        //     no source chip even if mirrored to Spotify.
                        //     The `spotifyId` on these rows is an outbound
                        //     sync link, not the origin.
                        //   - Spotify-imported playlist → "Spotify".
                        val isLocal = playlist?.id?.startsWith("local-") == true
                        val sourceLabel = playlist?.sourceUrl?.let { hostLabelFromUrl(it) }
                            ?: playlist?.spotifyId?.takeIf { !isLocal }?.let { "Spotify" }
                        sourceLabel?.let { add(it) }
                        add("${tracks.size} tracks")
                    }
                    Text(
                        text = metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Source/mirror chips — each opens the playlist on that
                    // service. Driven off sync_playlist_link (+ id-prefix /
                    // pull source), so a multi-mirror playlist shows them all.
                    if (mirrors.isNotEmpty()) {
                        val mirrorCtx = LocalContext.current
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            mirrors.forEach { (providerId, externalId) ->
                                playlistMirrorUrl(providerId, externalId)?.let { url ->
                                    MirrorLinkChip(
                                        label = playlistProviderLabel(providerId),
                                        color = playlistProviderColor(providerId),
                                    ) {
                                        runCatching {
                                            mirrorCtx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // "Last updated" line. Uses `lastModified` (set on every
                    // content change — hosted-XSPF refresh, manual edit, or
                    // Spotify pull) rather than `updatedAt` (which tracks
                    // any row write including metadata-only touches).
                    playlist?.lastModified?.takeIf { it > 0 }?.let { ts ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Last updated ${formatPlaylistRelativeTime(ts)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (tracks.isNotEmpty()) {
                        // Button is centered horizontally on the screen; the
                        // overflow menu floats to the right edge so it stays
                        // accessible without offsetting Play All.
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { viewModel.playAll() },
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.align(Alignment.Center),
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play All")
                            }
                            IconButton(
                                onClick = { showPlaylistMenu = true },
                                modifier = Modifier.align(Alignment.CenterEnd),
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Track list
            itemsIndexed(tracks, key = { index, _ -> index }) { index, track ->
                TrackRow(
                    title = track.trackTitle,
                    artist = track.trackArtist,
                    artworkUrl = track.trackArtworkUrl,
                    resolvers = trackResolvers["${track.trackTitle.lowercase().trim()}|${track.trackArtist.lowercase().trim()}"]
                        ?: track.availableResolvers(resolverOrder),
                    resolverConfidences = trackResolverConfidences["${track.trackTitle.lowercase().trim()}|${track.trackArtist.lowercase().trim()}"],
                    duration = track.trackDuration,
                    trackNumber = index + 1,
                    isPlaying = nowPlayingTitle == track.trackTitle,
                    onClick = { viewModel.playTrack(index) },
                    onLongClick = {
                        val entity = viewModel.trackEntityAt(index)
                        if (entity != null) {
                            contextMenuState.show(
                                TrackContextInfo(
                                    title = track.trackTitle,
                                    artist = track.trackArtist,
                                    album = track.trackAlbum,
                                    artworkUrl = track.trackArtworkUrl,
                                    duration = track.trackDuration,
                                    playlistId = track.playlistId,
                                    playlistPosition = track.position,
                                ),
                                entity,
                            )
                        }
                    },
                )
            }
        }

        if (showPlaylistMenu) {
            val pl = playlist
            if (pl != null) {
                PlaylistOptionsSheet(
                    playlistName = pl.name,
                    artworkUrl = pl.artworkUrl,
                    trackCount = tracks.size,
                    onDismiss = { showPlaylistMenu = false },
                    onEditPlaylist = {
                        showPlaylistMenu = false
                        onNavigateToEdit()
                    },
                    onQueuePlaylist = {
                        showPlaylistMenu = false
                        viewModel.queueAll()
                    },
                    onDeletePlaylist = {
                        showPlaylistMenu = false
                        showDeleteConfirm = true
                    },
                    // Detail screen has the full tracklist already in scope
                    // → use the rich smart-link variant (per-track service URLs)
                    // rather than the lite deeplink fallback.
                    onShare = { sharePlaylist(pl, tracks) },
                    onOpenSync = {
                        showPlaylistMenu = false
                        showSyncSheet = true
                    },
                )
            }
        }

        if (showSyncSheet) {
            val pl = playlist
            if (pl != null) {
                com.parachord.android.ui.components.PlaylistSyncChannelsSheet(
                    playlistId = pl.id,
                    playlistName = pl.name,
                    onDismiss = { showSyncSheet = false },
                )
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor = ModalBg,
                titleContentColor = ModalTextActive,
                textContentColor = ModalTextPrimary,
                title = { Text("Delete Playlist") },
                text = { Text("Are you sure you want to delete \"${playlist?.name}\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        viewModel.deletePlaylist()
                        onBack()
                    }) {
                        Text("Delete", color = Color(0xFFEF4444))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel", color = ModalTextPrimary)
                    }
                },
            )
        }
    }
}

@Composable
private fun PlaylistMosaic(
    trackArtworkUrls: List<String>,
    size: Dp,
) {
    val uniqueUrls = trackArtworkUrls.distinct().take(4)
    val halfSize = size / 2

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        if (uniqueUrls.size >= 4) {
            Column {
                Row {
                    AsyncImage(model = uniqueUrls[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                    AsyncImage(model = uniqueUrls[1], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                }
                Row {
                    AsyncImage(model = uniqueUrls[2], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                    AsyncImage(model = uniqueUrls[3], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                }
            }
        } else if (uniqueUrls.isNotEmpty()) {
            // Less than 4 unique artworks — just show the first one full size
            AsyncImage(model = uniqueUrls[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // No artwork at all — gray placeholder
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF2D2D2D)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = Color(0xFF6B7280),
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistOptionsSheet(
    playlistName: String,
    artworkUrl: String?,
    trackCount: Int,
    onDismiss: () -> Unit,
    onEditPlaylist: () -> Unit,
    onQueuePlaylist: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onShare: (() -> Unit)? = null,
    onOpenSync: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ModalBg,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(ModalBg, ModalBgDarker)))
                .padding(bottom = 32.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtCard(
                    artworkUrl = artworkUrl,
                    size = 48.dp,
                    cornerRadius = 4.dp,
                    elevation = 1.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlistName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ModalTextActive,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$trackCount tracks",
                        fontSize = 13.sp,
                        color = ModalTextPrimary,
                    )
                }
            }

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 8.dp))

            ContextMenuItem(
                icon = Icons.Filled.Edit,
                label = "Edit Playlist",
                onClick = onEditPlaylist,
            )

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))

            ContextMenuItem(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = "Queue Playlist",
                onClick = onQueuePlaylist,
            )

            if (onShare != null) {
                HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                ContextMenuItem(
                    icon = Icons.Filled.Share,
                    label = "Share",
                    onClick = { onShare(); onDismiss() },
                )
            }

            if (onOpenSync != null) {
                HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                ContextMenuItem(
                    icon = Icons.Filled.Sync,
                    label = "Sync…",
                    onClick = { onOpenSync(); onDismiss() },
                )
            }

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))

            ContextMenuItem(
                icon = Icons.Filled.Delete,
                label = "Delete Playlist",
                onClick = onDeletePlaylist,
            )
        }
    }
}

/**
 * Derive a short human label from a hosted-XSPF source URL. Standard
 * hosts use the first hostname label (`spinitron.com/...` → "Spinitron").
 * Project-hosting domains like GitHub Pages bury the meaningful name in
 * the path — `https://jherskowitz.github.io/spinbin/...` is "Spinbin",
 * not "Jherskowitz" — so those are special-cased to use the first path
 * segment instead. Returns null if the URL can't be parsed.
 */
private fun hostLabelFromUrl(url: String): String? {
    val uri = try {
        android.net.Uri.parse(url)
    } catch (_: Exception) {
        null
    } ?: return null
    val host = uri.host?.removePrefix("www.") ?: return null

    // Project-hosting domains where the user/org is the subdomain and
    // the project name is the first path segment. Add new suffixes here
    // as they come up (gitlab.io, netlify.app, vercel.app, etc).
    val projectHostSuffixes = listOf(".github.io", ".gitlab.io", ".netlify.app", ".vercel.app", ".pages.dev")
    if (projectHostSuffixes.any { host.endsWith(it) }) {
        val firstSegment = uri.pathSegments?.firstOrNull()?.trim('/')?.ifBlank { null }
        if (firstSegment != null) return firstSegment.titlecase()
    }

    // Standard path — first hostname label. `radio.example.co.uk` → "radio".
    val first = host.substringBefore('.').ifBlank { return null }
    return first.titlecase()
}

private fun String.titlecase(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }

/**
 * "X ago" style relative-time formatter for the playlist detail header.
 * Mirrors the settings screen's sync-status formatter but kept local
 * to avoid dragging the helper across modules.
 */
private fun formatPlaylistRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> {
            val m = diff / 60_000
            if (m == 1L) "1 minute ago" else "$m minutes ago"
        }
        diff < 86_400_000 -> {
            val h = diff / 3_600_000
            if (h == 1L) "1 hour ago" else "$h hours ago"
        }
        diff < 7 * 86_400_000L -> {
            val d = diff / 86_400_000
            if (d == 1L) "yesterday" else "$d days ago"
        }
        else -> {
            val fmt = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            "on ${fmt.format(java.util.Date(timestamp))}"
        }
    }
}

/** Web URL for a playlist on a given service, or null if not linkable. */
private fun playlistMirrorUrl(providerId: String, externalId: String): String? = when (providerId) {
    "spotify" -> "https://open.spotify.com/playlist/$externalId"
    "listenbrainz" -> "https://listenbrainz.org/playlist/$externalId/"
    "applemusic" -> "https://music.apple.com/library/playlist/$externalId"
    else -> null
}

private fun playlistProviderLabel(providerId: String): String = when (providerId) {
    "spotify" -> "Spotify"
    "applemusic" -> "Apple Music"
    "listenbrainz" -> "ListenBrainz"
    else -> providerId.replaceFirstChar { it.uppercase() }
}

private fun playlistProviderColor(providerId: String): Color = when (providerId) {
    "spotify" -> Color(0xFF1DB954)
    "applemusic" -> Color(0xFFFA243C)
    "listenbrainz" -> Color(0xFFEB743B)
    else -> Color(0xFF9CA3AF)
}

@Composable
private fun MirrorLinkChip(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = "$label ↗",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color = color.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
