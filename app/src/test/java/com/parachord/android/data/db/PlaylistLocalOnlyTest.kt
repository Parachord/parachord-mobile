package com.parachord.android.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistLocalOnlyTest {
    private fun insertBarePlaylist(id: String, localOnly: Long = 0L) =
        TestDatabaseFactory.create().also { db ->
            db.playlistQueries.insert(
                id = id,
                name = "My List",
                description = null,
                artworkUrl = null,
                trackCount = 0,
                createdAt = 1L,
                updatedAt = 1L,
                spotifyId = null,
                snapshotId = null,
                lastModified = 1L,
                locallyModified = 0L,
                ownerName = null,
                sourceUrl = null,
                sourceContentHash = null,
                localOnly = localOnly,
                writable = 1L,
            )
        }

    @Test
    fun `localOnly defaults to 0 when not explicitly set via setLocalOnly`() {
        val db = insertBarePlaylist("local-abc")
        val row = db.playlistQueries.getById("local-abc").executeAsOne()
        assertEquals(0L, row.localOnly)
    }

    @Test
    fun `setLocalOnly flips the flag`() {
        val db = insertBarePlaylist("local-abc")
        db.playlistQueries.setLocalOnly(1L, "local-abc")
        val row = db.playlistQueries.getById("local-abc").executeAsOne()
        assertEquals(1L, row.localOnly)
    }
}
