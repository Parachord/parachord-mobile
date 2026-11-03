package com.parachord.shared.repository

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
