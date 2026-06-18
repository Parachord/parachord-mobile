package com.parachord.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.resolver.TrackResolverCache
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * In-app metadata (tag) editor for a local-files track — Android side of the
 * iOS local-files tag editor (cross-platform parity). Edits title / artist /
 * album as Parachord stores them (the DB row); it does NOT rewrite the on-disk
 * file's tags. Shown from the track context menu for `resolver == "localfiles"`
 * tracks only — editing a streaming track's title/artist would break the
 * resolution that keys on them.
 */
@Composable
fun EditMetadataDialog(
    track: TrackEntity,
    onSave: (title: String, artist: String, album: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Metadata") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist, onValueChange = { artist = it },
                    label = { Text("Artist") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = album, onValueChange = { album = it },
                    label = { Text("Album") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Updates how this track appears in Parachord — title, artist, and album " +
                        "for display, playback, and scrobbles. The original file on disk isn't changed.",
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, artist, album); onDismiss() },
                enabled = title.isNotBlank(),
            ) { Text("Save", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Self-contained save action for the tag editor (mirrors [rememberShareTrack]):
 * Koin-injects the repository + resolver cache so the context-menu host can wire
 * editing with no per-screen plumbing. Updates the DB row, then re-resolves under
 * the NEW title/artist so resolver badges + album art refresh (the row shows an
 * art skeleton while that resolve is in flight).
 */
@Composable
fun rememberEditTrackMetadata(): (TrackEntity, String, String, String) -> Unit {
    val scope = rememberCoroutineScope()
    val repo: LibraryRepository = koinInject()
    val resolverCache: TrackResolverCache = koinInject()
    return remember(repo, resolverCache) {
        { entity, title, artist, album ->
            scope.launch {
                val albumOpt = album.trim().ifBlank { null }
                repo.updateTrackMetadata(entity.id, title, artist, albumOpt)
                // Re-resolve under the corrected metadata. backfillDb = TRUE so the
                // newly-resolved artwork (and streaming IDs) backfill onto the row —
                // that DB write is what makes the row's art update from the skeleton
                // to the real cover (TrackResolverCache only backfills artwork when
                // the row currently has none, so existing art is never clobbered).
                val updated = entity.copy(
                    title = title.trim().ifBlank { entity.title },
                    artist = artist.trim().ifBlank { "Unknown Artist" },
                    album = albumOpt,
                )
                resolverCache.resolveInBackground(listOf(updated), backfillDb = true, priority = true)
            }
            Unit
        }
    }
}
