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
import com.parachord.shared.playback.PlaybackResumePosition
import com.parachord.shared.playback.SavedPlaybackPosition
import org.junit.Assert.assertEquals
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
        coEvery { settingsStore.isPersistQueueEnabled() } returns true
        coEvery { settingsStore.setPersistedQueueState(any()) } returns Unit
        val stateHolder = PlaybackStateHolder()
        stateHolder.update { copy(currentTrack = track) }

        val persistence = QueuePersistence(settingsStore, QueueManager(), stateHolder)
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

    /** #567: the position is saved on its own when playback pauses, so a restored queue resumes there. */
    @Test fun `a pause saves the position, not the queue`() = runBlocking {
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        every { settingsStore.persistQueue } returns flowOf(true)
        coEvery { settingsStore.isPersistQueueEnabled() } returns true
        val saved = mutableListOf<String>()
        coEvery { settingsStore.setPersistedPlaybackPosition(capture(saved)) } returns Unit
        val stateHolder = PlaybackStateHolder()
        stateHolder.update { copy(currentTrack = track, isPlaying = true, position = 1_000L, duration = 200_000L) }

        val persistence = QueuePersistence(settingsStore, QueueManager(), stateHolder)
        persistence.startObserving()
        delay(800)
        saved.clear() // the first playing update counts as 15 s since no save

        stateHolder.update { copy(position = 1_500L) }
        delay(100)
        assertEquals(0, saved.size)

        stateHolder.update { copy(position = 95_000L, isPlaying = false) }
        delay(100)
        assertEquals(1, saved.size)
        val position = PlaybackResumePosition.decode(saved.single())
        assertEquals(SavedPlaybackPosition("t1", 95_000L, 200_000L), position)
        coVerify(exactly = 1) { settingsStore.setPersistedQueueState(any()) }
    }
}
