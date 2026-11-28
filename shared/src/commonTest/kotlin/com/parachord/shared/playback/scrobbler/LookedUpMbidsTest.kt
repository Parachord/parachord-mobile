package com.parachord.shared.playback.scrobbler

import com.parachord.shared.metadata.RecordingMbidLookup
import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The scrobble-time lookup fills a listen's missing MBIDs and nothing else (#853). */
class LookedUpMbidsTest {
    private val recording = "8cd0b0e3-4bf6-4f5c-9d0a-9f84f8bcd8f4"
    private val release = "76df3287-6cda-33eb-8e9a-044b5e15ffdd"
    private val artist = "8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11"
    private val other = "48140466-cff6-3222-bd55-63c27e43190d"
    private val found = RecordingMbidLookup.Found(recording, release, artist)
    private val track = Track(id = "t", title = "Roads", artist = "Portishead")

    @Test fun aTrackWithNoIdentityTakesTheWholeAnswer() {
        val t = withLookedUpMbids(track, found)
        assertEquals(listOf(recording, release, artist), listOf(t.recordingMbid, t.releaseMbid, t.artistMbid))
    }

    @Test fun anIdAlreadyOnTheTrackWins() {
        val t = withLookedUpMbids(track.copy(recordingMbid = recording, releaseMbid = other), found)
        assertEquals(other, t.releaseMbid)
        assertEquals(artist, t.artistMbid)
    }

    @Test fun anotherRecordingsReleaseIsNeverTaken() {
        val t = withLookedUpMbids(track.copy(recordingMbid = other), found)
        assertEquals(other, t.recordingMbid)
        assertNull(t.releaseMbid)
        assertNull(t.artistMbid)
    }

    @Test fun anIsrcAnswerCarriesOnlyTheRecording() {
        val t = withLookedUpMbids(track, RecordingMbidLookup.Found(recording))
        assertEquals(recording, t.recordingMbid)
        assertNull(t.releaseMbid)
    }

    @Test fun aValueThatIsntAnMbidIsIgnored() {
        assertEquals(track, withLookedUpMbids(track, RecordingMbidLookup.Found("not-an-mbid", release)))
        assertNull(withLookedUpMbids(track, RecordingMbidLookup.Found(recording, "x", "y")).releaseMbid)
    }
}
