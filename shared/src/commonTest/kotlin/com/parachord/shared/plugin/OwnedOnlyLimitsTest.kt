package com.parachord.shared.plugin

import com.parachord.shared.home.PlaybackLimit
import com.parachord.shared.resolver.ResolvedSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A plan that plays only what the account owns (Amazon off Music Unlimited,
 * desktop #1494 / #1598): desktop's vectors from
 * `tests/renderer/playback-limits.test.js`, ported.
 */
class OwnedOnlyLimitsTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    private val primeMessage = "Amazon Prime: Parachord plays the songs you've bought from Amazon. Playing anything else from Amazon needs Music Unlimited."
    private val unknownMessage = "Parachord plays the songs you've bought from Amazon. Playing anything else from Amazon needs Music Unlimited, which Parachord couldn't find on your account."

    private fun amazonReply(plan: String): String = when (plan) {
        "unlimited" -> """{"signedIn":true,"playbackLimit":{"blocked":false,"planKey":"unlimited","label":null,"message":null,"searchesHeldBack":true},"statusLabel":null,"statusMessage":null}"""
        "prime" -> """{"signedIn":true,"playbackLimit":{"blocked":false,"ownedOnly":true,"planKey":"prime","label":"Prime: purchases only","message":"$primeMessage","searchesHeldBack":true},"statusLabel":null,"statusMessage":"$primeMessage"}"""
        else -> """{"signedIn":true,"playbackLimit":{"blocked":false,"ownedOnly":true,"planKey":"$plan","label":"Purchases only","message":"$unknownMessage","searchesHeldBack":true},"statusLabel":null,"statusMessage":"$unknownMessage"}"""
    }
    private fun amazonLimit(plan: String) = pluginStatusLimit(j(amazonReply(plan)))!!
    private fun otherLimit(blocked: Boolean) = PlaybackLimit(
        blocked, if (blocked) "Free: no playback" else null, if (blocked) "free" else "premium", null,
    )

    @Test
    fun `the plugin's ownedOnly entry keeps the resolver in playback`() {
        val prime = amazonLimit("prime")
        assertEquals(PlaybackLimit(false, "Prime: purchases only", "prime", primeMessage, true, ownedOnly = true), prime)
        assertEquals(emptyList(), planBlockedIds(mapOf("amazonmusic" to prime)))
        assertEquals(emptyList(), notPlayingIds(planBlockedIds(mapOf("amazonmusic" to prime)), emptySet()))
    }

    @Test
    fun `ownedOnly is only ever an unblocked entry's and only when said`() {
        assertFalse(pluginStatusLimit(j("""{"playbackLimit":{"blocked":true,"ownedOnly":true}}"""))!!.ownedOnly)
        assertFalse(pluginStatusLimit(j("""{"playbackLimit":{"blocked":false,"ownedOnly":"yes"}}"""))!!.ownedOnly)
        assertFalse(amazonLimit("unlimited").ownedOnly)
    }

    @Test
    fun `the tile says what still plays`() {
        val prime = amazonLimit("prime")
        assertEquals(TileStatus("Prime: purchases only", primeMessage), tileStatus(mapOf("amazonmusic" to prime), "amazonmusic"))
        assertEquals("Purchases only", tileStatus(mapOf("amazonmusic" to PlaybackLimit(false, ownedOnly = true)), "amazonmusic").statusLabel)
        assertNull(limitMessage(mapOf("amazonmusic" to prime), "amazonmusic"))
        // A plan limit wins over the Play-from switch.
        assertEquals("Prime: purchases only", tileStatus(mapOf("amazonmusic" to prime), "amazonmusic", setOf("amazonmusic")).statusLabel)
    }

    @Test
    fun `an upgrade searches again only where searches were held back`() {
        assertEquals(LimitTransition(true, false, "0"), limitTransition("1", amazonLimit("unlimited")))
        assertEquals(LimitTransition(false, false, "0"), limitTransition("1", otherLimit(false)))
        assertEquals(LimitTransition(false, false, "1"), limitTransition("1", otherLimit(true)))
        assertEquals(LimitTransition(false, false, "0"), limitTransition(null, amazonLimit("unlimited")))
        assertEquals(LimitTransition(false, false, null), limitTransition("1", null))
        assertEquals("playback_limit_blocked:amazonmusic", limitStorageKey("amazonmusic"))
    }

    @Test
    fun `entering it purges and searches again and leaving it for Unlimited searches again`() {
        val prime = amazonLimit("prime")
        assertEquals(LimitTransition(true, true, "owned"), limitTransition("0", prime))
        assertEquals(LimitTransition(true, true, "owned"), limitTransition("1", prime))
        assertEquals(LimitTransition(true, true, "owned"), limitTransition(null, prime))
        assertEquals(LimitTransition(false, false, "owned"), limitTransition("owned", prime))
        assertEquals(LimitTransition(true, false, "0"), limitTransition("owned", amazonLimit("unlimited")))
        assertEquals(LimitTransition(false, false, "owned"), limitTransition("owned", amazonLimit("unknown")))
        assertEquals(LimitTransition(false, false, "1"), limitTransition("owned", otherLimit(true)))
    }

    private fun src(resolver: String, noMatch: Boolean = false) =
        ResolvedSource(url = "u", sourceType = "stream", resolver = resolver, noMatch = noMatch)

    @Test
    fun `clearing drops misses always and found sources only on a purge`() {
        val found = src("amazonmusic")
        val miss = src("amazonmusic", noMatch = true)
        val other = src("spotify")
        assertEquals(listOf(found, other), clearResolverSources(listOf(found, miss, other), "amazonmusic", dropFound = false))
        assertEquals(listOf(other), clearResolverSources(listOf(found, miss, other), "amazonmusic", dropFound = true))
        val untouched = listOf(found, other)
        assertSame(untouched, clearResolverSources(untouched, "amazonmusic", dropFound = false))
        assertTrue(asksResolverAgain(listOf(other), "amazonmusic"))
        assertTrue(asksResolverAgain(listOf(miss, other), "amazonmusic"))
        assertFalse(asksResolverAgain(listOf(found), "amazonmusic"))
    }
}
