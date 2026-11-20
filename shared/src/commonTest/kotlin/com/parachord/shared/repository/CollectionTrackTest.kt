package com.parachord.shared.repository

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionTrackTest {

    private val now = 1_800_000_000_000L
    private val playing = Track(id = "q-1", title = "Run Away From the Mob", artist = "Queens of the Stone Age")

    @Test
    fun aNewLoveIsDatedNow() {
        assertEquals(0L, playing.addedAt)
        assertEquals(now, collectionTrack(playing, existing = null, nowMs = now).addedAt)
    }

    @Test
    fun aNewLoveIgnoresTheDateTheQueuedTrackCarried() {
        val queued = playing.copy(addedAt = 1_000L)
        assertEquals(now, collectionTrack(queued, existing = null, nowMs = now).addedAt)
    }

    @Test
    fun aTrackAlreadyInTheCollectionKeepsItsDate() {
        val saved = playing.copy(id = "lib-1", addedAt = 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, collectionTrack(playing, existing = saved, nowMs = now).addedAt)
    }

    // #705: a scanned local file is in the library, not the collection.
    private val scanned = Track(id = "local-7", title = "Clover", artist = "Tundra 212", resolver = "localfiles", inCollection = false, addedAt = 9L)

    @Test
    fun aLoveIsInTheCollection() {
        assertTrue(collectionTrack(scanned, existing = null, nowMs = now).inCollection)
    }

    @Test
    fun scannedAndImportedFilesAreRecognised() {
        assertTrue(isScannedLocalFile(scanned))
        assertTrue(isScannedLocalFile(scanned.copy(id = "fileimport-1")))
        assertFalse(isScannedLocalFile(scanned.copy(resolver = "spotify")))
        assertFalse(isScannedLocalFile(scanned.copy(id = "spotify-1")))
    }

    @Test
    fun recentLovesLeaveOutUnlovedFiles() {
        val loved = playing.copy(id = "a", addedAt = 1L)
        val newer = playing.copy(id = "b", addedAt = 3L)
        assertEquals(listOf("b", "a"), recentLoves(listOf(loved, scanned, newer), 10).map { it.id })
        assertEquals(listOf("b"), recentLoves(listOf(loved, scanned, newer), 1).map { it.id })
        assertEquals(listOf("a"), lovedTracks(listOf(loved, scanned)).map { it.id })
    }

    @Test
    fun recentLovesNewestFirstAndUndatedLast() {
        val t = listOf(
            playing.copy(id = "x"), playing.copy(id = "a", addedAt = 1L),
            playing.copy(id = "y"), playing.copy(id = "b", addedAt = 5L),
        )
        assertEquals(listOf("b", "a", "x", "y"), recentLoves(t, 50).map { it.id })
        assertEquals(listOf("b", "a"), recentLoves(t, 2).map { it.id })
    }
}
