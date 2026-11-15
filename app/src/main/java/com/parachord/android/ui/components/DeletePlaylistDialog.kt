package com.parachord.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The one delete dialog for a playlist, on every screen that deletes one
 * (#684). It names each service the playlist is on, and the user ticks which
 * of them lose their copy; [onConfirm] gets exactly that set (empty = only
 * Parachord's copy goes). Deleting from Spotify unfollows the playlist, which
 * its owner can't undo in Spotify's app, so no screen may delete remotely
 * without asking here. iOS has the same dialog (`PlaylistDetailView`).
 *
 * [loadMirrors] returns the provider ids the playlist mirrors to
 * (`LibraryRepository.getPlaylistMirrors(id).keys`).
 */
@Composable
fun DeletePlaylistDialog(
    playlistId: String,
    playlistName: String,
    loadMirrors: suspend (String) -> List<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var mirrors by remember(playlistId) { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember(playlistId) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(playlistId) {
        val m = runCatching { loadMirrors(playlistId) }.getOrDefault(emptyList())
        mirrors = m
        selected = m.toSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ModalBg,
        titleContentColor = ModalTextActive,
        textContentColor = ModalTextPrimary,
        title = { Text("Delete Playlist") },
        text = {
            Column {
                Text("Remove \"$playlistName\" from Parachord.")
                if (mirrors.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Also delete from:",
                        color = ModalTextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    mirrors.forEach { pid ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                selected = if (pid in selected) selected - pid else selected + pid
                            },
                        ) {
                            Checkbox(
                                checked = pid in selected,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + pid else selected - pid
                                },
                            )
                            Text(deletePlaylistProviderLabel(pid), color = ModalTextPrimary)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Unchecked services keep their copy (may re-import on next sync). " +
                            "Apple Music can't be deleted via its API.",
                        color = ModalTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.intersect(mirrors.toSet())) }) {
                Text("Delete", color = Color(0xFFEF4444))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = ModalTextPrimary)
            }
        },
    )
}

private fun deletePlaylistProviderLabel(providerId: String): String = when (providerId) {
    "spotify" -> "Spotify"
    "applemusic" -> "Apple Music"
    "listenbrainz" -> "ListenBrainz"
    else -> providerId.replaceFirstChar { it.uppercase() }
}
