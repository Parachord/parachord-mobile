package com.parachord.shared.plugin

import com.parachord.shared.home.PlaybackLimit
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Desktop's vectors from `tests/renderer/playback-limits.test.js`
 * (`src/renderer/lib/playback-limits.js`), ported.
 */
class PlaybackLimitsTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    private val primeMessage = "Amazon Prime: Amazon is used for album art, artist photos and links. Playing Amazon tracks in Parachord needs Music Unlimited."
    private val otherMessage = "Other Free: Other is used for search and links. Playing Other tracks in Parachord needs Other Premium."
    private val amazonPrime = PlaybackLimit(true, "Prime: no playback", "prime", primeMessage, true)
    private fun otherLimit(blocked: Boolean) = PlaybackLimit(
        blocked, if (blocked) "Free: no playback" else null, if (blocked) "free" else "premium", if (blocked) otherMessage else null,
    )

    @Test
    fun `a valid playbackLimit becomes the limit entry validated field by field`() {
        assertEquals(
            PlaybackLimit(true, "Free: no playback", "free", "Playing needs Awesome Premium.", true),
            pluginStatusLimit(j("""{"signedIn":true,"playbackLimit":{"blocked":true,"planKey":"free","label":"Free: no playback","message":"Playing needs Awesome Premium.","searchesHeldBack":true},"statusLabel":"Free plan","statusMessage":"Playing needs Awesome Premium."}""")),
        )
        assertEquals(
            PlaybackLimit(false, null, "premium", null, false),
            pluginStatusLimit(j("""{"playbackLimit":{"blocked":false,"planKey":"premium","searchesHeldBack":false}}""")),
        )
        assertEquals(
            PlaybackLimit(true, null, null, null, false),
            pluginStatusLimit(j("""{"playbackLimit":{"blocked":true,"planKey":7,"label":{},"message":null,"searchesHeldBack":"yes"}}""")),
        )
    }

    @Test
    fun `no verdict - a null status or a reply without a playbackLimit or a malformed one`() {
        assertNull(pluginStatusLimit(null))
        assertNull(pluginStatusLimit(j("\"signed-in\"")))
        assertNull(pluginStatusLimit(j("""{"signedIn":true,"playbackLimit":null,"statusLabel":"Signed in","statusMessage":null}""")))
        assertNull(pluginStatusLimit(j("""{"playbackLimit":{"blocked":"yes"}}""")))
        assertNull(pluginStatusLimit(j("""{"playbackLimit":"blocked"}""")))
        assertNull(pluginStatusLimit(j("""{"playbackLimit":{}}""")))
    }

    @Test
    fun `the loader's failed marker and anything not an object read as a failed read`() {
        assertEquals(PluginStatusEntry.READ_FAILED, parsePluginStatus("""{"failed":true}"""))
        assertEquals(PluginStatusEntry.READ_FAILED, parsePluginStatus("\"signed-in\""))
        assertEquals(PluginStatusEntry.READ_FAILED, parsePluginStatus("not json"))
        assertNull(parsePluginStatus(null))
        val amazon = parsePluginStatus("""{"signedIn":true,"playbackLimit":{"blocked":true,"planKey":"prime","label":"Prime: no playback","message":"$primeMessage","searchesHeldBack":true},"statusLabel":null,"statusMessage":"$primeMessage"}""")
        assertEquals(PluginStatusEntry(true, amazonPrime, null, primeMessage, false), amazon)
    }

    @Test
    fun `the Settings sign-in row - the reply's own two lines with signedIn and loading as fallbacks`() {
        assertEquals(PluginStatusRow("Free plan", "Playing needs Awesome Premium."), pluginStatusRow(PluginStatusEntry(statusLabel = "Free plan", statusMessage = "Playing needs Awesome Premium.")))
        assertEquals("Signed in", pluginStatusRow(PluginStatusEntry(signedIn = true)).label)
        assertEquals("Not signed in", pluginStatusRow(PluginStatusEntry(signedIn = false)).label)
        assertEquals("Checking…", pluginStatusRow(PluginStatusEntry(signedIn = null)).label)
        assertEquals(PluginStatusRow("Checking…", ""), pluginStatusRow(null))
        assertEquals(PluginStatusRow("Signed in", ""), pluginStatusRow(parsePluginStatus("""{"statusLabel":"Signed in","statusMessage":42}""")))
        assertEquals("Setup required · Sign in", PluginStatusRow("Setup required", "Sign in").line)
        assertEquals("Signed in", PluginStatusRow("Signed in", "").line)
    }

    @Test
    fun `the Settings sign-in row - a failed read says what happened and what to do`() {
        assertEquals(
            PluginStatusRow("Couldn’t check", "Parachord couldn’t check this plug-in’s status. Try opening this panel again."),
            pluginStatusRow(PluginStatusEntry.READ_FAILED),
        )
    }

    @Test
    fun `plugin entries - a no-verdict reply is dropped and a verdict stays`() {
        assertEquals(
            emptyMap(),
            pluginStatusLimits(
                mapOf(
                    "hung" to PluginStatusEntry.READ_FAILED,
                    "unsigned" to null,
                    "nothingToSay" to PluginStatusEntry(signedIn = true, statusLabel = "Signed in"),
                    "malformed" to parsePluginStatus("""{"playbackLimit":{"blocked":"yes"}}"""),
                ),
            ),
        )
        val blocked = PlaybackLimit(true, "Free: no playback", "free", "Needs Premium.", true)
        assertEquals(
            mapOf("awesomemusic" to blocked, "applemusic" to PlaybackLimit(false, planKey = "connected")),
            pluginStatusLimits(
                mapOf(
                    "awesomemusic" to PluginStatusEntry(playbackLimit = blocked),
                    "applemusic" to parsePluginStatus("""{"playbackLimit":{"blocked":false,"planKey":"connected"}}"""),
                ),
            ),
        )
    }

    @Test
    fun `the blocked ids and the tile label and the panel message`() {
        val limits = mapOf("amazonmusic" to amazonPrime, "other" to otherLimit(true), "soundcloud" to null)
        assertEquals(listOf("amazonmusic", "other"), planBlockedIds(limits))
        assertEquals(emptyList(), planBlockedIds(mapOf("other" to otherLimit(false))))
        assertEquals(TileStatus("Free: no playback", otherMessage), tileStatus(limits, "other"))
        assertEquals(TileStatus("Prime: no playback", primeMessage), tileStatus(limits, "amazonmusic"))
        assertEquals(TileStatus(null, null), tileStatus(limits, "soundcloud"))
        assertEquals(TileStatus(null, null), tileStatus(mapOf("other" to otherLimit(false)), "other"))
        assertEquals(otherMessage, limitMessage(limits, "other"))
        assertNull(limitMessage(limits, "bandcamp"))
    }

    @Test
    fun `notPlayingIds - plan-blocked and switched-off once each`() {
        assertEquals(listOf("amazonmusic", "youtube"), notPlayingIds(listOf("amazonmusic"), listOf("youtube", "amazonmusic")))
        val blocked = listOf("amazonmusic")
        assertSame(blocked, notPlayingIds(blocked, emptyList()))
    }

    @Test
    fun `tileStatus - the plan's label wins and a switched-off one says what's left`() {
        val limits = mapOf("amazonmusic" to PlaybackLimit(true, "Prime: no playback", message = "m"))
        assertEquals("Prime: no playback", tileStatus(limits, "amazonmusic", listOf("amazonmusic")).statusLabel)
        assertEquals("Search and links only", tileStatus(limits, "youtube", listOf("youtube")).statusLabel)
        assertNull(tileStatus(limits, "spotify", listOf("youtube")).statusLabel)
        assertTrue(tileStatus(limits, "youtube", listOf("youtube")).statusTitle!!.isNotEmpty())
    }
}
