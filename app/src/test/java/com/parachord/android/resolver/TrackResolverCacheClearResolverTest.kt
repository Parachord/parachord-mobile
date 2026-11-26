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
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A plan change (shared `limitTransition`, desktop #1494): entering a
 * purchases-only plan drops the resolver's saved sources, found or missed, and
 * the next resolve of a row asks only that resolver again.
 */
class TrackResolverCacheClearResolverTest {

    private var disk: String? = null
    private val resolverManager = mockk<ResolverManager>(relaxed = true)

    private fun cache(): TrackResolverCache {
        val settings = mockk<SettingsStore>(relaxed = true)
        // The #850 clean-up isn't what these test; its first run made the load
        // outlast awaitLoaded's 3 s on a slow CI runner.
        io.mockk.coEvery { settings.isWrongSongPurgeDone() } returns true
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
        )
    }

    private val track = TrackEntity(id = "t1", title = "Judy Is a Punk", artist = "Ramones")
    private val amazon = ResolvedSource(url = "amazonmusic:track:B0", sourceType = "amazonmusic", resolver = "amazonmusic", confidence = 0.95)
    private val spotify = ResolvedSource(url = "spotify:track:abc", sourceType = "spotify", resolver = "spotify", spotifyId = "abc", confidence = 0.95)
    private val amazonOwned = amazon.copy(url = "amazonmusic:track:B1")

    @Test fun `a purge drops the resolver's sources and asks only it again`() = runBlocking {
        coEvery { resolverManager.resolveWithHintsReport(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ResolveReport(listOf(spotify, amazon), asked = setOf("spotify", "amazonmusic"))
        coEvery { resolverManager.resolveReport(any(), any(), any(), any(), any(), any(), any()) } returns
            ResolveReport(listOf(amazonOwned), asked = setOf("amazonmusic"))
        val cache = cache()
        cache.awaitLoaded()
        cache.resolveInBackground(listOf(track), backfillDb = false).join()
        delay(100)
        assertEquals(listOf("spotify", "amazonmusic"), cache.getSources(track.title, track.artist)?.map { it.resolver })

        cache.clearResolver("amazonmusic", sourcesToo = true)
        assertEquals(listOf("spotify"), cache.getSources(track.title, track.artist)?.map { it.resolver })
        repeat(50) { if (disk?.contains("amazonmusic") == true) delay(20) }
        assertFalse("the saved copy goes too", disk?.contains("amazonmusic:track:B0") == true)

        cache.resolveInBackground(listOf(track), backfillDb = false).join()
        delay(100)
        coVerify(exactly = 1) {
            resolverManager.resolveReport(any(), any(), any(), any(), setOf("amazonmusic"), any(), any())
        }
        assertEquals(
            listOf("amazonmusic:track:B1"),
            cache.getSources(track.title, track.artist)?.filter { it.resolver == "amazonmusic" }?.map { it.url },
        )
    }
}
