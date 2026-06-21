package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavior vectors for the pure N-way 3-way merge (Phase 0). These ARE the
 * cross-engine fixtures — the desktop JS merge must produce identical output
 * for the same inputs. Keep them simple + literal so they translate to JSON.
 */
class PlaylistMergeTest {

    private fun copy(id: String, tracks: List<String>, editedAt: Long) =
        PlaylistMergeCopy(id, tracks, editedAt)

    @Test
    fun noChangedCopies_returnsBaseline() {
        val base = listOf("a", "b", "c")
        // A copy equal to the baseline contributes no delta and is ignored.
        assertEquals(base, PlaylistMerge.merge(base, listOf(copy("spotify", base, 5))))
        assertEquals(base, PlaylistMerge.merge(base, emptyList()))
    }

    @Test
    fun singleAdd_appendsAddedTrack() {
        val base = listOf("a", "b")
        val result = PlaylistMerge.merge(base, listOf(copy("spotify", listOf("a", "b", "x"), 1)))
        assertEquals(listOf("a", "b", "x"), result)
    }

    @Test
    fun singleRemove_dropsTrack() {
        val base = listOf("a", "b", "c")
        val result = PlaylistMerge.merge(base, listOf(copy("spotify", listOf("a", "c"), 1)))
        assertEquals(listOf("a", "c"), result)
    }

    @Test
    fun concurrentAddsOnDifferentCopies_bothSurvive() {
        // Add X on Spotify, add Y on Apple Music — nothing lost.
        val base = listOf("a", "b")
        val result = PlaylistMerge.merge(
            base,
            listOf(
                copy("spotify", listOf("a", "b", "x"), 1),
                copy("applemusic", listOf("a", "b", "y"), 2),   // newer → sets order
            ),
        )
        // Winner (applemusic, t=2) order first: a, b, y; then x appended.
        assertEquals(listOf("a", "b", "y", "x"), result)
    }

    @Test
    fun concurrentAddAndRemove_differentKeys_bothApply() {
        // Remove A on one copy, add X on another.
        val base = listOf("a", "b")
        val result = PlaylistMerge.merge(
            base,
            listOf(
                copy("spotify", listOf("a", "b", "x"), 1),   // added x
                copy("applemusic", listOf("b"), 2),          // removed a (newer)
            ),
        )
        assertEquals(listOf("b", "x"), result)
    }

    @Test
    fun deletePropagates_evenIfAnotherCopyStillHasIt() {
        // A removed X (older); B still has X but DID a different edit (added Y).
        // Union-removes: a delete anywhere drops the track.
        val base = listOf("x", "z")
        val result = PlaylistMerge.merge(
            base,
            listOf(
                copy("spotify", listOf("z"), 5),             // removed x
                copy("applemusic", listOf("x", "z", "y"), 9), // kept x, added y (newer)
            ),
        )
        // x is removed (Spotify deleted it); y added; z survives.
        assertEquals(listOf("z", "y"), result)
    }

    @Test
    fun reorder_followsTheEditedCopysOrder() {
        val base = listOf("a", "b", "c")
        val result = PlaylistMerge.merge(base, listOf(copy("spotify", listOf("c", "a", "b"), 1)))
        assertEquals(listOf("c", "a", "b"), result)
    }

    @Test
    fun order_followsTheMostRecentlyEditedCopy() {
        val base = listOf("a", "b", "c")
        val result = PlaylistMerge.merge(
            base,
            listOf(
                copy("spotify", listOf("b", "a", "c"), 1),     // reorder, older
                copy("applemusic", listOf("c", "b", "a"), 9),  // reorder, newer → wins order
            ),
        )
        assertEquals(listOf("c", "b", "a"), result)
    }

    @Test
    fun emptyBaseline_firstMerge_unionsAdds() {
        val result = PlaylistMerge.merge(
            emptyList(),
            listOf(
                copy("spotify", listOf("a", "b"), 1),
                copy("listenbrainz", listOf("b", "c"), 2),   // newer → order
            ),
        )
        // Winner (lb, t=2): b, c; then a appended.
        assertEquals(listOf("b", "c", "a"), result)
    }

    @Test
    fun duplicateKeyAcrossCopies_appearsOnce() {
        val base = listOf("a")
        val result = PlaylistMerge.merge(
            base,
            listOf(
                copy("spotify", listOf("a", "x"), 1),
                copy("applemusic", listOf("a", "x"), 2),   // both added x
            ),
        )
        assertEquals(listOf("a", "x"), result)
    }

    @Test
    fun removeAll_yieldsEmpty() {
        val base = listOf("a", "b")
        val result = PlaylistMerge.merge(base, listOf(copy("spotify", emptyList(), 1)))
        assertEquals(emptyList(), result)
    }
}
