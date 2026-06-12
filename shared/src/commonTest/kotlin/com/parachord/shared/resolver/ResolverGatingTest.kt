package com.parachord.shared.resolver

import com.parachord.shared.plugin.PluginManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the shared resolver-orchestration helpers extracted in #210 — the
 * pieces Android (`ResolverManager`) and iOS (`IosResolverCoordinator`) both run.
 */
class ResolverGatingTest {

    private fun plugin(id: String, resolve: Boolean = true) =
        PluginManager.PluginInfo(id = id, name = id, version = "1.0.0", capabilities = mapOf("resolve" to resolve))

    private val plugins = listOf(
        plugin("spotify"), plugin("applemusic"), plugin("soundcloud"),
        plugin("bandcamp"), plugin("youtube"), plugin("listenbrainz", resolve = false),
    )

    @Test
    fun axeIds_excludeNativeDisabledAndNonResolve_emptyActiveMeansAllOn() {
        val ids = ResolverGating.axeResolverIds(
            plugins = plugins,
            active = emptyList(),                       // all on
            disabled = setOf("youtube"),                // user-disabled
            nativeResolverIds = setOf("spotify", "applemusic"),
        )
        // native (spotify/applemusic) excluded, youtube disabled, listenbrainz
        // not resolve-capable → only bandcamp + soundcloud remain.
        assertEquals(setOf("soundcloud", "bandcamp"), ids.toSet())
    }

    @Test
    fun axeIds_nonEmptyActive_filtersToActiveSet() {
        val ids = ResolverGating.axeResolverIds(
            plugins = plugins,
            active = listOf("bandcamp"),                // only bandcamp active
            disabled = emptySet(),
            nativeResolverIds = setOf("spotify", "applemusic"),
        )
        assertEquals(listOf("bandcamp"), ids)
    }

    @Test
    fun rescore_bothAxesMatch_promotesTo095_passthroughWhenNoMatchedFields() {
        val sources = listOf(
            ResolvedSource(url = "u", sourceType = "soundcloud", resolver = "soundcloud",
                confidence = 0.9, matchedTitle = "Clover", matchedArtist = "Tundra 212"),
            // No matched fields → authoritative confidence preserved (direct-ID hint).
            ResolvedSource(url = "u2", sourceType = "spotify", resolver = "spotify",
                confidence = 1.0),
        )
        val scored = ResolverGating.rescore(sources, "Clover", "Tundra 212")
        assertEquals(0.95, scored[0].confidence)
        assertEquals(1.0, scored[1].confidence)   // untouched
    }

    @Test
    fun rescore_wrongArtist_collapsesTo050() {
        val sources = listOf(
            ResolvedSource(url = "u", sourceType = "applemusic", resolver = "applemusic",
                confidence = 0.9, matchedTitle = "Mariana", matchedArtist = "Some Other Band"),
        )
        val scored = ResolverGating.rescore(sources, "Mariana", "The Real Artist")
        assertEquals(0.50, scored[0].confidence)
    }

    @Test
    fun selectBestMatch_picksBidirectionalMatch_overWrongFirstResult() {
        data class R(val t: String, val a: String)
        val results = listOf(
            R("Mariana", "Wrong Band"),                 // title-only — desktop/Android-old would take this
            R("Mariana", "The Real Artist"),            // correct
        )
        val best = selectBestMatch(results, "Mariana", "The Real Artist", { it.t }, { it.a })
        assertEquals("The Real Artist", best?.a)
    }

    @Test
    fun selectBestMatch_targetWithExtraWords_stillMatches() {
        data class R(val t: String, val a: String)
        // The "THE Tallest Man on Earth" case: catalog string is a substring of
        // the target — one-directional `includes` would miss it; bidirectional hits.
        val results = listOf(R("Love Is All", "Tallest Man on Earth"))
        val best = selectBestMatch(results, "Love Is All", "THE Tallest Man on Earth", { it.t }, { it.a })
        assertTrue(best != null)
    }

    @Test
    fun selectBestMatch_noMatch_returnsNull() {
        data class R(val t: String, val a: String)
        val results = listOf(R("Completely Different", "Nobody"))
        assertNull(selectBestMatch(results, "Mariana", "The Real Artist", { it.t }, { it.a }))
    }
}
