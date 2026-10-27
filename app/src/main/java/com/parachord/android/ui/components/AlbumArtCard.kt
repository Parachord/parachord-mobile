package com.parachord.android.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.launch

/**
 * A stored cover at [url] failed to load: ask [com.parachord.shared.metadata.BrokenArtRetry]
 * for a working one (once per album per session), which also patches every
 * stored copy. Null when the album isn't known or nothing better turned up.
 * Desktop parity: parachord#1101.
 */
internal suspend fun retryBrokenAlbumArt(url: String, artist: String?, album: String?): String? {
    if (artist.isNullOrBlank() || album.isNullOrBlank()) return null
    return runCatching {
        org.koin.core.context.GlobalContext.get()
            .get<com.parachord.shared.metadata.BrokenArtRetry>()
            .reportAlbumArt(url, artist, album)
    }.getOrNull()
}

/**
 * Reusable album art image composable with rounded corners, shadow,
 * and a gradient placeholder when no artwork URL is available.
 *
 * When [placeholderName] is provided, uses a deterministic gradient
 * with initials (matching the desktop app's behavior). Otherwise falls
 * back to a simple music note placeholder.
 *
 * Pass [artist] and [album] wherever they're known: a cover that fails to
 * load is then looked up again ([retryBrokenAlbumArt]) instead of leaving
 * the placeholder up for as long as the dead URL stays stored.
 */
@Composable
fun AlbumArtCard(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    cornerRadius: Dp = 8.dp,
    elevation: Dp = 2.dp,
    placeholderName: String? = null,
    contentDescription: String? = "Album artwork",
    artist: String? = null,
    album: String? = null,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val commonModifier = modifier
        .size(size)
        .shadow(elevation, shape)
        .clip(shape)
    val scope = rememberCoroutineScope()
    var replacement by remember(artworkUrl) { mutableStateOf<String?>(null) }

    // Animate between different artwork URLs (track switches) with a smooth crossfade
    Crossfade(
        targetState = replacement ?: artworkUrl,
        animationSpec = tween(durationMillis = 300),
        modifier = commonModifier,
        label = "album-art-crossfade",
    ) { url ->
        if (!url.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(300)
                    .build(),
                contentDescription = contentDescription,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
                onError = {
                    scope.launch { retryBrokenAlbumArt(url, artist, album)?.let { replacement = it } }
                },
                loading = {
                    if (placeholderName != null) {
                        GradientPlaceholder(
                            name = placeholderName,
                            modifier = Modifier.size(size),
                            fontSize = (size.value * 0.35f).sp,
                        )
                    } else {
                        ArtPlaceholderSimple(Modifier.size(size), size)
                    }
                },
                error = {
                    if (placeholderName != null) {
                        GradientPlaceholder(
                            name = placeholderName,
                            modifier = Modifier.size(size),
                            fontSize = (size.value * 0.35f).sp,
                        )
                    } else {
                        ArtPlaceholderSimple(Modifier.size(size), size)
                    }
                },
            )
        } else {
            if (placeholderName != null) {
                GradientPlaceholder(
                    name = placeholderName,
                    modifier = Modifier.size(size),
                    fontSize = (size.value * 0.35f).sp,
                )
            } else {
                ArtPlaceholderSimple(Modifier.size(size), size)
            }
        }
    }
}

/**
 * Variant that fills available width with a 1:1 aspect ratio.
 * Used for large album art displays (NowPlaying, Album header).
 *
 * Defers URL changes until the new image is decoded — the displayed URL
 * only updates on Coil's onSuccess callback, so the old artwork stays
 * visible with zero flicker. Uses Compose Crossfade for smooth transitions.
 */
@Composable
fun AlbumArtCardFill(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    elevation: Dp = 4.dp,
    placeholderName: String? = null,
    contentDescription: String? = "Album artwork",
    artist: String? = null,
    album: String? = null,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val commonModifier = modifier
        .aspectRatio(1f)
        .shadow(elevation, shape)
        .clip(shape)

    // Only update the displayed URL when the image is actually decoded.
    // This prevents any flash/flicker when the URL changes — the old
    // image stays visible until the new one is ready.
    var displayedUrl by remember { mutableStateOf(artworkUrl) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The URL to show: the caller's, or a working replacement for it once a
    // dead cover has been retried ([retryBrokenAlbumArt]).
    var wantedUrl by remember(artworkUrl) { mutableStateOf(artworkUrl) }

    // Prefetch the new URL; update displayedUrl only on success. A failed
    // load (Coil returns an ErrorResult, it doesn't throw) used to be shown
    // anyway; now it keeps the current image and retries the cover.
    LaunchedEffect(wantedUrl) {
        val target = wantedUrl
        if (target.isNullOrBlank()) {
            displayedUrl = null
            return@LaunchedEffect
        }
        if (target == displayedUrl) return@LaunchedEffect
        val result = try {
            context.imageLoader.execute(ImageRequest.Builder(context).data(target).build())
        } catch (_: Exception) {
            null
        }
        if (result is SuccessResult) {
            displayedUrl = target // now in memory cache — safe to switch
        } else {
            retryBrokenAlbumArt(target, artist, album)?.let { wantedUrl = it }
        }
    }

    Crossfade(
        targetState = displayedUrl,
        animationSpec = tween(durationMillis = 400),
        modifier = commonModifier,
        label = "album-art-fill-crossfade",
    ) { url ->
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .build(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // The first URL is shown without a prefetch, so its failure lands here.
                onError = {
                    scope.launch { retryBrokenAlbumArt(url, artist, album)?.let { wantedUrl = it } }
                },
            )
        } else {
            if (placeholderName != null) {
                GradientPlaceholder(
                    name = placeholderName,
                    modifier = Modifier.fillMaxSize(),
                    fontSize = 32.sp,
                )
            } else {
                ArtPlaceholderSimple(Modifier.fillMaxSize(), 48.dp)
            }
        }
    }
}

/**
 * Simple music note placeholder (fallback when no name is available).
 */
@Composable
private fun ArtPlaceholderSimple(modifier: Modifier, iconSize: Dp) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize * 0.4f),
        )
    }
}
