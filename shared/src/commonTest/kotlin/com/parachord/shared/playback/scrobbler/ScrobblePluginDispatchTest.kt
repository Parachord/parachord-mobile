package com.parachord.shared.playback.scrobbler

import com.parachord.shared.model.Track
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for the achordion `.axe` dispatch payload.
 *
 * The achordion `.axe` tier-2 submit keys on EITHER `mbid` OR `isrc` read off
 * the dispatched track JSON, and logs "no MBID or ISRC — cannot key submit"
 * when both are absent from the payload. [ScrobblePluginDispatch.buildTrackJson]
 * once serialized `mbid` but not `isrc`, so an Apple-Music-streamed track that
 * carried only an ISRC (no MBID, e.g. during a mapper outage) could never be
 * submitted via the `.axe` path even though the native Track had the key. These
 * tests pin the ISRC into the payload so that gap can't silently return.
 */
class ScrobblePluginDispatchTest {

    private fun track(mbid: String? = null, isrc: String? = null) = Track(
        id = "t1",
        title = "June Guitar",
        artist = "Alex G",
        appleMusicId = "1440935467",
        recordingMbid = mbid,
        isrc = isrc,
    )

    @Test
    fun buildTrackJson_includesIsrc_whenPresent() {
        val json = ScrobblePluginDispatch.buildTrackJson(track(isrc = "USRC12500400"))
        assertTrue("isrc" in json, "isrc key must be in the dispatched payload")
        assertEquals("USRC12500400", json["isrc"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildTrackJson_omitsIsrc_whenAbsent() {
        // Omitted (not null) so achordion's `track.isrc` is `undefined`, matching
        // the desktop track shape — never an explicit null.
        val json = ScrobblePluginDispatch.buildTrackJson(track(isrc = null))
        assertFalse("isrc" in json, "isrc key must be omitted when the track has none")
    }

    @Test
    fun buildTrackJson_carriesBothMbidAndIsrc_independently() {
        val json = ScrobblePluginDispatch.buildTrackJson(
            track(mbid = "11111111-1111-1111-1111-111111111111", isrc = "USRC12500400"),
        )
        assertEquals("11111111-1111-1111-1111-111111111111", json["mbid"]?.jsonPrimitive?.content)
        assertEquals("USRC12500400", json["isrc"]?.jsonPrimitive?.content)
    }
}
