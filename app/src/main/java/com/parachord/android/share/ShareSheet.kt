package com.parachord.android.share

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Shown when a playlist can't be shared yet — it has no ListenBrainz MBID anchor
 *  (the cross-platform key Achordion's playlist page uses). Matches desktop. */
private const val LB_SHARE_HINT =
    "Sync this playlist to ListenBrainz first to enable sharing via Achordion."

/**
 * Open the system share sheet (`Intent.ACTION_SEND` wrapped in
 * `Intent.createChooser`) with the given URL and a human-readable subject.
 * Subject is shown by recipients that surface it (e.g. email apps).
 */
fun openShareSheet(context: Context, url: String, subject: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, url)
    }
    val chooser = Intent.createChooser(intent, "Share").apply {
        // Required when launching from a non-Activity context. Most callsites
        // pass an Activity so this is a no-op for them.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

/**
 * Returns a callback that asynchronously builds a share URL (Achordion
 * entity link with submit pre-warm — falling back to a lookup URL when
 * the MBID is absent or the API times out) for [track] and opens the
 * system share sheet. Internal 4 s timeout caps share-sheet delay.
 */
@Composable
fun rememberShareTrack(): (TrackEntity) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareManager: ShareManager = koinInject()
    return remember(context) {
        { track ->
            scope.launch {
                val result = shareManager.shareTrack(track)
                openShareSheet(context, result.url, result.subject)
            }
            Unit
        }
    }
}

@Composable
fun rememberShareAlbum(): (
    title: String,
    artist: String,
    artworkUrl: String?,
    tracks: List<PlaylistTrackEntity>,
    spotifyAlbumId: String?,
) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareManager: ShareManager = koinInject()
    return remember(context) {
        { title, artist, artworkUrl, _, _ ->
            scope.launch {
                // releaseGroupMbid is not yet plumbed through callers — future follow-up
                // to surface it from MetadataService results so Achordion serves the
                // entity page directly instead of the lookup fallback.
                val result = shareManager.shareAlbum(title, artist, artworkUrl, releaseGroupMbid = null)
                openShareSheet(context, result.url, result.subject)
            }
            Unit
        }
    }
}

/**
 * Album share variant for the (common) case where the callsite has only
 * (title, artist, artwork) in scope — the rest of an album row in a list,
 * for instance. Falls back to the `parachord://album/{artist}/{title}`
 * deeplink wrapper because we have no per-track URLs to feed the
 * smart-link API. A future revision can plumb track lookup through
 * here for richer rows.
 */
@Composable
fun rememberShareAlbumLite(): (title: String, artist: String, artworkUrl: String?) -> Unit {
    val shareAlbum = rememberShareAlbum()
    return remember(shareAlbum) {
        { title, artist, artworkUrl ->
            shareAlbum(title, artist, artworkUrl, emptyList(), null)
        }
    }
}

@Composable
fun rememberSharePlaylist(): (PlaylistEntity, List<PlaylistTrackEntity>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareManager: ShareManager = koinInject()
    return remember(context) {
        { playlist, tracks ->
            scope.launch {
                val result = shareManager.sharePlaylist(playlist, tracks) ?: run {
                    Toast.makeText(context, LB_SHARE_HINT, Toast.LENGTH_LONG).show()
                    return@launch
                }
                openShareSheet(context, result.url, result.subject)
            }
            Unit
        }
    }
}

/**
 * Variant for callsites (e.g. PlaylistsScreen list rows) that have only the
 * playlist ID — track list is loaded lazily inside ShareManager.
 */
@Composable
fun rememberSharePlaylistById(): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareManager: ShareManager = koinInject()
    return remember(context) {
        { id ->
            scope.launch {
                val result = shareManager.sharePlaylist(id) ?: run {
                    Toast.makeText(context, LB_SHARE_HINT, Toast.LENGTH_LONG).show()
                    return@launch
                }
                openShareSheet(context, result.url, result.subject)
            }
            Unit
        }
    }
}

@Composable
fun rememberShareArtist(): (name: String, imageUrl: String?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareManager: ShareManager = koinInject()
    return remember(context, shareManager) {
        { name, _ ->
            // imageUrl is no longer used — Achordion serves the artist page
            // from name (or MBID, when plumbed through callers in a follow-up).
            scope.launch {
                val result = shareManager.shareArtist(name, artistMbid = null)
                openShareSheet(context, result.url, result.subject)
            }
            Unit
        }
    }
}

/**
 * Convenience: surface a "couldn't share" toast on the rare case where the
 * share sheet itself fails (no apps installed that handle text/plain — very
 * unusual). Caller wraps [openShareSheet] with this if they care.
 */
fun safeOpenShareSheet(context: Context, url: String, subject: String) {
    try {
        openShareSheet(context, url, subject)
    } catch (e: Exception) {
        Toast.makeText(context, "No app available to share with", Toast.LENGTH_SHORT).show()
    }
}
