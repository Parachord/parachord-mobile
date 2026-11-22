package com.parachord.shared.playback

import com.parachord.shared.playback.drm.PluginDrmError
import com.parachord.shared.playback.drm.PluginDrmOutcome
import com.parachord.shared.resolver.ResolvedSource
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A plugin's getStreamUrl played through the host's plain player (#670,
 * desktop parachord#1613). The fallback vectors mirror desktop's
 * tests/renderer/drm-plain-stream-fallback.test.js.
 */
class PluginStreamPlayTest {

    private val url = "https://cdn.example.com/a.mp3?Signature=abc"

    @Test
    fun `the reply is read with its error kept`() {
        assertEquals(PluginStreamReply.Answered(url), PluginStreamReplies.parse("""{"ok":true,"url":"$url"}"""))
        assertEquals(PluginStreamReply.Answered(null), PluginStreamReplies.parse("""{"ok":true,"url":null}"""))
        val failed = PluginStreamReplies.parse("""{"ok":false,"error":{"reason":"not-signed-in","message":"Sign in"}}""")
        assertIs<PluginStreamReply.Failed>(failed)
        assertEquals("not-signed-in", failed.error.reason)
        // No answer at all is a failed play.
        assertIs<PluginStreamReply.Failed>(PluginStreamReplies.parse(null))
        assertIs<PluginStreamReply.Failed>(PluginStreamReplies.parse("not json"))
    }

    @Test
    fun `an html5Stream plugin's failures are its settings`() {
        assertEquals(PluginStreamOutcome.Play(url), pluginStreamOutcome(PluginStreamReply.Answered(url), "subsonic", "Subsonic", true))
        val settings = PluginStreamOutcome.Settings(checkPluginSettingsMessage("subsonic"))
        assertEquals(settings, pluginStreamOutcome(PluginStreamReply.Answered(null), "subsonic", "Subsonic", true))
        assertEquals(settings, pluginStreamOutcome(PluginStreamReply.Failed(PluginDrmError()), "subsonic", "Subsonic", true))
        // A plain-http server: https only for now.
        assertEquals(settings, pluginStreamOutcome(PluginStreamReply.Answered("http://home:4533/rest/stream"), "subsonic", "Subsonic", true))
    }

    @Test
    fun `a Widevine plugin's plain stream maps as the DRM path`() {
        fun outcome(reply: PluginStreamReply) = pluginStreamOutcome(reply, "amazonmusic", "Amazon Music", false)
        assertEquals(PluginStreamOutcome.Play(url), outcome(PluginStreamReply.Answered(url)))
        // Not a purchase: a refused source.
        val notOwned = outcome(PluginStreamReply.Answered(null))
        assertIs<PluginStreamOutcome.Refused>(notOwned)
        assertEquals(noPlainStreamMessage("Amazon Music"), notOwned.message)
        val signedOut = outcome(PluginStreamReply.Failed(PluginDrmError(reason = "not-signed-in")))
        assertIs<PluginStreamOutcome.Refused>(signedOut)
        assertEquals(PluginDrmOutcome.NOT_SIGNED_IN, signedOut.outcome)
        val plan = outcome(PluginStreamReply.Failed(PluginDrmError(reason = "plan-ineligible", message = "Needs Music Unlimited.")))
        assertEquals(PluginStreamOutcome.Refused(PluginDrmOutcome.PLAN_REFUSED, "Needs Music Unlimited."), plan)
        // A failed request, purchases that couldn't be read: try again.
        assertEquals(PluginStreamOutcome.StartFailed(), outcome(PluginStreamReply.Failed(PluginDrmError(reason = "load-failed"))))
        assertEquals(PluginStreamOutcome.StartFailed(), outcome(PluginStreamReply.Failed(PluginDrmError(reason = "not-ready"))))
        assertEquals(PluginStreamOutcome.Superseded, outcome(PluginStreamReply.Failed(PluginDrmError(reason = "superseded"))))
    }

    @Test
    fun `a DRM play that didn't start falls back to the plain stream`() {
        for (o in listOf(PluginDrmOutcome.START_FAILED, PluginDrmOutcome.LICENSE_DENIED, PluginDrmOutcome.FAMILY_REFUSED)) {
            assertTrue(drmPlainStreamFallbackAllowed(o, started = false, hasStreamUrl = true), "$o")
        }
    }

    @Test
    fun `never after a plan refusal or a sign-in or a newer play`() {
        for (o in listOf(PluginDrmOutcome.PLAN_REFUSED, PluginDrmOutcome.NOT_SIGNED_IN, PluginDrmOutcome.SUPERSEDED)) {
            assertFalse(drmPlainStreamFallbackAllowed(o, started = false, hasStreamUrl = true), "$o")
        }
    }

    @Test
    fun `never for a play that started or a plugin without getStreamUrl`() {
        assertFalse(drmPlainStreamFallbackAllowed(PluginDrmOutcome.START_FAILED, started = true, hasStreamUrl = true))
        assertFalse(drmPlainStreamFallbackAllowed(PluginDrmOutcome.START_FAILED, started = false, hasStreamUrl = false))
    }

    @Test
    fun `the track handed to getStreamUrl carries the plugin's record and no stream URL`() {
        val source = ResolvedSource(
            url = "amazonmusic:track:B0XYZ", sourceType = "amazonmusic", resolver = "amazonmusic",
            pluginFields = mapOf("amazonAsin" to "B0XYZ", "albumAsin" to "B0ALB"),
        )
        val json = pluginStreamTrackJson("t1", "Song", "Artist", "Album", 200_000, "amazonmusic", null, source)
        assertEquals("200", json.getValue("duration").jsonPrimitive.content)
        val record = json.getValue("sources").jsonObject.getValue("amazonmusic").jsonObject
        assertEquals("B0XYZ", record.getValue("amazonAsin").jsonPrimitive.content)
        assertEquals("amazonmusic", record.getValue("resolver").jsonPrimitive.content)

        val subsonic = pluginStreamTrackJson("t2", "Song", "Artist", null, null, "subsonic", "mf3", null)
        assertEquals("mf3", subsonic.getValue("subsonicId").jsonPrimitive.content)
        assertFalse(subsonic.containsKey("album"))
        assertFalse(subsonic.containsKey("duration"))
    }
}
