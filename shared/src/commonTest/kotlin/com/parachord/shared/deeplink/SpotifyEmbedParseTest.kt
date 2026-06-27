package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Spotify editorial-playlist embed fallback (parachord-mobile#286). Verifies
 * `parseSpotifyEmbed` against the real `__NEXT_DATA__` shape
 * (`props.pageProps.state.data.entity.{name,trackList[].{title,subtitle}}`),
 * captured from `open.spotify.com/embed/playlist/<id>`.
 */
class SpotifyEmbedParseTest {

    private fun page(nextData: String) =
        """<!doctype html><html><body><script id="__NEXT_DATA__" type="application/json">$nextData</script></body></html>"""

    @Test fun parsesNameAndTrackList() {
        val nd = """{"props":{"pageProps":{"state":{"data":{"entity":{
            "name":"Today's Top Hits",
            "trackList":[
              {"uri":"spotify:track:1","title":"Song A","subtitle":"Artist A","duration":1000},
              {"uri":"spotify:track:2","title":"Song B","subtitle":"Artist B, Artist C"},
              {"uri":"spotify:track:3","title":"","subtitle":"Blank Title Skipped"},
              {"uri":"spotify:track:4","subtitle":"No Title Key Skipped"}
            ]}}}}}}"""
        val r = parseSpotifyEmbed(page(nd))
        assertNotNull(r)
        assertEquals("Today's Top Hits", r.displayName)
        assertEquals(2, r.tracks.size) // blank/missing-title rows dropped
        assertEquals("Song A", r.tracks[0].title)
        assertEquals("Artist A", r.tracks[0].artist)
        assertEquals("Artist B, Artist C", r.tracks[1].artist)
    }

    @Test fun fallsBackToTitleFieldForName() {
        val nd = """{"props":{"pageProps":{"state":{"data":{"entity":{
            "title":"By Title Field","trackList":[{"title":"X","subtitle":"Y"}]}}}}}}"""
        assertEquals("By Title Field", parseSpotifyEmbed(page(nd))?.displayName)
    }

    @Test fun nullWhenNoNextDataScript() {
        assertNull(parseSpotifyEmbed("<html><body>no embed here</body></html>"))
    }

    @Test fun nullWhenNoTrackList() {
        val nd = """{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Empty"}}}}}}"""
        assertNull(parseSpotifyEmbed(page(nd)))
    }

    @Test fun nullWhenTrackListAllUnusable() {
        val nd = """{"props":{"pageProps":{"state":{"data":{"entity":{
            "name":"Bad","trackList":[{"uri":"x"},{"title":"","subtitle":""}]}}}}}}"""
        assertNull(parseSpotifyEmbed(page(nd)))
    }

    @Test fun nullWhenJsonMalformed() {
        assertNull(parseSpotifyEmbed(page("{not valid json")))
    }
}
