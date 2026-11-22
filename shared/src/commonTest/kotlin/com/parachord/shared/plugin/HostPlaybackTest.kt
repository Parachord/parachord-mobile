package com.parachord.shared.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostPlaybackTest {

    private fun plugin(id: String, caps: Map<String, Boolean>, drm: Boolean = false, streamUrl: Boolean = false) =
        PluginManager.PluginInfo(
            id = id, name = id, version = "1.0.0", capabilities = caps, hasDrmPair = drm, hasStreamUrl = streamUrl,
        )

    private val amazonCaps = mapOf("resolve" to true, "stream" to true, "widevine" to true, "status" to true)

    /** Amazon before desktop parachord#1613: the DRM pair only. */
    private val amazonDrmOnly = plugin("amazonmusic", amazonCaps, drm = true)

    /** Amazon 1.23.0: getStreamUrl beside the pair, and no html5Stream declared. */
    private val amazon = plugin("amazonmusic", amazonCaps, drm = true, streamUrl = true)
    private val subsonic = plugin("subsonic", mapOf("resolve" to true, "html5Stream" to true), streamUrl = true)
    private val spotify = plugin("spotify", mapOf("resolve" to true, "stream" to true))
    private val widevineNoPair = plugin("half", mapOf("resolve" to true, "widevine" to true), streamUrl = true)

    @Test
    fun `a plugin's plugin-supplied paths`() {
        // The DRM pair is Widevine for Android and FairPlay for iOS (desktop #1627).
        assertEquals(setOf(HostPlayback.WIDEVINE, HostPlayback.FAIRPLAY), pluginPlaybackPaths(amazonDrmOnly))
        assertEquals(setOf(HostPlayback.WIDEVINE, HostPlayback.FAIRPLAY, HostPlayback.PLAIN_STREAM), pluginPlaybackPaths(amazon))
        assertEquals(setOf(HostPlayback.HTML5_STREAM), pluginPlaybackPaths(subsonic))
        assertEquals(emptySet(), pluginPlaybackPaths(spotify))
        // Widevine without getPlaybackManifest + relayLicense isn't a path, nor is its getStreamUrl alone.
        assertEquals(emptySet(), pluginPlaybackPaths(widevineNoPair))
    }

    @Test
    fun `iOS plays html5Stream and a Widevine plugin's plain stream`() {
        assertFalse(hostCannotPlay(subsonic, HostPlayback.IOS))
        assertFalse(hostCannotPlay(amazon, HostPlayback.IOS))
        assertFalse(hostCannotPlay(spotify, HostPlayback.IOS))
        assertEquals(emptyList(), hostUnplayableIds(listOf(spotify, subsonic, amazon), HostPlayback.IOS))
    }

    /** A host with the stream paths but no DRM engine (iOS before #794). */
    private val noDrmHost = setOf(HostPlayback.HTML5_STREAM, HostPlayback.PLAIN_STREAM)

    @Test
    fun `iOS plays a DRM-pair plugin with no getStreamUrl as FairPlay`() {
        assertFalse(hostCannotPlay(amazonDrmOnly, HostPlayback.IOS))
        assertTrue(playsThroughFairPlay(amazonDrmOnly, HostPlayback.IOS))
        assertTrue(playsThroughFairPlay(amazon, HostPlayback.IOS))
        assertFalse(playsThroughFairPlay(amazon, HostPlayback.ANDROID))
        assertFalse(playsThroughFairPlay(subsonic, HostPlayback.IOS))
    }

    @Test
    fun `a host with no DRM engine can't play a DRM-only plugin`() {
        assertTrue(hostCannotPlay(amazonDrmOnly, noDrmHost))
        assertEquals(listOf("amazonmusic"), hostUnplayableIds(listOf(spotify, subsonic, amazonDrmOnly), noDrmHost))
    }

    @Test
    fun `Android plays them all`() {
        assertEquals(emptyList(), hostUnplayableIds(listOf(spotify, subsonic, amazon, amazonDrmOnly), HostPlayback.ANDROID))
    }

    @Test
    fun `which plugins play through getStreamUrl first`() {
        // iOS: Subsonic. Amazon goes to FairPlay first; its plain stream is the fallback.
        assertTrue(playsThroughStreamUrl(subsonic, HostPlayback.IOS))
        assertFalse(playsThroughStreamUrl(amazon, HostPlayback.IOS))
        // A host without a DRM engine plays Amazon's purchases through getStreamUrl first.
        assertTrue(playsThroughStreamUrl(amazon, noDrmHost))
        assertFalse(playsThroughStreamUrl(amazonDrmOnly, HostPlayback.IOS))
        assertFalse(playsThroughStreamUrl(spotify, HostPlayback.IOS))
        // Android: Subsonic only; Amazon goes to Widevine first, its plain stream is the fallback.
        assertTrue(playsThroughStreamUrl(subsonic, HostPlayback.ANDROID))
        assertFalse(playsThroughStreamUrl(amazon, HostPlayback.ANDROID))
    }

    @Test
    fun `the tile and the Play from switch say so`() {
        assertEquals(TileStatus(HOST_CANNOT_PLAY_LABEL, HOST_CANNOT_PLAY_MESSAGE), tileStatus(emptyMap(), "amazonmusic", emptyList(), listOf("amazonmusic")))
        assertEquals(TileStatus(null, null), tileStatus(emptyMap(), "spotify", emptyList(), listOf("amazonmusic")))
        val s = playbackSwitchState(amazonDrmOnly, active = true, playbackOff = false, limit = null, hostCannotPlay = hostCannotPlay(amazonDrmOnly, noDrmHost))
        assertFalse(s.checked)
        assertTrue(s.disabled)
        assertEquals(HOST_CANNOT_PLAY_MESSAGE, s.hint)
        // With getStreamUrl, iOS no longer says "Search and links only" for Amazon.
        assertEquals(TileStatus(null, null), tileStatus(emptyMap(), "amazonmusic", emptyList(), hostUnplayableIds(listOf(amazon), HostPlayback.IOS)))
    }

    @Test
    fun `active_resolvers follows a field-editor plugin's config`() {
        assertEquals(listOf("bandcamp", "subsonic"), activeResolversAfterConfig(listOf("bandcamp"), "subsonic", configured = true))
        assertEquals(listOf("bandcamp"), activeResolversAfterConfig(listOf("bandcamp", "subsonic"), "subsonic", configured = false))
        assertNull(activeResolversAfterConfig(listOf("bandcamp", "subsonic"), "subsonic", configured = true))
        assertNull(activeResolversAfterConfig(listOf("bandcamp"), "subsonic", configured = false))
        // An empty list asks every resolver: nothing to add.
        assertNull(activeResolversAfterConfig(emptyList(), "subsonic", configured = true))
    }

    @Test
    fun `Test connection's line`() {
        assertEquals("Connected — 1 result for \"test\"", connectionTestOkMessage(1))
        assertEquals("Connected — 12 results for \"test\"", connectionTestOkMessage(12))
    }
}
