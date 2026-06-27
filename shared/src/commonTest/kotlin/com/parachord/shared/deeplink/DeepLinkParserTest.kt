package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Guards the `parachord://` parse contract that iOS dispatches (#228). Host +
 * path + query-param names must stay in lockstep with Android's DeepLinkHandler.
 */
class DeepLinkParserTest {

    private fun uri(
        host: String,
        path: List<String> = emptyList(),
        params: Map<String, String> = emptyMap(),
        scheme: String = "parachord",
    ): DeepLinkUri = SimpleDeepLinkUri(scheme, host, path, params)

    @Test
    fun play_requires_artist_and_title() {
        val a = DeepLinkParser.parse(uri("play", params = mapOf("artist" to "Radiohead", "title" to "Creep")))
        assertEquals(DeepLinkAction.Play("Radiohead", "Creep"), a)
        // Missing title → Unknown (not a half-built Play).
        assertIs<DeepLinkAction.Unknown>(DeepLinkParser.parse(uri("play", params = mapOf("artist" to "Radiohead"))))
    }

    @Test
    fun control_maps_action_string() {
        assertEquals(DeepLinkAction.Control("next"), DeepLinkParser.parse(uri("control", path = listOf("next"))))
        assertEquals(DeepLinkAction.Control("previous"), DeepLinkParser.parse(uri("control", path = listOf("previous"))))
    }

    @Test
    fun queue_add_and_clear() {
        assertEquals(
            DeepLinkAction.QueueAdd("Bjork", "Joga", "Homogenic"),
            DeepLinkParser.parse(uri("queue", path = listOf("add"), params = mapOf("artist" to "Bjork", "title" to "Joga", "album" to "Homogenic"))),
        )
        assertEquals(DeepLinkAction.QueueClear, DeepLinkParser.parse(uri("queue", path = listOf("clear"))))
    }

    @Test
    fun shuffle_on_off_and_volume_clamp() {
        assertEquals(DeepLinkAction.Shuffle(true), DeepLinkParser.parse(uri("shuffle", path = listOf("on"))))
        assertEquals(DeepLinkAction.Shuffle(false), DeepLinkParser.parse(uri("shuffle", path = listOf("off"))))
        assertEquals(DeepLinkAction.Volume(100), DeepLinkParser.parse(uri("volume", path = listOf("150"))))
    }

    @Test
    fun listen_along() {
        assertEquals(
            DeepLinkAction.ListenAlong("lastfm", "jherskow"),
            DeepLinkParser.parse(uri("listen-along", params = mapOf("service" to "lastfm", "user" to "jherskow"))),
        )
    }

    @Test
    fun navigation_hosts() {
        assertEquals(DeepLinkAction.NavigateHome, DeepLinkParser.parse(uri("home")))
        assertEquals(DeepLinkAction.NavigateArtist("Bjork", "onTour"), DeepLinkParser.parse(uri("artist", path = listOf("Bjork"), params = mapOf("tab" to "onTour"))))
        assertEquals(DeepLinkAction.NavigateAlbum("Bjork", "Homogenic"), DeepLinkParser.parse(uri("album", path = listOf("Bjork", "Homogenic"))))
        assertEquals(DeepLinkAction.NavigateCharts, DeepLinkParser.parse(uri("charts")))
        assertEquals(DeepLinkAction.NavigateCriticalDarlings, DeepLinkParser.parse(uri("critics-picks")))
        assertEquals(DeepLinkAction.NavigatePlaylist("abc123"), DeepLinkParser.parse(uri("playlist", path = listOf("abc123"))))
        assertEquals(DeepLinkAction.NavigateSearch("aphex twin", null), DeepLinkParser.parse(uri("search", params = mapOf("q" to "aphex twin"))))
        assertEquals(DeepLinkAction.NavigateChat("hi"), DeepLinkParser.parse(uri("chat", params = mapOf("prompt" to "hi"))))
    }

    @Test
    fun protocol_play_is_parsed() {
        // play/album|playlist|radio now parse to their actions (parachord#256/#930).
        assertIs<DeepLinkAction.PlayAlbum>(DeepLinkParser.parse(uri("play", path = listOf("album"), params = mapOf("mbid" to "x"))))
        assertIs<DeepLinkAction.PlayPlaylist>(DeepLinkParser.parse(uri("play", path = listOf("playlist"), params = mapOf("url" to "https://open.spotify.com/playlist/abc"))))
        assertIs<DeepLinkAction.PlayRadio>(DeepLinkParser.parse(uri("play", path = listOf("radio"), params = mapOf("artist" to "x"))))
        // ?type= fallback (query form) routes the same as the path form.
        assertIs<DeepLinkAction.PlayPlaylist>(DeepLinkParser.parse(uri("play", params = mapOf("type" to "playlist", "url" to "https://open.spotify.com/playlist/abc"))))
    }

    @Test
    fun wrong_scheme_is_unknown() {
        // Non-parachord scheme → Unknown (external URL parsing is Android-only).
        assertIs<DeepLinkAction.Unknown>(DeepLinkParser.parse(uri("track", scheme = "spotify")))
    }

    @Test
    fun import_is_parsed() {
        val a = DeepLinkParser.parse(uri("import", params = mapOf("url" to "https://ex.com/p.xspf")))
        assertTrue(a is DeepLinkAction.ImportPlaylist && a.url == "https://ex.com/p.xspf")
    }
}
