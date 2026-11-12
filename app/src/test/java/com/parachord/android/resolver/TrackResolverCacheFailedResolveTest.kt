package com.parachord.android.resolver

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.cache.PersistentJsonCache
import com.parachord.shared.resolver.ResolveReport
import com.parachord.shared.resolver.ResolvedSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #566: a resolve where some resolvers couldn't answer (offline, a Spotify
 * cooldown) isn't kept as complete. It stays off disk, and once the back-off
 * passes only the failed resolvers are asked again. Saved as complete, a
 * moment without network left Recent Loves showing only the Apple Music id
 * each track carried, for the 7 days the cache keeps sources.
 */
class TrackResolverCacheFailedResolveTest {

    private var disk: String? = null
    private var now = 1_000_000L
    private val resolverManager = mockk<ResolverManager>(relaxed = true)

    private fun cache(): TrackResolverCache {
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.getResolverOrderFlow() } returns flowOf(emptyList())
        return TrackResolverCache(
            resolverManager = resolverManager,
            resolverScoring = mockk(relaxed = true),
            libraryRepository = mockk(relaxed = true),
            settingsStore = settings,
            mbidEnrichment = mockk(relaxed = true),
            playlistTrackDao = mockk(relaxed = true),
            persisted = PersistentJsonCache(
                ListSerializer(ResolvedSource.serializer()),
                read = { disk }, write = { disk = it },
                ttlMs = 7L * 24 * 60 * 60 * 1000, maxEntries = 100, saveDelayMs = 0,
            ),
            nowMs = { now },
        )
    }

    private val track = TrackEntity(id = "t1", title = "I Know!", artist = "Swapmeet", appleMusicId = "1880091921")
    private val amHint = ResolvedSource(url = "applemusic:song:1880091921", sourceType = "applemusic", resolver = "applemusic", appleMusicId = "1880091921", confidence = 1.0)
    private val spotify = ResolvedSource(url = "spotify:track:abc", sourceType = "spotify", resolver = "spotify", spotifyId = "abc", confidence = 0.95)

    private suspend fun settle(cache: TrackResolverCache) {
        cache.resolveInBackground(listOf(track), backfillDb = false).join()
        delay(100)
    }

    @Test fun `a resolve with failures stays off disk and retries only the failed resolvers`() = runBlocking {
        coEvery { resolverManager.resolveWithHintsReport(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ResolveReport(listOf(amHint), asked = setOf("spotify", "soundcloud"), failed = setOf("spotify", "soundcloud"))
        coEvery { resolverManager.resolveReport(any(), any(), any(), any(), any(), any(), any()) } returns
            ResolveReport(listOf(spotify), asked = setOf("spotify", "soundcloud"))

        val cache = cache()
        cache.awaitLoaded()
        settle(cache)
        assertEquals(listOf("applemusic"), cache.getSources("I Know!", "Swapmeet")?.map { it.resolver })
        assertNull("a failed resolve isn't saved", disk)

        // Before the back-off: nothing is asked again.
        settle(cache)
        coVerify(exactly = 0) { resolverManager.resolveReport(any(), any(), any(), any(), any(), any(), any()) }

        // After it: only the resolvers that failed.
        now += 2 * 60 * 1000
        settle(cache)
        coVerify(exactly = 1) {
            resolverManager.resolveReport(any(), any(), any(), any(), setOf("spotify", "soundcloud"), any(), any())
        }
        assertEquals(listOf("applemusic", "spotify"), cache.getSources("I Know!", "Swapmeet")?.map { it.resolver })
        repeat(50) { if (disk == null) delay(20) }
        assertEquals("saved once every resolver answered", true, disk?.contains("spotify:track:abc"))
    }

    @Test fun `a complete resolve is saved`() = runBlocking {
        coEvery { resolverManager.resolveWithHintsReport(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ResolveReport(listOf(amHint, spotify), asked = setOf("spotify", "soundcloud"))
        val cache = cache()
        cache.awaitLoaded()
        settle(cache)
        repeat(50) { if (disk == null) delay(20) }
        assertEquals(true, disk?.contains("spotify:track:abc"))
    }

    @Test fun `entries a failed resolve saved before the fix are dropped once at startup`() = runBlocking {
        // Saved the old way: the stored Apple Music id alone, next to a real answer.
        val first = cache()
        first.awaitLoaded()
        first.putSources("I Know!", "Swapmeet", listOf(amHint))
        first.putSources("Poor Advice", "Basht.", listOf(spotify.copy(artworkUrl = "s.jpg")))
        // Up to 5 s for the debounced save: a slow CI runner took over 1 s.
        repeat(250) { if (disk?.contains("s.jpg") != true) delay(20) }

        val second = cache()
        second.awaitLoaded()
        assertNull(second.getSources("I Know!", "Swapmeet"))
        assertEquals(listOf("spotify"), second.getSources("Poor Advice", "Basht.")?.map { it.resolver })
    }
}
