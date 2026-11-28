package com.parachord.android.playback

import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.metadata.RecordingMbidLookup
import com.parachord.shared.model.Track
import com.parachord.shared.playback.scrobbler.ScrobbleManager
import com.parachord.shared.playback.scrobbler.ScrobbleState
import com.parachord.shared.playback.scrobbler.Scrobbler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections

/**
 * #853: a play that reaches the scrobble with no MBIDs (iOS, a recommendation,
 * a weekly playlist) scrobbles the release and artist the lookup found, and a
 * lookup that doesn't answer doesn't hold the scrobble.
 */
class ScrobbleMbidIdentityTest {
    private class Recorder : Scrobbler {
        override val id = "fake"
        override val displayName = "Fake"
        val scrobbled: MutableList<Track> = Collections.synchronizedList(mutableListOf())
        override suspend fun isEnabled() = true
        override suspend fun sendNowPlaying(track: Track, durationMs: Long?) {}
        override suspend fun submitScrobble(track: Track, timestamp: Long, durationMs: Long?) { scrobbled += track }
    }

    private val recording = "8cd0b0e3-4bf6-4f5c-9d0a-9f84f8bcd8f4"
    private val release = "76df3287-6cda-33eb-8e9a-044b5e15ffdd"
    private val song = Track(id = "rec-1", title = "Roads", artist = "Portishead")

    private fun manager(scrobbler: Scrobbler, state: MutableStateFlow<ScrobbleState>, lookup: suspend () -> RecordingMbidLookup) =
        ScrobbleManager(
            settingsStore = mockk { every { scrobblingEnabled } returns flowOf(true) },
            playbackStateFlow = state,
            scrobblers = setOf(scrobbler),
            trackDao = mockk { coEvery { getById(any()) } returns null },
            mbidEnrichment = mockk<MbidEnrichmentService> {
                coEvery { getCanonicalNames(any(), any()) } returns null
                coEvery { lookupRecordingMbid(any(), any(), any(), any()) } coAnswers { lookup() }
            },
            mbidLookupBudgetMs = 300,
        )

    private suspend fun playPastThreshold(state: MutableStateFlow<ScrobbleState>) {
        state.value = ScrobbleState(song, isPlaying = true, position = 0, duration = 200_000)
        delay(100)
        state.value = ScrobbleState(song, isPlaying = true, position = 120_000, duration = 200_000)
    }

    @Test
    fun theScrobbleCarriesTheReleaseTheLookupFound() = runBlocking {
        val scrobbler = Recorder()
        val state = MutableStateFlow(ScrobbleState(null, isPlaying = false, position = 0, duration = 0))
        manager(scrobbler, state) { RecordingMbidLookup.Found(recording, release, null) }.startObserving()
        playPastThreshold(state)
        withTimeout(3_000) { while (scrobbler.scrobbled.isEmpty()) delay(20) }
        assertEquals(recording, scrobbler.scrobbled.single().recordingMbid)
        assertEquals(release, scrobbler.scrobbled.single().releaseMbid)
    }

    @Test
    fun aLookupThatDoesntAnswerDoesntHoldTheScrobble() = runBlocking {
        val scrobbler = Recorder()
        val state = MutableStateFlow(ScrobbleState(null, isPlaying = false, position = 0, duration = 0))
        manager(scrobbler, state) { delay(60_000); RecordingMbidLookup.Found(recording, release, null) }.startObserving()
        playPastThreshold(state)
        // Now playing and the scrobble each wait at most the 300 ms budget.
        withTimeout(2_500) { while (scrobbler.scrobbled.isEmpty()) delay(20) }
        assertNull(scrobbler.scrobbled.single().releaseMbid)
    }
}
