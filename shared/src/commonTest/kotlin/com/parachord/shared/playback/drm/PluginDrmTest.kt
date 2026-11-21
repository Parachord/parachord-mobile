package com.parachord.shared.playback.drm

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plugin-supplied Widevine contract's errors, the bootstrap's replies,
 * the family latch and the player events (desktop plugins.md "The
 * plugin-supplied Widevine path", widevine-player.js).
 */
class PluginDrmTest {

    @Test
    fun `a plan refusal is a plan refusal whatever else it carries`() {
        assertEquals(
            PluginDrmOutcome.PLAN_REFUSED,
            pluginDrmOutcome(PluginDrmError(reason = "plan-ineligible", planKey = "prime")),
        )
    }

    @Test
    fun `not signed in superseded and a build refusal`() {
        assertEquals(PluginDrmOutcome.NOT_SIGNED_IN, pluginDrmOutcome(PluginDrmError(reason = "not-signed-in")))
        assertEquals(PluginDrmOutcome.SUPERSEDED, pluginDrmOutcome(PluginDrmError(reason = "superseded")))
        assertEquals(
            PluginDrmOutcome.LICENSE_DENIED,
            pluginDrmOutcome(PluginDrmError(reason = "playback-failed", licenseDenied = true)),
        )
    }

    @Test
    fun `a per-content denial refuses a family and any other denial fails the start`() {
        assertEquals(
            PluginDrmOutcome.FAMILY_REFUSED,
            pluginDrmOutcome(PluginDrmError(reason = "playback-failed", denialReason = "CUSTOMER_NOT_ELIGIBLE")),
        )
        assertEquals(
            PluginDrmOutcome.START_FAILED,
            pluginDrmOutcome(PluginDrmError(reason = "playback-failed", denialReason = "REGION_BLOCKED")),
        )
        assertEquals(PluginDrmOutcome.START_FAILED, pluginDrmOutcome(PluginDrmError(reason = "load-failed")))
        assertEquals(PluginDrmOutcome.START_FAILED, pluginDrmOutcome(PluginDrmError.timedOut("getPlaybackManifest")))
    }

    @Test
    fun `the manifest budget is desktop's ten seconds`() {
        assertEquals(10_000L, PluginDrmContract.MANIFEST_BUDGET_MS)
        assertEquals("playback-failed", PluginDrmError.timedOut("x").reason)
    }

    @Test
    fun `an opened session carries its manifest and extras`() {
        val reply = PluginDrmReplies.open(
            """{"ok":true,"sessionId":"drm_1_x","manifest":"<MPD/>","extras":{"asin":"B01","substituteAsin":null,"cdnType":"cf"}}""",
        )
        assertIs<PluginDrmOpenReply.Opened>(reply)
        assertEquals("drm_1_x", reply.sessionId)
        assertEquals("<MPD/>", reply.manifest)
        assertEquals(PluginDrmExtras(asin = "B01", substituteAsin = null, cdnType = "cf"), reply.extras)
    }

    @Test
    fun `a refused open carries the contract fields`() {
        val reply = PluginDrmReplies.open(
            """{"ok":false,"error":{"reason":"plan-ineligible","planKey":"prime","licenseDenied":false,"denialReason":null,"message":"Prime can't play"}}""",
        )
        assertIs<PluginDrmOpenReply.Failed>(reply)
        assertEquals(PluginDrmError("plan-ineligible", "prime", false, null, "Prime can't play"), reply.error)
    }

    @Test
    fun `no answer or a reply with no manifest is a failed open`() {
        assertIs<PluginDrmOpenReply.Failed>(PluginDrmReplies.open(null))
        assertIs<PluginDrmOpenReply.Failed>(PluginDrmReplies.open("not json"))
        val noManifest = PluginDrmReplies.open("""{"ok":true,"sessionId":"s"}""")
        assertIs<PluginDrmOpenReply.Failed>(noManifest)
        assertEquals("playback-failed", noManifest.error.reason)
    }

    @Test
    fun `a licence reply is the licence or the refusal`() {
        assertEquals(PluginDrmLicenseReply.Licensed("AAEC"), PluginDrmReplies.license("""{"ok":true,"license":"AAEC"}"""))
        val refused = PluginDrmReplies.license(
            """{"ok":false,"error":{"reason":"playback-failed","licenseDenied":true,"denialReason":"CERT","message":"m"}}""",
        )
        assertIs<PluginDrmLicenseReply.Failed>(refused)
        assertTrue(refused.error.licenseDenied)
        assertEquals("CERT", refused.error.denialReason)
        assertIs<PluginDrmLicenseReply.Failed>(PluginDrmReplies.license("""{"ok":true}"""))
        assertIs<PluginDrmLicenseReply.Failed>(PluginDrmReplies.license(null))
    }

    @Test
    fun `a string licenseDenied is not true`() {
        val e = PluginDrmReplies.error(kotlinx.serialization.json.Json.parseToJsonElement("""{"licenseDenied":"true"}"""))
        assertFalse(e.licenseDenied)
    }

    @Test
    fun `the family latch drops a content-refused family once`() {
        val table = DrmFamilyTable()
        val denial = PluginDrmError(denialReason = "CUSTOMER_NOT_ELIGIBLE")
        assertTrue(table.recordLicenseRefusal(denial, "flac"))
        assertEquals(setOf("flac"), table.blockedFamilies)
        // A repeat: the retry didn't take, so the error propagates.
        assertFalse(table.recordLicenseRefusal(denial, "flac"))
        // An unknown family or another refusal latches nothing.
        assertFalse(table.recordLicenseRefusal(denial, null))
        assertFalse(table.recordLicenseRefusal(PluginDrmError(licenseDenied = true), "opus"))
        table.clearPlanBlocks()
        assertTrue(table.blockedFamilies.isEmpty())
    }

    @Test
    fun `play-started carries desktop's payload`() {
        val e = PluginPlayerEvents.playStarted(
            asin = "B01", albumAsin = null, durationMs = 210_000, initialDelaySeconds = 1.23456,
            variantFacts = PluginPlayerEvents.VariantFacts("flac", 48000, 2, 1_902_230), cdnType = "cf",
        )
        assertEquals("play-started", e["type"]!!.jsonPrimitive.content)
        assertEquals("B01", e["asin"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, e["albumAsin"])
        assertEquals(210_000L, e["durationMs"]!!.jsonPrimitive.content.toLong())
        assertEquals(1.235, e["initialDelaySeconds"]!!.jsonPrimitive.content.toDouble())
        val facts = e["variantFacts"] as JsonObject
        assertEquals(JsonPrimitive("flac"), facts["codec"])
        assertEquals(1_902_230L, facts["bandwidth"]!!.jsonPrimitive.content.toLong())
        assertEquals("cf", e["cdnType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `play-started has no variant facts without a bitrate and no delay when unknown`() {
        val e = PluginPlayerEvents.playStarted(null, null, null, null, PluginPlayerEvents.VariantFacts("flac", null, null, null), null)
        assertEquals(JsonNull, e["variantFacts"])
        assertNull(e["initialDelaySeconds"])
    }

    private fun type(e: JsonObject?) = e?.get("type")?.jsonPrimitive?.content

    @Test
    fun `the tracker maps the player's changes to desktop's events`() {
        val t = PluginPlayerEventTracker()
        val started = PluginPlayerEvents.playStarted("a", null, null, null, null, null)
        // Nothing before the first play.
        assertNull(t.onPaused(0))
        assertNull(t.onSeek(0))
        assertEquals("play-started", type(t.onPlaying(0) { started }))
        assertEquals("pause", type(t.onPaused(5_000)))
        assertEquals("resume", type(t.onPlaying(5_000) { started }))
        val seek = t.onSeek(60_000)
        assertEquals("seek", type(seek))
        assertEquals(60_000L, seek!!["positionMs"]!!.jsonPrimitive.content.toLong())
        assertEquals("ended", type(t.onEnded(200_000, 200_000)))
        // After the end: no pause, no stopped.
        assertNull(t.onPaused(200_000))
        assertNull(t.onStopped(200_000))
    }

    @Test
    fun `leaving a session that played is stopped once`() {
        val t = PluginPlayerEventTracker()
        t.onPlaying(0) { PluginPlayerEvents.playStarted(null, null, null, null, null, null) }
        assertEquals("stopped", type(t.onStopped(12_000)))
        assertNull(t.onStopped(12_000))
        assertNull(t.onPlaying(0) { error("no event after stopped") })
    }

    @Test
    fun `a session that never played sends no stopped`() {
        assertNull(PluginPlayerEventTracker().onStopped(0))
    }

    @Test
    fun `a plan refusal shows the plugin's own words`() {
        assertEquals("Prime can't play.", PluginDrmMessages.refused(PluginDrmOutcome.PLAN_REFUSED, "Amazon Music", "Prime can't play."))
        assertEquals(
            "Your Amazon Music plan doesn’t play tracks on demand.",
            PluginDrmMessages.refused(PluginDrmOutcome.PLAN_REFUSED, "Amazon Music", null),
        )
        assertEquals(
            "Sign in to Amazon Music in Settings to play this track.",
            PluginDrmMessages.refused(PluginDrmOutcome.NOT_SIGNED_IN, "Amazon Music", "ignored"),
        )
        assertEquals("x Playing via Spotify.", PluginDrmMessages.playingVia("x", "Spotify"))
    }

    @Test
    fun `a widevine plugin's result keeps its string and number fields`() {
        val fields = com.parachord.shared.resolver.pluginResultFields(
            """{"id":"amazonmusic-B01","amazonAsin":"B01","duration":210,"explicit":true,"sources":["amazonmusic"],"genre":null}""",
        )!!
        assertEquals(mapOf("id" to "amazonmusic-B01", "amazonAsin" to "B01", "duration" to "210"), fields)
        assertNull(com.parachord.shared.resolver.pluginResultFields("[1,2]"))
        assertNull(com.parachord.shared.resolver.pluginResultFields("{}"))
    }
}
