package com.parachord.shared.ui

/**
 * A track's long-press menu, one list for both apps (Android `TrackContextMenu`,
 * iOS `pcTrackContextMenu`), in desktop's group order (`main.js`'s track
 * context menu): the queue actions, the removals, the tag editor, the
 * collection, then where to go and Share. Play Next and Go to Artist / Album
 * are mobile's own (desktop opens pages by clicking the names).
 */
enum class TrackMenuAction {
    PLAY_NEXT,
    ADD_TO_QUEUE,
    ADD_TO_PLAYLIST,
    REMOVE_FROM_QUEUE,
    REMOVE_FROM_PLAYLIST,
    EDIT_METADATA,
    /** Add to or remove from the collection; [TrackMenuItem.label] says which. */
    COLLECTION,
    GO_TO_ARTIST,
    GO_TO_ALBUM,
    SHARE,
}

/**
 * One row: [startsGroup] draws a divider above it, [destructive] the danger
 * colour. [label] is empty for [TrackMenuAction.COLLECTION] when the app
 * didn't know whether the track is saved (iOS's heart row reads it itself).
 */
data class TrackMenuItem(
    val action: TrackMenuAction,
    val label: String,
    val startsGroup: Boolean,
    val destructive: Boolean = false,
)

object TrackMenu {
    const val PLAY_NEXT = "Play Next"
    const val ADD_TO_QUEUE = "Add to Queue"
    const val ADD_TO_PLAYLIST = "Add to Playlist…"
    const val REMOVE_FROM_QUEUE = "Remove from Queue"
    const val REMOVE_FROM_PLAYLIST = "Remove from Playlist"
    const val EDIT_METADATA = "Edit Metadata"
    const val ADD_TO_COLLECTION = "Add to Collection"
    const val REMOVE_FROM_COLLECTION = "Remove from Collection"
    const val GO_TO_ARTIST = "Go to Artist"
    const val GO_TO_ALBUM = "Go to Album"
    const val SHARE = "Share"

    /** The collection row's words, or "" while it isn't known. */
    fun collectionLabel(inCollection: Boolean?): String = when (inCollection) {
        true -> REMOVE_FROM_COLLECTION
        false -> ADD_TO_COLLECTION
        null -> ""
    }

    /**
     * The rows for a track, given what this screen can do. [inCollection] null
     * leaves the collection row's label to the app.
     */
    fun items(
        inCollection: Boolean?,
        canAddToPlaylist: Boolean = true,
        canRemoveFromQueue: Boolean = false,
        canRemoveFromPlaylist: Boolean = false,
        canEditMetadata: Boolean = false,
        canGoToArtist: Boolean = true,
        canGoToAlbum: Boolean = false,
        canShare: Boolean = true,
    ): List<TrackMenuItem> {
        val groups = listOf(
            listOfNotNull(
                TrackMenuItem(TrackMenuAction.PLAY_NEXT, PLAY_NEXT, false),
                TrackMenuItem(TrackMenuAction.ADD_TO_QUEUE, ADD_TO_QUEUE, false),
                if (canAddToPlaylist) TrackMenuItem(TrackMenuAction.ADD_TO_PLAYLIST, ADD_TO_PLAYLIST, false) else null,
            ),
            listOfNotNull(
                if (canRemoveFromQueue) TrackMenuItem(TrackMenuAction.REMOVE_FROM_QUEUE, REMOVE_FROM_QUEUE, false, destructive = true) else null,
                if (canRemoveFromPlaylist) TrackMenuItem(TrackMenuAction.REMOVE_FROM_PLAYLIST, REMOVE_FROM_PLAYLIST, false, destructive = true) else null,
            ),
            listOfNotNull(
                if (canEditMetadata) TrackMenuItem(TrackMenuAction.EDIT_METADATA, EDIT_METADATA, false) else null,
            ),
            listOf(TrackMenuItem(TrackMenuAction.COLLECTION, collectionLabel(inCollection), false)),
            listOfNotNull(
                if (canGoToArtist) TrackMenuItem(TrackMenuAction.GO_TO_ARTIST, GO_TO_ARTIST, false) else null,
                if (canGoToAlbum) TrackMenuItem(TrackMenuAction.GO_TO_ALBUM, GO_TO_ALBUM, false) else null,
            ),
            listOfNotNull(
                if (canShare) TrackMenuItem(TrackMenuAction.SHARE, SHARE, false) else null,
            ),
        ).filter { it.isNotEmpty() }
        return groups.flatMapIndexed { g, group ->
            group.mapIndexed { i, item -> item.copy(startsGroup = g > 0 && i == 0) }
        }
    }
}
