package com.parachord.android.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.share.rememberShareTrack

/**
 * Composable state holder for track context menu + playlist picker.
 *
 * Usage:
 * ```
 * val menuState = rememberTrackContextMenuState()
 *
 * // In your TrackRow:
 * TrackRow(onLongClick = { menuState.show(trackInfo, trackEntity) })
 *
 * // At the bottom of your composable:
 * TrackContextMenuHost(state = menuState, ...)
 * ```
 */
class TrackContextMenuState {
    var contextTrack by mutableStateOf<TrackContextInfo?>(null)
        private set
    var contextTrackEntity by mutableStateOf<TrackEntity?>(null)
        private set
    var showPlaylistPicker by mutableStateOf(false)
        private set
    var showEditMetadata by mutableStateOf(false)
        private set

    fun show(info: TrackContextInfo, entity: TrackEntity) {
        contextTrack = info
        contextTrackEntity = entity
    }

    fun dismiss() {
        contextTrack = null
        contextTrackEntity = null
    }

    /**
     * Transition from context menu to playlist picker.
     * Hides the context menu sheet but keeps the entity for the picker.
     */
    fun openPlaylistPicker() {
        contextTrack = null // Remove context menu sheet from composition
        showPlaylistPicker = true
        // contextTrackEntity is preserved for the playlist picker
    }

    fun dismissPlaylistPicker() {
        showPlaylistPicker = false
        contextTrackEntity = null
    }

    /** Transition from context menu to the metadata (tag) editor dialog. */
    fun openEditMetadata() {
        contextTrack = null // Remove context menu sheet from composition
        showEditMetadata = true
        // contextTrackEntity is preserved for the editor dialog.
    }

    fun dismissEditMetadata() {
        showEditMetadata = false
        contextTrackEntity = null
    }
}

@Composable
fun rememberTrackContextMenuState(): TrackContextMenuState {
    return remember { TrackContextMenuState() }
}

/**
 * Renders the TrackContextMenu and PlaylistPickerSheet based on [state].
 * Place this at the root of your screen composable.
 */
@Composable
fun TrackContextMenuHost(
    state: TrackContextMenuState,
    playlists: List<PlaylistEntity>,
    onPlayNext: (TrackEntity) -> Unit,
    onAddToQueue: (TrackEntity) -> Unit,
    onAddToPlaylist: (PlaylistEntity, TrackEntity) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: ((albumTitle: String, artistName: String) -> Unit)? = null,
    onToggleCollection: ((TrackEntity, Boolean) -> Unit)? = null,
    onRemoveFromPlaylist: ((playlistId: String, position: Int) -> Unit)? = null,
    onCreateNewPlaylist: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val shareTrack = rememberShareTrack()
    val editMetadata = rememberEditTrackMetadata()
    val track = state.contextTrack
    val entity = state.contextTrackEntity

    if (track != null && entity != null) {
        TrackContextMenu(
            track = track,
            onDismiss = { state.dismiss() },
            onShare = { shareTrack(entity) },
            // Local files only — editing a streaming track's title/artist would
            // break the resolution that keys on them.
            onEditMetadata = if (entity.resolver == "localfiles") {
                { state.openEditMetadata() }
            } else null,
            onPlayNext = {
                onPlayNext(entity)
                Toast.makeText(context, "Playing next: ${entity.title}", Toast.LENGTH_SHORT).show()
            },
            onAddToQueue = {
                onAddToQueue(entity)
                Toast.makeText(context, "Added to queue: ${entity.title}", Toast.LENGTH_SHORT).show()
            },
            onAddToPlaylist = {
                state.openPlaylistPicker()
            },
            onGoToArtist = {
                onNavigateToArtist(track.artist)
            },
            onGoToAlbum = if (track.album != null && onNavigateToAlbum != null) {
                { onNavigateToAlbum(track.album, track.artist) }
            } else null,
            onToggleCollection = {
                onToggleCollection?.invoke(entity, track.isInCollection)
                val msg = if (track.isInCollection) "Removed from collection" else "Added to collection"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            },
            onRemoveFromPlaylist = if (track.playlistId != null && track.playlistPosition != null && onRemoveFromPlaylist != null) {
                {
                    onRemoveFromPlaylist(track.playlistId, track.playlistPosition)
                    Toast.makeText(context, "Removed from playlist", Toast.LENGTH_SHORT).show()
                }
            } else null,
        )
    }

    if (state.showEditMetadata && entity != null) {
        EditMetadataDialog(
            track = entity,
            onSave = { title, artist, album -> editMetadata(entity, title, artist, album) },
            onDismiss = { state.dismissEditMetadata() },
        )
    }

    if (state.showPlaylistPicker && entity != null) {
        PlaylistPickerSheet(
            playlists = playlists,
            onSelectPlaylist = { playlist ->
                onAddToPlaylist(playlist, entity)
                Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
            },
            onCreateNewPlaylist = {
                onCreateNewPlaylist?.invoke()
            },
            onDismiss = { state.dismissPlaylistPicker() },
        )
    }
}
