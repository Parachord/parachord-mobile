package com.parachord.android.playback

import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.model.Track
import com.parachord.shared.playback.scrobbler.ScrobbleManager
import com.parachord.shared.playback.scrobbler.ScrobbleState
import com.parachord.shared.playback.scrobbler.Scrobbler
import com.parachord.shared.settings.SettingsStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * #347 — the "now playing" lifecycle that keeps a ListenBrainz playing-now entry
 * from sticking. LB expires playing-now one track-length after the last
 * submission, so the manager must (a) send the length in MILLISECONDS from the
 * engine, (b) re-send once when that length first becomes known, and (c) never
 * re-send while paused or stopped — otherwise the entry is refreshed forever.
 */
class ScrobbleNowPlayingLifecycleTest {

    /** Records each now-playing call's duration (null = sent before the engine knew it). */
    private class RecordingScrobbler : Scrobbler {
        override val id = "fake"
        override val displayName = "Fake"
        val nowPlaying: MutableList<Long?> = Collections.synchronizedList(mutableListOf())
        override suspend fun isEnabled() = true
        override suspend fun sendNowPlaying(track: Track, durationMs: Long?) { nowPlaying += durationMs }
        override suspend fun submitScrobble(track: Track, timestamp: Long, durationMs: Long?) {}
    }

    private val scrobbler = RecordingScrobbler()
    private val state = MutableStateFlow(ScrobbleState(currentTrack = null, isPlaying = false, position = 0, duration = 0))

    private val manager = ScrobbleManager(
        settingsStore = mockk { every { scrobblingEnabled } returns flowOf(true) },
        playbackStateFlow = state,
        scrobblers = setOf(scrobbler),
        trackDao = mockk(relaxed = true),
        mbidEnrichment = mockk<MbidEnrichmentService> {
            coEvery { getCanonicalNames(any(), any()) } returns null
            coEvery { getRecordingMbid(any(), any(), any()) } returns null
        },
    )

    // A recording MBID skips the DB re-read; the rest of enrichment is mocked out.
    private val song = Track(id = "t1", title = "Another Beautiful Day", artist = "Courtney Barnett", recordingMbid = "m1")

    /** Push a state and give the manager's background scope time to process it. */
    private suspend fun emit(s: ScrobbleState) {
        state.value = s
        delay(150)
    }

    private suspend fun awaitNowPlayingCount(n: Int) = withTimeout(3_000) {
        while (scrobbler.nowPlaying.size < n) delay(20)
    }

    @Test
    fun sendsOnceThenOnceMoreWithTheEngineLengthInMs_andNeverWhilePausedOrStopped() = runBlocking {
        manager.startObserving()

        // Track starts before the engine reports a length → one send, no length.
        emit(ScrobbleState(song, isPlaying = true, position = 0, duration = 0))
        awaitNowPlayingCount(1)

        // Engine reports 325 000 ms (5:25) → exactly one re-send, in ms (not 325).
        emit(ScrobbleState(song, isPlaying = true, position = 1_000, duration = 325_000))
        awaitNowPlayingCount(2)

        // Ordinary playback ticks, a pause, more paused ticks, a resume, a stop:
        // none of these refresh the playing-now entry.
        emit(ScrobbleState(song, isPlaying = true, position = 5_000, duration = 325_000))
        emit(ScrobbleState(song, isPlaying = false, position = 6_000, duration = 325_000))
        emit(ScrobbleState(song, isPlaying = false, position = 6_000, duration = 325_000))
        emit(ScrobbleState(song, isPlaying = true, position = 6_500, duration = 325_000))
        emit(ScrobbleState(null, isPlaying = false, position = 0, duration = 0))
        delay(300)

        assertEquals(listOf(null, 325_000L), scrobbler.nowPlaying.toList())
    }

    @Test
    fun aTrackLoadedPausedSendsNothingUntilItPlays() = runBlocking {
        manager.startObserving()

        emit(ScrobbleState(song, isPlaying = false, position = 0, duration = 325_000))
        delay(300)
        assertEquals(emptyList<Long?>(), scrobbler.nowPlaying.toList())

        emit(ScrobbleState(song, isPlaying = true, position = 0, duration = 325_000))
        awaitNowPlayingCount(1)
        delay(300)
        assertEquals(listOf(325_000L), scrobbler.nowPlaying.toList())
    }
}
