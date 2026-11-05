package com.parachord.android.share

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.share.PlaylistShareCheck
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
    val shareManager: ShareManager = koinInject()
    val share = rememberPlaylistShareFlow(shareManager)
    return remember(share) {
        { playlist, tracks -> share { shareManager.sharePlaylist(playlist, tracks) } }
    }
}

/**
 * Variant for callsites (e.g. PlaylistsScreen list rows) that have only the
 * playlist ID — track list is loaded lazily inside ShareManager.
 */
@Composable
fun rememberSharePlaylistById(): (String) -> Unit {
    val shareManager: ShareManager = koinInject()
    val share = rememberPlaylistShareFlow(shareManager)
    return remember(share) {
        { id -> share { shareManager.sharePlaylist(id) } }
    }
}

/**
 * Runs a playlist share and acts on the answer: the share sheet, a message, or the
 * "Make this playlist public?" prompt, which it also hosts. Cancelling or tapping
 * outside the prompt changes nothing and shares nothing (#527, desktop parachord#1265).
 */
@Composable
private fun rememberPlaylistShareFlow(shareManager: ShareManager): (suspend () -> PlaylistShare) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf<PlaylistShare.NeedsPublic?>(null) }

    fun handle(share: PlaylistShare) {
        when (share) {
            PlaylistShare.NotSynced -> Toast.makeText(context, LB_SHARE_HINT, Toast.LENGTH_LONG).show()
            PlaylistShare.Gone -> Toast.makeText(context, PlaylistShareCheck.GONE_MESSAGE, Toast.LENGTH_LONG).show()
            is PlaylistShare.Failed ->
                Toast.makeText(context, PlaylistShareCheck.makePublicFailed(share.error), Toast.LENGTH_LONG).show()
            is PlaylistShare.NeedsPublic -> prompt = share
            is PlaylistShare.Ready -> openShareSheet(context, share.result.url, share.result.subject)
        }
    }

    prompt?.let { asked ->
        AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(PlaylistShareCheck.PRIVATE_TITLE) },
            text = { Text(PlaylistShareCheck.privateMessage(asked.name)) },
            confirmButton = {
                TextButton(onClick = {
                    prompt = null
                    scope.launch { handle(shareManager.makePublicAndShare(asked)) }
                }) { Text(PlaylistShareCheck.PRIVATE_CONFIRM) }
            },
            dismissButton = {
                TextButton(onClick = { prompt = null }) { Text("Cancel") }
            },
        )
    }

    return remember(context, scope) {
        { ask -> scope.launch { handle(ask()) } }
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
