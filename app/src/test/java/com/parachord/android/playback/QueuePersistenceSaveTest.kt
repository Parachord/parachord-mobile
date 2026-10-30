package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The queue is saved when the track or the queue changes, not on every
 * playback position update. The position changes every 500 ms while a song
 * plays, and keying the save on the whole playback state re-wrote the full
 * queue about once a second.
 *
 * Real delays (runBlocking): the save is debounced on wall-clock time.
 */
class QueuePersistenceSaveTest {

    private val track = TrackEntity(id = "t1", title = "Jenny Says", artist = "Cowboy Mouth")

    @Test fun `position updates don't save the queue`() = runBlocking {
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        every { settingsStore.persistQueue } returns flowOf(true)
        coEvery { settingsStore.setPersistedQueueState(any()) } returns Unit
        val stateHolder = PlaybackStateHolder()
        stateHolder.update { copy(currentTrack = track) }

        val persistence = QueuePersistence(settingsStore, QueueManager(), stateHolder, Json)
        persistence.startObserving()
        delay(800)
        coVerify(exactly = 1) { settingsStore.setPersistedQueueState(any()) }

        // Three seconds of playback: a position update every 500 ms.
        repeat(6) { i ->
            stateHolder.update { copy(position = (i + 1) * 500L, isPlaying = true) }
            delay(500)
        }
        delay(800)
        coVerify(exactly = 1) { settingsStore.setPersistedQueueState(any()) }

        // A new track is saved.
        stateHolder.update { copy(currentTrack = track.copy(id = "t2", title = "Hurricane")) }
        delay(800)
        coVerify(exactly = 2) { settingsStore.setPersistedQueueState(any()) }
    }
}
