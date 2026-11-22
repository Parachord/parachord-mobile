package com.parachord.shared.playback.drm

import com.parachord.shared.plugin.PluginManager.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQualityLineTest {

    private val amazon = PluginInfo("amazonmusic", "Amazon Music", "1", capabilities = mapOf("widevine" to true, "resolve" to true), hasDrmPair = true)
    private val apple = PluginInfo("applemusic", "Apple Music", "1", capabilities = mapOf("resolve" to true))
    private val spotify = PluginInfo("spotify", "Spotify", "1")
    private val soundcloud = PluginInfo("soundcloud", "SoundCloud", "1")
    private val bandcamp = PluginInfo("bandcamp", "Bandcamp", "1")
    private val subsonic = PluginInfo("subsonic", "Subsonic", "1", capabilities = mapOf("html5Stream" to true))
    private val local = PluginInfo("localfiles", "Local Files", "1")
    private val other = PluginInfo("youtube", "YouTube", "1")

    private val atmos = DeviceAudioCapabilities(eac3 = true, eac3Joc = false)
    private val noAtmos = DeviceAudioCapabilities.NONE

    private fun line(p: PluginInfo, platform: PlaybackPlatform = PlaybackPlatform.ANDROID, s: PlaybackQualitySettings = PlaybackQualitySettings(), d: DeviceAudioCapabilities = noAtmos) =
        PlaybackQualityLine.forPlugin(p, platform, s, d)

    @Test
    fun `a widevine plugin follows with the tier and adjust`() {
        assertEquals(
            PlaybackQualityLine.Follows("Max: best available, lossless where playable · Adjust to your connection"),
            line(amazon),
        )
        assertEquals(
            PlaybackQualityLine.Follows("High: up to 500 kbps"),
            line(amazon, s = PlaybackQualitySettings(PlaybackQualityTier.HIGH, adaptive = false)),
        )
    }

    @Test
    fun `atmos shows only when it is on and the device can decode it`() {
        val s = PlaybackQualitySettings(PlaybackQualityTier.SAVER, adaptive = false, spatial = true)
        assertEquals(PlaybackQualityLine.Follows("Data saver: up to 100 kbps · Dolby Atmos when available"), line(amazon, s = s, d = atmos))
        assertEquals(PlaybackQualityLine.Follows("Data saver: up to 100 kbps"), line(amazon, s = s, d = noAtmos))
    }

    @Test
    fun `widevine needs both DRM methods and android`() {
        assertNull(line(amazon.copy(hasDrmPair = false)))
        assertNull(line(amazon.copy(hasDrmPair = false), platform = PlaybackPlatform.IOS))
    }

    @Test
    fun `on iOS the DRM pair is FairPlay AAC — it says so`() {
        val l = line(amazon, platform = PlaybackPlatform.IOS) as PlaybackQualityLine.ExternalControl
        assertEquals(PlaybackQualityLine.IOS_FAIRPLAY_TITLE, l.title)
        assertTrue(l.detail!!.startsWith("Amazon Music streams AAC on iPhone and iPad."))
    }

    @Test
    fun `apple music follows on android without adjust or atmos`() {
        val s = PlaybackQualitySettings(PlaybackQualityTier.STANDARD, adaptive = true, spatial = true)
        assertEquals(PlaybackQualityLine.Follows("Standard: up to 200 kbps"), line(apple, s = s, d = atmos))
    }

    @Test
    fun `apple music on iOS is set in iOS Settings`() {
        val l = line(apple, platform = PlaybackPlatform.IOS) as PlaybackQualityLine.ExternalControl
        assertEquals("Set in iOS Settings", l.title)
        assertEquals("Settings › Music › Audio Quality. Parachord’s Playback Quality doesn’t change it.", l.detail)
    }

    @Test
    fun `the built-in services each say who sets it`() {
        assertEquals("Set in the Spotify app", (line(spotify) as PlaybackQualityLine.ExternalControl).title)
        assertEquals(
            PlaybackQualityLine.ExternalControl("Set by its player", "SoundCloud chooses the quality; it can’t be changed."),
            line(soundcloud),
        )
        assertEquals(
            PlaybackQualityLine.ExternalControl("Set by its player", "Bandcamp chooses the quality; it can’t be changed."),
            line(bandcamp, platform = PlaybackPlatform.IOS),
        )
        assertEquals(
            PlaybackQualityLine.ExternalControl("Set by your server", "Your server’s transcoding settings decide it."),
            line(subsonic),
        )
        assertEquals(PlaybackQualityLine.ExternalControl("Plays the file as it is", null), line(local))
    }

    @Test
    fun `a plugin with no rule shows nothing`() {
        assertNull(line(other))
        assertNull(line(other, platform = PlaybackPlatform.IOS))
    }

    @Test
    fun `the general copy names the services that follow`() {
        val copy = PlaybackQualityCopy.build(listOf(spotify, apple, amazon, local), PlaybackPlatform.ANDROID)
        assertEquals("Amazon Music and Apple Music use these settings. Other services choose their own quality.", copy.description)
        assertTrue(copy.adjustHint.startsWith("Amazon Music only. Lowers quality"))
        assertTrue(copy.atmosHint.startsWith("Amazon Music only. Plays the 5.1 core"))
    }

    @Test
    fun `the general copy with only apple music`() {
        val copy = PlaybackQualityCopy.build(listOf(spotify, apple), PlaybackPlatform.ANDROID)
        assertEquals("Apple Music uses these settings. Other services choose their own quality.", copy.description)
        assertTrue(copy.adjustHint.startsWith("None of your plug-ins use this yet."))
    }

    @Test
    fun `the general copy on iOS names nobody`() {
        val copy = PlaybackQualityCopy.build(listOf(apple, amazon), PlaybackPlatform.IOS)
        assertTrue(copy.description.startsWith("None of your plug-ins"))
    }

    @Test
    fun `names join with and`() {
        assertEquals("A", PlaybackQualityCopy.joinNames(listOf("A")))
        assertEquals("A and B", PlaybackQualityCopy.joinNames(listOf("A", "B")))
        assertEquals("A, B and C", PlaybackQualityCopy.joinNames(listOf("A", "B", "C")))
    }

    @Test
    fun `musickit plays data saver at its standard bitrate and every other tier high`() {
        assertEquals(64, MusicKitQuality.bitrateFor(PlaybackQualityTier.SAVER))
        for (t in listOf(PlaybackQualityTier.STANDARD, PlaybackQualityTier.HIGH, PlaybackQualityTier.MAX, null)) {
            assertEquals(256, MusicKitQuality.bitrateFor(t))
        }
    }

    @Test
    fun `the label names atmos and lossless only`() {
        assertEquals("Dolby Atmos", qualityLabel(CodecFamilies.EC3, isSpatial = true))
        assertEquals("Dolby Atmos", qualityLabel(CodecFamilies.EC3, isSpatial = false))
        assertEquals("Lossless", qualityLabel(CodecFamilies.FLAC, isSpatial = false))
        assertNull(qualityLabel(CodecFamilies.OPUS, isSpatial = false))
        assertNull(qualityLabel(CodecFamilies.AAC, isSpatial = false))
        assertNull(qualityLabel(null, isSpatial = false))
    }

    @Test
    fun `a format's family comes from its codecs then its mime type`() {
        assertEquals(CodecFamilies.FLAC, codecFamilyOf("flac", null))
        assertEquals(CodecFamilies.EC3, codecFamilyOf(null, "audio/eac3-joc"))
        assertEquals(CodecFamilies.FLAC, codecFamilyOf(null, "audio/flac"))
        assertNull(codecFamilyOf(null, null))
    }

    @Test
    fun `spatial is dropped where the device cannot decode it`() {
        val s = PlaybackQualitySettings(spatial = true)
        assertEquals(false, s.forDevice(noAtmos).spatial)
        assertEquals(true, s.forDevice(atmos).spatial)
        assertEquals(true, s.forDevice(DeviceAudioCapabilities(eac3Joc = true)).spatial)
    }
}
