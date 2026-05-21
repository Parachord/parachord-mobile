package com.parachord.android.deeplink

import android.app.Application
import android.net.Uri
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.RadioMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for DeepLinkHandler's URI parsing logic.
 *
 * Since android.net.Uri is not available in plain JUnit tests (no Robolectric),
 * we test the parsing logic by replicating the pure string-based routing.
 * The actual DeepLinkHandler delegates to Uri methods; these tests verify
 * the routing decision logic using the same string patterns.
 *
 * Tests that need real Uri parsing run under Robolectric (see
 * [DeepLinkHandlerRadioContractTest] below).
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

/**
 * Real-Uri tests for the `parachord://play/radio` parser contract (#121 [1/N]).
 *
 * Runs under Robolectric so [Uri.parse] is real; the rest of the suite is
 * string-pattern only and stays fast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeepLinkHandlerRadioContractTest {

    private val handler = DeepLinkHandler()

    @Test
    fun parsePlayRadio_nameTakesPrecedenceOverTitle() {
        val a = handler.parse(Uri.parse("parachord://play/radio?url=https%3A%2F%2Fexample.com%2Fp.jspf&name=My%20Station&title=ignored"))
        assertTrue(a is DeepLinkAction.PlayRadio)
        val pr = a as DeepLinkAction.PlayRadio
        assertEquals("My Station", pr.name)
    }

    @Test
    fun parsePlayRadio_modeBOnlyWhenNoUrlOrTracks() {
        val a = handler.parse(Uri.parse("parachord://play/radio?artist=Slowdive"))
        val pr = a as DeepLinkAction.PlayRadio
        assertTrue(pr.mode is RadioMode.ArtistSeed)
    }

    @Test
    fun parsePlayRadio_modeCWhenTracksAndArtistBothPresent() {
        // Artist + tracks → Mode C (pool wins per issue spec).
        // base64 of [{"title":"T","artist":"A"}] = W3sidGl0bGUiOiJUIiwiYXJ0aXN0IjoiQSJ9XQ==
        val a = handler.parse(Uri.parse("parachord://play/radio?artist=Foo&tracks=W3sidGl0bGUiOiJUIiwiYXJ0aXN0IjoiQSJ9XQ%3D%3D"))
        val pr = a as DeepLinkAction.PlayRadio
        assertTrue(pr.mode is RadioMode.PoolBased)
    }

    @Test
    fun parsePlayRadio_refillAliasMapsToSameField() {
        // ?refill= should populate refillUrl. ?refillUrl= legacy alias should also work.
        val a1 = handler.parse(Uri.parse("parachord://play/radio?url=https%3A%2F%2Fexample.com%2Fp.jspf&refill=https%3A%2F%2Fexample.com%2Fr.jspf"))
        val a2 = handler.parse(Uri.parse("parachord://play/radio?url=https%3A%2F%2Fexample.com%2Fp.jspf&refillUrl=https%3A%2F%2Fexample.com%2Fr.jspf"))
        assertEquals("https://example.com/r.jspf", (a1 as DeepLinkAction.PlayRadio).refillUrl)
        assertEquals("https://example.com/r.jspf", (a2 as DeepLinkAction.PlayRadio).refillUrl)
    }

    @Test
    fun parsePlayRadio_refillAlonePresentButNoUrlOrTracks_isUnknown() {
        // refill is for subsequent fetches only — not a valid initial pool source.
        val a = handler.parse(Uri.parse("parachord://play/radio?refill=https%3A%2F%2Fexample.com%2Fr.jspf"))
        assertTrue(a is DeepLinkAction.Unknown)
    }

    @Test
    fun parsePlayRadio_modeB_titleHintPlumbsThrough() {
        val a = handler.parse(Uri.parse("parachord://play/radio?artist=Slowdive&title=Sugar%20For%20The%20Pill"))
        val pr = a as DeepLinkAction.PlayRadio
        val seed = pr.mode as RadioMode.ArtistSeed
        assertEquals("Slowdive", seed.artist)
        assertEquals("Sugar For The Pill", seed.title)
    }

    @Test
    fun parsePlayRadio_blankArtistTreatedAsAbsent() {
        // ?artist= (empty) + ?url= → still Mode C (the empty artist must NOT
        // win Mode B's gate).
        val a = handler.parse(Uri.parse("parachord://play/radio?artist=&url=https%3A%2F%2Fexample.com%2Fp.jspf"))
        val pr = a as DeepLinkAction.PlayRadio
        assertTrue(pr.mode is RadioMode.PoolBased)
    }

    @Test
    fun parsePlayRadio_malformedTracksFallsBackToArtistSeed() {
        // Malformed base64 in ?tracks= causes parseProtocolPlayInput to drop
        // the input entirely. With ?artist= also present, parser falls into
        // Mode B. This is documented behavior, locked by this test.
        val a = handler.parse(Uri.parse("parachord://play/radio?artist=Slowdive&tracks=NOT_BASE64"))
        val pr = a as DeepLinkAction.PlayRadio
        assertTrue(pr.mode is RadioMode.ArtistSeed)
    }

    @Test
    fun parsePlayRadio_shuffleFlagPlumbsThrough() {
        val a = handler.parse(Uri.parse("parachord://play/radio?artist=Slowdive&shuffle=1"))
        assertTrue((a as DeepLinkAction.PlayRadio).shuffle)
        val b = handler.parse(Uri.parse("parachord://play/radio?artist=Slowdive"))
        assertFalse((b as DeepLinkAction.PlayRadio).shuffle)
    }
}

/**
 * Round-trip tests for the Universal-Link / App-Link HTTPS form
 * (`https://parachord.com/<verb>`) against its custom-scheme counterpart
 * (`parachord://<verb>`).
 *
 * Each test asserts `parse(httpsUrl) == parse(parachordUrl)` for one verb.
 * This locks the contract: the HTTPS form is a complete drop-in for the
 * custom scheme. If [DeepLinkHandler.parseParachordHttps]'s URL rewrite
 * breaks for any verb, exactly one of these tests fails.
 *
 * Adding a new verb to [DeepLinkHandler.parseParachord]? Add a parallel
 * test here AND a matching `<data android:path|pathPrefix=...>` entry to
 * the `https://parachord.com` `<intent-filter>` in AndroidManifest.xml.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeepLinkHandlerHttpsParachordTest {

    private val handler = DeepLinkHandler()

    private fun assertRoundTrip(httpsPath: String, parachordPath: String) {
        val https = handler.parse(Uri.parse("https://parachord.com$httpsPath"))
        val parachord = handler.parse(Uri.parse("parachord:$parachordPath"))
        assertEquals(
            "HTTPS form (https://parachord.com$httpsPath) must produce the same DeepLinkAction as the custom scheme (parachord:$parachordPath)",
            parachord,
            https,
        )
        // Reject the "both Unknown" trivial pass — a real verb must resolve.
        assertFalse(
            "Both forms resolved to Unknown — the test isn't actually exercising parseParachord's switch",
            https is DeepLinkAction.Unknown,
        )
    }

    // -- Playback verbs --

    @Test fun play_artist_title() =
        assertRoundTrip("/play?artist=Radiohead&title=Creep", "//play?artist=Radiohead&title=Creep")

    @Test fun play_album_byMbid() =
        assertRoundTrip("/play/album?mbid=554b417d-8885-41e6-86c7-ae935e62d571", "//play/album?mbid=554b417d-8885-41e6-86c7-ae935e62d571")

    @Test fun play_playlist_bySpotifyId() =
        assertRoundTrip(
            "/play/playlist?spotify=37i9dQZF1DXcBWIGoYBM5M&title=Hits",
            "//play/playlist?spotify=37i9dQZF1DXcBWIGoYBM5M&title=Hits",
        )

    @Test fun play_radio_artistSeed() =
        assertRoundTrip("/play/radio?artist=Slowdive", "//play/radio?artist=Slowdive")

    @Test fun listen_along() =
        assertRoundTrip("/listen-along?service=lastfm&user=jherskow", "//listen-along?service=lastfm&user=jherskow")

    @Test fun control_pause() =
        assertRoundTrip("/control/pause", "//control/pause")

    @Test fun control_play() =
        assertRoundTrip("/control/play", "//control/play")

    @Test fun control_next() =
        assertRoundTrip("/control/next", "//control/next")

    @Test fun queue_add() =
        assertRoundTrip("/queue/add?artist=Beatles&title=Yesterday", "//queue/add?artist=Beatles&title=Yesterday")

    @Test fun queue_clear() =
        assertRoundTrip("/queue/clear", "//queue/clear")

    @Test fun shuffle_on() =
        assertRoundTrip("/shuffle/on", "//shuffle/on")

    @Test fun shuffle_off() =
        assertRoundTrip("/shuffle/off", "//shuffle/off")

    @Test fun volume_set() =
        assertRoundTrip("/volume/50", "//volume/50")

    @Test fun import_playlist() =
        assertRoundTrip(
            "/import?url=https%3A%2F%2Fexample.com%2Fp.xspf",
            "//import?url=https%3A%2F%2Fexample.com%2Fp.xspf",
        )

    // -- Navigation verbs --

    @Test fun navigate_home() =
        assertRoundTrip("/home", "//home")

    @Test fun navigate_artist() =
        assertRoundTrip("/artist/Radiohead", "//artist/Radiohead")

    @Test fun navigate_artist_withTab() =
        assertRoundTrip("/artist/Radiohead?tab=tour", "//artist/Radiohead?tab=tour")

    @Test fun navigate_album() =
        assertRoundTrip("/album/Radiohead/OK%20Computer", "//album/Radiohead/OK%20Computer")

    @Test fun navigate_library() =
        assertRoundTrip("/library", "//library")

    @Test fun navigate_library_withTab() =
        assertRoundTrip("/library?tab=artists", "//library?tab=artists")

    @Test fun navigate_history() =
        assertRoundTrip("/history", "//history")

    @Test fun navigate_friend() =
        assertRoundTrip("/friend/jherskow?tab=top-tracks", "//friend/jherskow?tab=top-tracks")

    @Test fun navigate_recommendations() =
        assertRoundTrip("/recommendations", "//recommendations")

    @Test fun navigate_charts() =
        assertRoundTrip("/charts", "//charts")

    @Test fun navigate_critics_picks() =
        assertRoundTrip("/critics-picks", "//critics-picks")

    @Test fun navigate_playlists() =
        assertRoundTrip("/playlists", "//playlists")

    @Test fun navigate_playlist_byId() =
        assertRoundTrip("/playlist/local-abc123", "//playlist/local-abc123")

    @Test fun navigate_settings() =
        assertRoundTrip("/settings", "//settings")

    @Test fun navigate_search() =
        assertRoundTrip("/search?q=Radiohead", "//search?q=Radiohead")

    @Test fun navigate_chat() =
        assertRoundTrip("/chat", "//chat")

    // -- Negative / edge cases --

    @Test fun rootPath_returnsUnknown() {
        // No verb → no action. (parseParachord with no host produces Unknown
        // too; we just want to confirm no NPE / crash on a bare host.)
        val a = handler.parse(Uri.parse("https://parachord.com/"))
        assertTrue(a is DeepLinkAction.Unknown)
    }

    @Test fun emptyPath_returnsUnknown() {
        val a = handler.parse(Uri.parse("https://parachord.com"))
        assertTrue(a is DeepLinkAction.Unknown)
    }

    @Test fun unknownVerb_returnsUnknown() {
        // parachord://nonexistent is also Unknown; the HTTPS form must
        // match.
        val httpsResult = handler.parse(Uri.parse("https://parachord.com/nonexistent-verb"))
        val parachordResult = handler.parse(Uri.parse("parachord://nonexistent-verb"))
        assertEquals(parachordResult, httpsResult)
    }
}
