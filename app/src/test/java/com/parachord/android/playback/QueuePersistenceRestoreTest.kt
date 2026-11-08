package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The saved queue is put back only when nothing has started playing. Android
 * Auto can start the app with the phone locked and play before the restore
 * has read storage; the restore then put the last session's queue over it.
 */
class QueuePersistenceRestoreTest {

    private val saved = TrackEntity(id = "old", title = "Kismet Kill", artist = "Swapmeet")
    private val savedNext = TrackEntity(id = "old-next", title = "Mount Zero", artist = "Swapmeet")
    private val autoNext = TrackEntity(id = "auto-next", title = "Myth", artist = "Beach House")

    private fun persistence(queueManager: QueueManager): QueuePersistence {
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        coEvery { settingsStore.isPersistQueueEnabled() } returns true
        coEvery { settingsStore.getPersistedQueueState() } returns Json.encodeToString(
            PersistedQueueState.serializer(),
            PersistedQueueState(
                currentTrack = saved.toSerializable(),
                upNext = listOf(savedNext.toSerializable()),
            ),
        )
        return QueuePersistence(settingsStore, queueManager, PlaybackStateHolder(), Json)
    }

    @Test fun `a fresh start restores the saved queue`() = runBlocking {
        val queueManager = QueueManager()
        val restored = persistence(queueManager).restoreIfEnabled(canApply = { true })

        assertEquals("old", restored?.id)
        assertEquals(listOf("old-next"), queueManager.snapshot.value.upNext.map { it.id })
    }

    @Test fun `a play already started keeps its queue`() = runBlocking {
        val queueManager = QueueManager()
        // What Android Auto's tap set up while the restore was reading storage.
        queueManager.setQueue(
            listOf(TrackEntity(id = "auto", title = "Dare Me", artist = "DIIV"), autoNext),
            startIndex = 0,
            context = null,
            shuffle = false,
        )

        val restored = persistence(queueManager).restoreIfEnabled(canApply = { false })

        assertNull(restored)
        assertEquals(listOf("auto-next"), queueManager.snapshot.value.upNext.map { it.id })
    }
}
