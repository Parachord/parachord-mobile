package com.parachord.android.playback.drm

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.DefaultDataSource
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.playback.drm.PlaybackQualitySettings
import com.parachord.shared.playback.drm.PlaybackQualityTier
import com.parachord.shared.playback.drm.DrmVariant
import com.parachord.shared.playback.drm.PluginDrmExtras
import com.parachord.shared.resolver.ResolvedSource
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Android half of the plugin-supplied Widevine engine: the
 * `parachord-drm://` item routing, the licence round trip through a fake JS
 * bridge, the quality policy over ExoPlayer's tracks, and the track the
 * plugin is handed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PluginDrmEngineTest {

    /** A fake `__pcDrm`: records the calls, answers what the test sets. */
    private class FakeJs(var licenseReply: String? = null) : PluginDrmJs {
        val licenseCalls = mutableListOf<Pair<String, String>>()
        val events = mutableListOf<Pair<String, String>>()
        val closed = mutableListOf<String>()
        override suspend fun open(resolverId: String, trackJson: String, budgetMs: Long): String? = null
        override suspend fun license(sessionId: String, challengeB64: String, budgetMs: Long): String? {
            licenseCalls += sessionId to challengeB64
            return licenseReply
        }
        override suspend fun event(sessionId: String, eventJson: String) { events += sessionId to eventJson }
        override suspend fun close(sessionId: String) { closed += sessionId }
    }

    private fun sessions(js: PluginDrmJs) =
        PluginDrmSessions(js, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), clock = { 1_000L })

    private fun session(id: String, quality: PlaybackQualitySettings = PlaybackQualitySettings()) = PluginDrmSessions.Session(
        id = id, resolverId = "amazonmusic", trackId = "t1", manifest = MPD, extras = PluginDrmExtras(asin = "B01"),
        quality = quality, albumAsin = null, durationMs = 200_000, openedAtMs = 0,
    )

    /** A fallback factory that only records what it was asked to build. */
    private class RecordingFactory : MediaSource.Factory {
        val built = mutableListOf<MediaItem>()
        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes(): IntArray = intArrayOf(androidx.media3.common.C.CONTENT_TYPE_OTHER)
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            built += mediaItem
            return mockk(relaxed = true)
        }
    }

    private fun factory(sessions: PluginDrmSessions, fallback: RecordingFactory) = PluginDrmMediaSourceFactory(
        fallback = fallback,
        sessions = sessions,
        dataSourceFactory = DefaultDataSource.Factory(RuntimeEnvironment.getApplication()),
        drmSessionManagerFor = { DrmSessionManager.DRM_UNSUPPORTED },
    )

    @Test
    fun `the drm uri names its session and nothing else does`() {
        assertEquals("drm_1_x", PluginDrmUris.sessionIdOf(PluginDrmUris.uriFor("drm_1_x")))
        assertNull(PluginDrmUris.sessionIdOf("content://media/external/audio/media/1"))
        assertNull(PluginDrmUris.sessionIdOf("https://example.com/a.mp3"))
        assertNull(PluginDrmUris.sessionIdOf("android.resource://pkg/raw/silence"))
        assertNull(PluginDrmUris.sessionIdOf("parachord-drm://../etc"))
        assertNull(PluginDrmUris.sessionIdOf(null))
    }

    @Test
    fun `every other item goes to the default factory unchanged`() {
        val fallback = RecordingFactory()
        val item = MediaItem.fromUri("https://example.com/a.mp3")
        factory(sessions(FakeJs()), fallback).createMediaSource(item)
        assertSame(item, fallback.built.single())
    }

    @Test
    fun `a drm item builds a dash source from the session's manifest`() {
        val fallback = RecordingFactory()
        val s = sessions(FakeJs())
        s.register(session("drm_1_a"))
        val source = factory(s, fallback).createMediaSource(MediaItem.fromUri(PluginDrmUris.uriFor("drm_1_a")))
        assertTrue(source is DashMediaSource)
        assertTrue(fallback.built.isEmpty())
    }

    @Test
    fun `a drm item whose session closed fails through the default factory`() {
        val fallback = RecordingFactory()
        factory(sessions(FakeJs()), fallback).createMediaSource(MediaItem.fromUri(PluginDrmUris.uriFor("drm_9_gone")))
        assertEquals(1, fallback.built.size)
    }

    @Test
    fun `the licence callback round-trips base64 through the plugin`() {
        val challenge = byteArrayOf(8, 4, 0, -1, 127)
        val licence = byteArrayOf(1, 2, 3, -128)
        val js = FakeJs(licenseReply = """{"ok":true,"license":"${java.util.Base64.getEncoder().encodeToString(licence)}"}""")
        val s = sessions(js)
        s.register(session("drm_1_a"))
        val bytes = PluginDrmLicenseCallback("drm_1_a", s, js, provisioning = null).licenseFor(challenge)
        assertArrayEquals(licence, bytes)
        assertEquals("drm_1_a" to java.util.Base64.getEncoder().encodeToString(challenge), js.licenseCalls.single())
    }

    @Test
    fun `a refused licence throws its fields and is recorded for the session`() {
        val js = FakeJs(licenseReply = """{"ok":false,"error":{"reason":"playback-failed","licenseDenied":false,"denialReason":"CUSTOMER_NOT_ELIGIBLE"}}""")
        val s = sessions(js)
        s.register(session("drm_1_a"))
        s.get("drm_1_a")!!.requestedFamily = "flac"
        try {
            PluginDrmLicenseCallback("drm_1_a", s, js, provisioning = null).licenseFor(byteArrayOf(1))
            fail("expected a refusal")
        } catch (e: PluginDrmLicenseException) {
            assertEquals("CUSTOMER_NOT_ELIGIBLE", e.error.denialReason)
        }
        assertEquals("CUSTOMER_NOT_ELIGIBLE", s.get("drm_1_a")!!.lastLicenseError?.denialReason)
        // The family latches once: the retry plays without FLAC; a second refusal doesn't loop.
        assertTrue(s.latchRefusedFamily("drm_1_a"))
        assertEquals(setOf("flac"), s.families.blockedFamilies)
        assertTrue(!s.latchRefusedFamily("drm_1_a"))
    }

    @Test
    fun `no answer from the plugin is a refused licence`() {
        val js = FakeJs(licenseReply = null)
        val s = sessions(js)
        s.register(session("drm_1_a"))
        try {
            PluginDrmLicenseCallback("drm_1_a", s, js, provisioning = null).licenseFor(byteArrayOf(1))
            fail("expected a refusal")
        } catch (e: PluginDrmLicenseException) {
            assertEquals("playback-failed", e.error.reason)
        }
    }

    @Test
    fun `a newer session closes an older one that never reached the player`() {
        val js = FakeJs()
        val s = sessions(js)
        s.register(session("drm_1_a"))
        s.register(session("drm_2_b"))
        assertNull(s.get("drm_1_a"))
        assertEquals(listOf("drm_1_a"), js.closed)
    }

    @Test
    fun `leaving a session that played sends stopped before the close`() {
        val js = FakeJs()
        val s = sessions(js)
        s.register(session("drm_1_a"))
        s.onPlayerSession("drm_1_a", 0)
        val ses = s.get("drm_1_a")!!
        s.send(ses.id, ses.tracker.onPlaying(0) { com.parachord.shared.playback.drm.PluginPlayerEvents.playStarted("B01", null, null, null, null, null) })
        s.onPlayerSession(null, 42_000)
        assertEquals(listOf("play-started", "stopped"), js.events.map { it.second.substringAfter("\"type\":\"").substringBefore('"') })
        assertEquals(listOf("drm_1_a"), js.closed)
    }

    @Test
    fun `the selector follows the policy only while the player is on a session`() {
        val s = sessions(FakeJs())
        s.register(session("drm_1_a"))
        assertNull(s.selectionPolicy())
        s.onPlayerSession("drm_1_a", 0)
        assertEquals("drm_1_a", s.selectionPolicy()!!.sessionId)
    }

    private fun track(group: Int, index: Int, codecs: String, bw: Long, handled: Boolean = true) =
        PluginDrmSelection.Track(group, index, handled, DrmVariant("$group/$index", codecs, bw, 48000, 2))

    // Amazon's shape: one adaptation set per family.
    private val tracks = listOf(
        track(0, 0, "opus", 53059), track(0, 1, "opus", 197343), track(0, 2, "opus", 325595),
        track(1, 0, "flac", 1062133), track(1, 1, "flac", 1902230),
        track(2, 0, "ec-3", 770130), track(2, 1, "ec-3", 450130),
    )

    @Test
    fun `locked max plays the top flac rung alone`() {
        val c = PluginDrmSelection.choose(tracks, PlaybackQualitySettings(PlaybackQualityTier.MAX, adaptive = false), emptySet())!!
        assertEquals(1, c.group)
        assertArrayEquals(intArrayOf(1), c.tracks)
    }

    @Test
    fun `adaptive high roams the opus ladder under 500 kbps`() {
        val c = PluginDrmSelection.choose(tracks, PlaybackQualitySettings(PlaybackQualityTier.HIGH, adaptive = true), emptySet())!!
        assertEquals(0, c.group)
        assertArrayEquals(intArrayOf(0, 1, 2), c.tracks)
    }

    @Test
    fun `adaptive standard stays at or below its ceiling`() {
        val c = PluginDrmSelection.choose(tracks, PlaybackQualitySettings(PlaybackQualityTier.STANDARD, adaptive = true), emptySet())!!
        assertArrayEquals(intArrayOf(0, 1), c.tracks)
    }

    @Test
    fun `a refused flac family falls back to opus`() {
        val c = PluginDrmSelection.choose(tracks, PlaybackQualitySettings(PlaybackQualityTier.MAX, adaptive = true), setOf("flac"))!!
        assertEquals(0, c.group)
        assertEquals(325595L, c.picked.bandwidth)
    }

    @Test
    fun `atmos on picks the ec-3 rung locked`() {
        val c = PluginDrmSelection.choose(tracks, PlaybackQualitySettings(PlaybackQualityTier.MAX, adaptive = true, spatial = true), emptySet())!!
        assertEquals(2, c.group)
        assertArrayEquals(intArrayOf(0), c.tracks)
    }

    @Test
    fun `a codec the decoder can't handle is never chosen`() {
        val noFlacDecoder = tracks.map { if (it.variant.codecs == "flac") it.copy(handled = false) else it }
        val c = PluginDrmSelection.choose(noFlacDecoder, PlaybackQualitySettings(PlaybackQualityTier.MAX, adaptive = false), emptySet())!!
        assertEquals("opus", c.picked.codecs)
    }

    @Test
    fun `nothing playable leaves the choice to exoplayer`() {
        assertNull(PluginDrmSelection.choose(listOf(track(0, 0, "mha1.0x0e", 1_000_000)), PlaybackQualitySettings(), emptySet()))
    }

    @Test
    fun `the plugin gets the whole track with its own source record`() {
        val t = TrackEntity(id = "t1", title = "Song", artist = "Artist", album = "Album", duration = 210_000, resolver = "amazonmusic")
        val source = ResolvedSource(
            resolver = "amazonmusic", sourceType = "amazonmusic", url = "amazonmusic:track:amazonmusic-B01",
            pluginFields = mapOf("amazonAsin" to "B01", "id" to "amazonmusic-B01"),
        )
        val json = PluginDrmHandler.trackJson(t, "amazonmusic", source)
        val record = (json["sources"] as kotlinx.serialization.json.JsonObject)["amazonmusic"] as kotlinx.serialization.json.JsonObject
        assertEquals("B01", (record["amazonAsin"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("Song", (json["title"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(210L, (json["duration"] as kotlinx.serialization.json.JsonPrimitive).content.toLong())
    }

    companion object {
        private val MPD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT3M30S" minBufferTime="PT2S" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
              <Period>
                <AdaptationSet contentType="audio" mimeType="audio/mp4">
                  <Representation id="1" codecs="opus" bandwidth="197343" audioSamplingRate="48000">
                    <BaseURL>https://cdn.example.com/a/1.mp4</BaseURL>
                    <SegmentBase indexRange="0-100"><Initialization range="0-50"/></SegmentBase>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
    }
}
