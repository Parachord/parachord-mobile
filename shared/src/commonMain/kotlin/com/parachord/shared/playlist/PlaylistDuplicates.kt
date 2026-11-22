package com.parachord.shared.playlist

import com.parachord.shared.format.countOf
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.sync.TrackKeys
import com.parachord.shared.sync.trackKeysOf
import com.parachord.shared.sync.unifyTrackKeys

/**
 * A playlist's duplicate tracks: later copies of a song already in it.
 *
 * "The same song" is the rule sync uses to match a track across services
 * ([unifyTrackKeys]): the same ISRC or recording MBID, else the same artist and
 * title with a trailing remaster tag ignored. Live, remix, acoustic and "feat."
 * titles stay apart. A row with no artist, title or id is never a duplicate.
 *
 * The first copy of each song stays, in its place; every later copy goes.
 */
object PlaylistDuplicates {

    /** Positions (as stored on the rows) of the copies that would be removed. */
    fun duplicatePositions(tracks: List<PlaylistTrack>): Set<Int> {
        val ordered = tracks.sortedBy { it.position }
        val keys = ordered.mapIndexed { index, t ->
            val k = trackKeysOf(
                isrc = t.trackIsrc,
                recordingMbid = t.trackRecordingMbid,
                artist = t.trackArtist,
                title = t.trackTitle,
            )
            // A blank row would otherwise share the empty "|" name with every
            // other blank row. Give it a name no other row can have.
            if (k.isrc == null && k.mbid == null && k.norm == "|") TrackKeys(null, null, "\u0000blank-$index") else k
        }
        val representatives = unifyTrackKeys(listOf(keys)).first()
        val seen = HashSet<String>()
        val dupes = HashSet<Int>()
        ordered.forEachIndexed { index, t ->
            if (!seen.add(representatives[index])) dupes.add(t.position)
        }
        return dupes
    }

    /**
     * Whether the playlist page offers the banner: there are duplicates and the
     * playlist is ours to edit. A hosted XSPF ([Playlist.sourceUrl]) is a copy of
     * its feed, which the next poll would restore, so it never offers it.
     */
    fun offersRemoval(playlist: com.parachord.shared.model.Playlist?, duplicateCount: Int): Boolean =
        playlist != null && playlist.sourceUrl == null && duplicateCount > 0

    /** How many copies [withoutDuplicates] would remove. */
    fun count(tracks: List<PlaylistTrack>): Int = duplicatePositions(tracks).size

    /** The playlist with every later copy removed, renumbered from 0 in order. */
    fun withoutDuplicates(tracks: List<PlaylistTrack>): List<PlaylistTrack> {
        val dupes = duplicatePositions(tracks)
        return tracks.sortedBy { it.position }
            .filter { it.position !in dupes }
            .mapIndexed { index, t -> t.copy(position = index) }
    }
}

/** The words for the playlist page's duplicates banner, on both apps. */
object PlaylistDuplicateCopy {
    const val REMOVE = "Remove duplicates"
    const val CANCEL = "Cancel"

    /** "3 duplicate tracks in this playlist". */
    fun banner(count: Int): String = "${countOf(count, "duplicate track")} in this playlist"

    /** "Remove 3 duplicate tracks?" */
    fun confirmTitle(count: Int): String = "Remove ${countOf(count, "duplicate track")}?"

    /** What the confirmation says happens. */
    fun confirmBody(playlistName: String): String =
        "The first copy of each track stays in “$playlistName”. Synced copies on other services update on the next sync."

    /** "Removed 3 duplicate tracks". */
    fun done(count: Int): String = "Removed ${countOf(count, "duplicate track")}"
}
