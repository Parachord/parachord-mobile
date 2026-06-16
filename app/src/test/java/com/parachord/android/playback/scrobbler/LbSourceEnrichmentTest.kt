package com.parachord.android.playback.scrobbler

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.playback.scrobbler.deriveLbSourceEnrichment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [deriveLbSourceEnrichment] — the pure helper that builds the
 * ListenBrainz `additional_info` source/MBID enrichment fields from a played
 * [TrackEntity]. Mirrors the desktop's `_deriveSourceEnrichment(track)` in
 * parachord-desktop/scrobbler-loader.js, adapted to Android's flat Track
 * model (no `sources` map — the scrobbler receives the single resolved row,
 * whose `resolver` is the played source and whose flat IDs are that source's
 * IDs). Issue #170.
 *
 * Confidence note: a played/resolved Room row is implicitly the high-confidence
 * winner (MIN_CONFIDENCE_THRESHOLD already filtered <0.60 out of selection, and
 * direct-ID hints stamp 0.95). The scrobble threshold itself is the empirical
 * confidence signal, so the helper has no explicit confidence gate.
 */
class LbSourceEnrichmentTest {

    private val RECORDING_MBID = "5b11f4ce-a62d-471e-81fc-a69a8278c7da"
    private val ARTIST_MBID = "f59c5520-5f46-4d2c-b2c4-822eccc4c8d3"
    private val RELEASE_MBID = "a1b2c3d4-1234-4567-89ab-cdef01234567"

    private fun track(
        resolver: String? = null,
        spotifyId: String? = null,
        appleMusicId: String? = null,
        soundcloudId: String? = null,
        sourceUrl: String? = null,
        recordingMbid: String? = null,
        artistMbid: String? = null,
        releaseMbid: String? = null,
    ) = TrackEntity(
        id = "t1",
        title = "Song",
        artist = "Artist",
        album = "Album",
        resolver = resolver,
        spotifyId = spotifyId,
        appleMusicId = appleMusicId,
        soundcloudId = soundcloudId,
        sourceUrl = sourceUrl,
        recordingMbid = recordingMbid,
        artistMbid = artistMbid,
        releaseMbid = releaseMbid,
    )

    @Test
    fun `direct-ID spotify match yields origin_url + service + spotify_id`() {
        val e = deriveLbSourceEnrichment(
            track(resolver = "spotify", spotifyId = "6rqhFgbbKwnb9MLmUQDhG6"),
        )
        assertEquals("https://open.spotify.com/track/6rqhFgbbKwnb9MLmUQDhG6", e.originUrl)
        assertEquals("spotify.com", e.musicService)
        assertEquals("Spotify", e.musicServiceName)
        assertEquals("https://open.spotify.com/track/6rqhFgbbKwnb9MLmUQDhG6", e.spotifyId)
    }

    @Test
    fun `applemusic played yields apple origin_url + service, no spotify_id`() {
        val e = deriveLbSourceEnrichment(
            track(resolver = "applemusic", appleMusicId = "1440935467"),
        )
        assertEquals("https://music.apple.com/us/song/1440935467", e.originUrl)
        assertEquals("music.apple.com", e.musicService)
        assertEquals("Apple Music", e.musicServiceName)
        assertNull(e.spotifyId)
    }

    @Test
    fun `spotify_id is emitted from a non-played source`() {
        // Played via Apple Music, but a verified Spotify ID is also on the row
        // (e.g. cross-resolver backfill). spotify_id is a cross-platform anchor
        // independent of which source played.
        val e = deriveLbSourceEnrichment(
            track(resolver = "applemusic", appleMusicId = "1440935467", spotifyId = "abc123"),
        )
        assertEquals("https://music.apple.com/us/song/1440935467", e.originUrl)
        assertEquals("Apple Music", e.musicServiceName)
        assertEquals("https://open.spotify.com/track/abc123", e.spotifyId)
    }

    @Test
    fun `soundcloud played with http sourceUrl yields origin_url`() {
        val e = deriveLbSourceEnrichment(
            track(
                resolver = "soundcloud",
                soundcloudId = "999",
                sourceUrl = "https://soundcloud.com/artist/track",
            ),
        )
        assertEquals("https://soundcloud.com/artist/track", e.originUrl)
        assertEquals("soundcloud.com", e.musicService)
        assertEquals("SoundCloud", e.musicServiceName)
    }

    @Test
    fun `soundcloud played with non-http sourceUrl omits origin_url and service`() {
        // origin_url / music_service / music_service_name are tied 1:1 — a
        // non-shareable URL drops all three.
        val e = deriveLbSourceEnrichment(
            track(
                resolver = "soundcloud",
                soundcloudId = "999",
                sourceUrl = "content://media/audio/999",
            ),
        )
        assertNull(e.originUrl)
        assertNull(e.musicService)
        assertNull(e.musicServiceName)
    }

    @Test
    fun `localfiles played omits source fields but keeps MBIDs`() {
        val e = deriveLbSourceEnrichment(
            track(
                resolver = "localfiles",
                sourceUrl = "file:///storage/emulated/0/Music/song.mp3",
                recordingMbid = RECORDING_MBID,
            ),
        )
        assertNull(e.originUrl)
        assertNull(e.musicService)
        assertNull(e.musicServiceName)
        assertNull(e.spotifyId)
        assertEquals(RECORDING_MBID, e.recordingMbid)
    }

    @Test
    fun `no-MBID track yields source fields but no MBIDs`() {
        val e = deriveLbSourceEnrichment(
            track(resolver = "spotify", spotifyId = "xyz"),
        )
        assertEquals("https://open.spotify.com/track/xyz", e.originUrl)
        assertNull(e.recordingMbid)
        assertNull(e.releaseMbid)
        assertTrue(e.artistMbids.isEmpty())
    }

    @Test
    fun `no-sources track yields only MBIDs`() {
        val e = deriveLbSourceEnrichment(
            track(
                resolver = null,
                recordingMbid = RECORDING_MBID,
                artistMbid = ARTIST_MBID,
                releaseMbid = RELEASE_MBID,
            ),
        )
        assertNull(e.originUrl)
        assertNull(e.musicService)
        assertNull(e.spotifyId)
        assertEquals(RECORDING_MBID, e.recordingMbid)
        assertEquals(RELEASE_MBID, e.releaseMbid)
        assertEquals(listOf(ARTIST_MBID), e.artistMbids)
    }

    @Test
    fun `invalid MBIDs are filtered out`() {
        val e = deriveLbSourceEnrichment(
            track(
                resolver = "spotify",
                spotifyId = "x",
                recordingMbid = "not-a-uuid",
                artistMbid = "also-bad",
                releaseMbid = "12345",
            ),
        )
        assertNull(e.recordingMbid)
        assertNull(e.releaseMbid)
        assertTrue(e.artistMbids.isEmpty())
    }
}
