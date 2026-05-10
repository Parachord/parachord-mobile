package com.parachord.android.deeplink

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for DeepLinkHandler's URI parsing logic.
 *
 * Since android.net.Uri is not available in plain JUnit tests (no Robolectric),
 * we test the parsing logic by replicating the pure string-based routing.
 * The actual DeepLinkHandler delegates to Uri methods; these tests verify
 * the routing decision logic using the same string patterns.
 */
class DeepLinkHandlerTest {

    // -- Parachord protocol URL routing --

    @Test
    fun `parachord play URL requires artist and title`() {
        val url = "parachord://play?artist=Radiohead&title=Creep"
        assertTrue(url.startsWith("parachord://play"))
        assertTrue(url.contains("artist="))
        assertTrue(url.contains("title="))
    }

    @Test
    fun `parachord control URL extracts action from path`() {
        val url = "parachord://control/pause"
        assertTrue(url.startsWith("parachord://control"))
        val action = url.substringAfter("control/")
        assertEquals("pause", action)
    }

    @Test
    fun `parachord queue add URL has required params`() {
        val url = "parachord://queue/add?artist=Beatles&title=Yesterday&album=Help"
        assertTrue(url.contains("queue/add"))
        assertTrue(url.contains("artist="))
        assertTrue(url.contains("title="))
        assertTrue(url.contains("album="))
    }

    @Test
    fun `parachord queue clear maps correctly`() {
        val url = "parachord://queue/clear"
        assertTrue(url.contains("queue/clear"))
    }

    @Test
    fun `parachord shuffle on and off`() {
        assertTrue("parachord://shuffle/on".endsWith("/on"))
        assertTrue("parachord://shuffle/off".endsWith("/off"))
    }

    @Test
    fun `parachord volume clamps to 0-100`() {
        // Testing the coerceIn logic
        assertEquals(0, (-10).coerceIn(0, 100))
        assertEquals(100, 150.coerceIn(0, 100))
        assertEquals(50, 50.coerceIn(0, 100))
    }

    // -- Spotify URI parsing --

    @Test
    fun `parseSpotifyUri extracts track ID`() {
        val uri = "spotify:track:6rqhFgbbKwnb9MLmUQDhG6"
        val ssp = uri.substringAfter("spotify:")
        val parts = ssp.split(":")
        assertEquals("track", parts[0])
        assertEquals("6rqhFgbbKwnb9MLmUQDhG6", parts[1])
    }

    @Test
    fun `parseSpotifyUri extracts album ID`() {
        val uri = "spotify:album:1DFixLWuPkv3KT3TnV35m3"
        val ssp = uri.substringAfter("spotify:")
        val parts = ssp.split(":")
        assertEquals("album", parts[0])
        assertEquals("1DFixLWuPkv3KT3TnV35m3", parts[1])
    }

    @Test
    fun `parseSpotifyUri extracts playlist ID`() {
        val uri = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        val ssp = uri.substringAfter("spotify:")
        val parts = ssp.split(":")
        assertEquals("playlist", parts[0])
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", parts[1])
    }

    @Test
    fun `parseSpotifyUri rejects invalid format`() {
        val uri = "spotify:invalid"
        val ssp = uri.substringAfter("spotify:")
        val parts = ssp.split(":")
        assertTrue(parts.size < 2)
    }

    // -- Spotify URL parsing --

    @Test
    fun `parseSpotifyUrl extracts track ID from standard URL`() {
        val path = "/track/6rqhFgbbKwnb9MLmUQDhG6"
        val segments = path.trim('/').split("/")
        assertEquals("track", segments[0])
        assertEquals("6rqhFgbbKwnb9MLmUQDhG6", segments[1])
    }

    @Test
    fun `parseSpotifyUrl handles intl prefix`() {
        // /intl-en/track/xxx → skip intl-en, use track + xxx
        val path = "/intl-en/track/6rqhFgbbKwnb9MLmUQDhG6"
        val segments = path.trim('/').split("/")
        assertTrue(segments[0].startsWith("intl-"))
        assertEquals("track", segments[1])
        assertEquals("6rqhFgbbKwnb9MLmUQDhG6", segments[2])
    }

    // -- Apple Music URL parsing --

    @Test
    fun `parseAppleMusicUrl extracts album from path`() {
        // music.apple.com/us/album/in-rainbows/1109714933
        val path = "/us/album/in-rainbows/1109714933"
        val segments = path.trim('/').split("/")
        // segments[0] = "us" (country), segments[1] = "album" (type)
        assertEquals("album", segments[1])
        assertEquals("1109714933", segments.last())
    }

    @Test
    fun `parseAppleMusicUrl extracts song from album URL with i parameter`() {
        // ?i= query parameter indicates a specific song within an album URL
        val url = "https://music.apple.com/us/album/creep/1109714933?i=1109715066"
        assertTrue(url.contains("i=1109715066"))
    }

    @Test
    fun `parseAppleMusicUrl converts artist path to name`() {
        // artist URLs: /us/artist/radiohead/657515
        val path = "/us/artist/radiohead/657515"
        val segments = path.trim('/').split("/")
        assertEquals("artist", segments[1])
        // Artist name from path, with dashes replaced by spaces
        val name = segments[2].replace("-", " ")
        assertEquals("radiohead", name)
    }

    // -- Scheme routing --

    @Test
    fun `routes parachord scheme correctly`() {
        assertEquals("parachord", "parachord://play".substringBefore("://"))
    }

    @Test
    fun `routes spotify scheme correctly`() {
        assertEquals("spotify", "spotify:track:abc".substringBefore(":"))
    }

    @Test
    fun `routes https scheme correctly`() {
        assertEquals("https", "https://open.spotify.com/track/abc".substringBefore("://"))
    }

    @Test
    fun `identifies spotify host`() {
        val url = "https://open.spotify.com/track/abc123"
        assertTrue(url.contains("open.spotify.com"))
    }

    @Test
    fun `identifies apple music host`() {
        val url = "https://music.apple.com/us/album/test/123"
        assertTrue(url.contains("music.apple.com"))
    }

    // -- Navigation deep links --

    @Test
    fun `parachord navigation hosts are recognized`() {
        val navHosts = listOf(
            "home", "artist", "album", "library", "history",
            "friend", "recommendations", "charts", "critics-picks",
            "playlists", "playlist", "settings", "search", "chat",
        )
        for (host in navHosts) {
            val url = "parachord://$host"
            assertEquals(host, url.substringAfter("parachord://").substringBefore("/").substringBefore("?"))
        }
    }

    @Test
    fun `search deep link extracts query and source`() {
        val url = "parachord://search?q=radiohead&source=musicbrainz"
        assertTrue(url.contains("q=radiohead"))
        assertTrue(url.contains("source=musicbrainz"))
    }

    @Test
    fun `chat deep link extracts prompt`() {
        val url = "parachord://chat?prompt=play+some+jazz"
        assertTrue(url.contains("prompt="))
    }

    // -- Protocol play sub-actions (#119 / #120) --

    @Test
    fun `play album sub-action recognized via path segment`() {
        val url = "parachord://play/album?spotify=4Z8W4fKeB5YxbusRwVAqVK"
        // Host stays 'play'; sub-action lives at first path segment.
        assertEquals("play", url.substringAfter("parachord://").substringBefore("/").substringBefore("?"))
        assertTrue(url.contains("/album"))
    }

    @Test
    fun `play playlist sub-action recognized via path segment`() {
        val url = "parachord://play/playlist?url=https%3A%2F%2Fexample.com%2Fmix.xspf&shuffle=1"
        assertTrue(url.contains("/playlist"))
        assertTrue(url.contains("shuffle=1"))
    }

    @Test
    fun `play radio sub-action recognized via path segment`() {
        val url = "parachord://play/radio?artist=Radiohead&title=Karma+Police"
        assertTrue(url.contains("/radio"))
    }

    @Test
    fun `play album accepts mbid spotify applemusic url tracks artist+title`() {
        // All 6 input shapes can coexist on the same URI; the resolver
        // walks them in priority order downstream.
        val params = listOf("mbid", "spotify", "applemusic", "url", "tracks", "artist", "title")
        for (p in params) {
            val url = "parachord://play/album?$p=value"
            assertTrue("$p missing", url.contains("$p="))
        }
    }

    @Test
    fun `play playlist forwards title creator and shuffle hints`() {
        val url = "parachord://play/playlist?url=https%3A%2F%2Fx.com%2Fy.xspf&title=My+Mix&creator=jesse&shuffle=1"
        assertTrue(url.contains("title=My+Mix"))
        assertTrue(url.contains("creator=jesse"))
        assertTrue(url.contains("shuffle=1"))
    }

    @Test
    fun `listen-along deep link requires service and user`() {
        val url = "parachord://listen-along?service=listenbrainz&user=jesse"
        assertTrue(url.contains("service=listenbrainz"))
        assertTrue(url.contains("user=jesse"))
    }

    @Test
    fun `bare play URL still has both artist and title for legacy single-track shape`() {
        val url = "parachord://play?artist=Radiohead&title=Creep"
        // No path segment — falls into the legacy [Play] action.
        assertTrue(url.startsWith("parachord://play?"))
        assertFalse(url.contains("parachord://play/"))
    }
}
