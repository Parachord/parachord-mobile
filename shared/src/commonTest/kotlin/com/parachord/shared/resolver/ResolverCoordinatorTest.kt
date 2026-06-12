package com.parachord.shared.resolver

import com.parachord.shared.plugin.PluginManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-tests the shared [ResolverCoordinator] orchestration (#210, Task 1):
 * the fan-out (spotify lambda + native ids + axe ids), the active/exclude gate,
 * the re-score, and the two return contracts (`resolveScored` unranked vs
 * `resolveRanked` floored+priority-ordered).
 *
 * ## Why narrow lambda inputs instead of `SettingsStore` / `PluginManager`
 *
 * The plan sketched the coordinator constructor as taking the concrete
 * `SettingsStore` + `PluginManager`. Both are platform-tied (SettingsStore wraps
 * a `KvStore`/`SecureTokenStore`; PluginManager wraps a `PluginFileAccess` +
 * `JsRuntime`) and can't be cheaply constructed in `commonTest`. Per the Task 1
 * guidance ("REFACTOR the coordinator to depend on narrow suspend-lambda inputs
 * … the same lambda-forwarding pattern used elsewhere in `shared/`"), the
 * coordinator instead takes `getActive` / `getDisabled` / `getPlugins` lambdas.
 * The Android/iOS bindings close over `settingsStore.getActiveResolvers()`,
 * `settingsStore.getDisabledPlugins()`, and `pluginManager.plugins.value` — so
 * production behavior is identical, and the test stays real (real
 * [ResolverScoring], real [PluginManager.PluginInfo], real [ResolverGating]).
 */
class ResolverCoordinatorTest {

    /** In-memory [ResolverRuntime]: a `resolverId → ResolvedSource` map. Records
     *  which native + axe ids the coordinator actually asked to resolve. */
    private class FakeRuntime(
        override val nativeResolverIds: Set<String>,
        private val natives: Map<String, ResolvedSource> = emptyMap(),
        private val axes: Map<String, ResolvedSource> = emptyMap(),
    ) : ResolverRuntime {
        val askedNative = mutableListOf<String>()
        val askedAxe = mutableListOf<String>()

        override suspend fun resolveNative(
            resolverId: String,
            query: String,
            targetTitle: String?,
            targetArtist: String?,
        ): ResolvedSource? {
            askedNative += resolverId
            return natives[resolverId]
        }

        override suspend fun resolveAxe(
            resolverId: String,
            query: String,
            targetTitle: String?,
            targetArtist: String?,
        ): ResolvedSource? {
            askedAxe += resolverId
            return axes[resolverId]
        }
    }

    private fun plugin(id: String, resolve: Boolean = true) =
        PluginManager.PluginInfo(id = id, name = id, version = "1.0.0", capabilities = mapOf("resolve" to resolve))

    private fun source(
        resolver: String,
        matchedTitle: String? = null,
        matchedArtist: String? = null,
        confidence: Double? = 0.9,
    ) = ResolvedSource(
        url = "u-$resolver",
        sourceType = resolver,
        resolver = resolver,
        confidence = confidence,
        matchedTitle = matchedTitle,
        matchedArtist = matchedArtist,
    )

    /** Real [ResolverScoring] with the canonical priority order and "all active". */
    private fun scoring() = ResolverScoring(
        getResolverOrder = { ResolverScoring.CANONICAL_RESOLVER_ORDER },
        getActiveResolvers = { emptyList() },
    )

    private fun coordinator(
        runtime: ResolverRuntime,
        plugins: List<PluginManager.PluginInfo> = emptyList(),
        active: List<String> = emptyList(),
        disabled: Set<String> = emptySet(),
        spotify: suspend (String) -> ResolvedSource? = { null },
    ) = ResolverCoordinator(
        runtime = runtime,
        scoring = scoring(),
        getActive = { active },
        getDisabled = { disabled },
        getPlugins = { plugins },
        resolveSpotify = spotify,
    )

    @Test
    fun resolveScored_fansOutSpotifyNativesAxe_unionRescored_unranked() = runTest {
        val runtime = FakeRuntime(
            nativeResolverIds = setOf("applemusic", "soundcloud", "localfiles"),
            natives = mapOf(
                // applemusic: correct both-match → 0.95
                "applemusic" to source("applemusic", matchedTitle = "Clover", matchedArtist = "Tundra 212"),
                // soundcloud: wrong artist (single-axis) → collapses to 0.50, stays present (unranked)
                "soundcloud" to source("soundcloud", matchedTitle = "Clover", matchedArtist = "Wrong Band"),
            ),
            axes = mapOf("bandcamp" to source("bandcamp", matchedTitle = "Clover", matchedArtist = "Tundra 212")),
        )
        val coord = coordinator(
            runtime,
            plugins = listOf(plugin("bandcamp")),
            spotify = { source("spotify", matchedTitle = "Clover", matchedArtist = "Tundra 212") },
        )

        val out = coord.resolveScored("Tundra 212 Clover", targetTitle = "Clover", targetArtist = "Tundra 212")
        val byResolver = out.associateBy { it.resolver }

        // Union: spotify + applemusic + soundcloud + bandcamp (localfiles returned null).
        assertEquals(setOf("spotify", "applemusic", "soundcloud", "bandcamp"), byResolver.keys)
        // Re-scored: both-match → 0.95, single-axis → 0.50.
        assertEquals(0.95, byResolver["spotify"]!!.confidence)
        assertEquals(0.95, byResolver["applemusic"]!!.confidence)
        assertEquals(0.95, byResolver["bandcamp"]!!.confidence)
        // UNRANKED: the sub-floor 0.50 source is still present (no floor applied here).
        assertEquals(0.50, byResolver["soundcloud"]!!.confidence)
    }

    @Test
    fun resolveRanked_dropsSubFloor_andOrdersByPriority() = runTest {
        val runtime = FakeRuntime(
            nativeResolverIds = setOf("applemusic", "soundcloud", "localfiles"),
            natives = mapOf(
                "applemusic" to source("applemusic", matchedTitle = "Clover", matchedArtist = "Tundra 212"),
                // single-axis → 0.50, must be dropped by the 0.60 floor
                "soundcloud" to source("soundcloud", matchedTitle = "Clover", matchedArtist = "Wrong Band"),
            ),
        )
        val coord = coordinator(
            runtime,
            spotify = { source("spotify", matchedTitle = "Clover", matchedArtist = "Tundra 212") },
        )

        val out = coord.resolveRanked("Tundra 212 Clover", targetTitle = "Clover", targetArtist = "Tundra 212")

        // soundcloud (0.50) dropped by floor; spotify before applemusic by canonical priority.
        assertEquals(listOf("spotify", "applemusic"), out.map { it.resolver })
        assertTrue(out.none { it.resolver == "soundcloud" })
    }

    @Test
    fun excludeResolvers_skipsNamedResolver_neverAsked() = runTest {
        val runtime = FakeRuntime(
            nativeResolverIds = setOf("applemusic", "soundcloud", "localfiles"),
            natives = mapOf(
                "applemusic" to source("applemusic", matchedTitle = "Clover", matchedArtist = "Tundra 212"),
                "soundcloud" to source("soundcloud", matchedTitle = "Clover", matchedArtist = "Tundra 212"),
            ),
            axes = mapOf("bandcamp" to source("bandcamp", matchedTitle = "Clover", matchedArtist = "Tundra 212")),
        )
        var spotifyAsked = false
        val coord = coordinator(
            runtime,
            plugins = listOf(plugin("bandcamp")),
            spotify = { spotifyAsked = true; source("spotify") },
        )

        val out = coord.resolveScored(
            "Tundra 212 Clover",
            targetTitle = "Clover",
            targetArtist = "Tundra 212",
            excludeResolvers = setOf("spotify", "soundcloud", "bandcamp"),
        )

        // Excluded spotify (lambda), soundcloud (native), bandcamp (axe) were never asked.
        assertFalse(spotifyAsked)
        assertFalse("soundcloud" in runtime.askedNative)
        assertFalse("bandcamp" in runtime.askedAxe)
        // applemusic (not excluded) still resolved.
        assertTrue("applemusic" in runtime.askedNative)
        assertEquals(setOf("applemusic"), out.map { it.resolver }.toSet())
    }

    @Test
    fun nativeIdNotInRuntimeSet_isNeverResolved() = runTest {
        // youtube is NOT in nativeResolverIds and has no resolve-capable plugin →
        // it must never be asked as native, and never appear in output.
        val runtime = FakeRuntime(
            nativeResolverIds = setOf("applemusic"),
            natives = mapOf(
                "applemusic" to source("applemusic", matchedTitle = "Clover", matchedArtist = "Tundra 212"),
                // even if the fake "knows" youtube, the coordinator must not ask for it
                "youtube" to source("youtube", matchedTitle = "Clover", matchedArtist = "Tundra 212"),
            ),
        )
        val coord = coordinator(runtime, spotify = { null })

        val out = coord.resolveScored("Tundra 212 Clover", targetTitle = "Clover", targetArtist = "Tundra 212")

        assertFalse("youtube" in runtime.askedNative)
        assertEquals(listOf("applemusic"), runtime.askedNative)
        assertNull(out.firstOrNull { it.resolver == "youtube" })
        assertNotNull(out.firstOrNull { it.resolver == "applemusic" })
    }

    @Test
    fun emptyResults_returnEmptyList_onBothContracts() = runTest {
        // Nothing registered in the runtime, spotify off → exercises the
        // selectRanked empty-input short-circuit through the coordinator.
        val runtime = FakeRuntime(nativeResolverIds = setOf("applemusic"))
        val coord = coordinator(runtime, spotify = { null })
        assertEquals(emptyList(), coord.resolveScored("q", "Clover", "Tundra 212"))
        assertEquals(emptyList(), coord.resolveRanked("q", "Clover", "Tundra 212"))
    }

    @Test
    fun nonEmptyActive_filtersSpotifyAndAxe_keepsOnlyActive() = runTest {
        // active = [applemusic] only — this is the iOS path (active is always a
        // non-empty seeded set there). spotify (lambda) + bandcamp (axe) must be
        // gated OUT by the `id in active` check; only the applemusic native runs.
        // The other tests only exercise the `active.isEmpty()` (Android) branch.
        val runtime = FakeRuntime(
            nativeResolverIds = setOf("applemusic"),
            natives = mapOf("applemusic" to source("applemusic", "Clover", "Tundra 212")),
            axes = mapOf("bandcamp" to source("bandcamp", "Clover", "Tundra 212")),
        )
        var spotifyAsked = false
        val coord = coordinator(
            runtime,
            plugins = listOf(plugin("bandcamp")),
            active = listOf("applemusic"),
            spotify = { spotifyAsked = true; source("spotify", "Clover", "Tundra 212") },
        )
        val out = coord.resolveScored("Tundra 212 Clover", targetTitle = "Clover", targetArtist = "Tundra 212")
        assertFalse(spotifyAsked)                        // spotify not in active → never asked
        assertFalse("bandcamp" in runtime.askedAxe)      // bandcamp not in active → never asked
        assertEquals(listOf("applemusic"), runtime.askedNative)
        assertEquals(setOf("applemusic"), out.map { it.resolver }.toSet())
    }
}
