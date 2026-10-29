package com.parachord.android.resolver

import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.cache.PersistentJsonCache
import com.parachord.shared.resolver.ResolvedSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resolved sources survive a restart (desktop keeps them 7 days). Before, the
 * cache started empty on every cold start, so an Android Auto play had nothing
 * but the track's stored IDs to route by (#452). Every resolver's sources are
 * kept, not only the ones with a DB column.
 */
class TrackResolverCachePersistenceTest {

    private var disk: String? = null

    private fun cache(): TrackResolverCache {
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.getResolverOrderFlow() } returns flowOf(emptyList())
        return TrackResolverCache(
            resolverManager = mockk(relaxed = true),
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

    private val sources = listOf(
        ResolvedSource(url = "applemusic:song:1880091921", sourceType = "applemusic", resolver = "applemusic", appleMusicId = "1880091921", confidence = 1.0),
        ResolvedSource(url = "https://swapmeet.bandcamp.com/track/i-know", sourceType = "bandcamp", resolver = "bandcamp", confidence = 0.95),
        ResolvedSource(url = "content://media/external/audio/media/42", sourceType = "localfiles", resolver = "localfiles", confidence = 0.95),
    )

    @Test fun `sources from every resolver are back after a restart`() = runBlocking {
        val first = cache()
        first.awaitLoaded()
        first.putSources("I Know!", "Swapmeet", sources)
        repeat(50) { if (disk == null) kotlinx.coroutines.delay(20) }

        val second = cache()
        second.awaitLoaded()
        assertEquals(listOf("applemusic", "bandcamp", "localfiles"), second.getSources("I Know!", "Swapmeet")?.map { it.resolver })
    }

    @Test fun `an empty answer is not saved`() = runBlocking {
        val first = cache()
        first.awaitLoaded()
        first.putSources("Nothing", "Nobody", emptyList())
        kotlinx.coroutines.delay(200)
        val second = cache()
        second.awaitLoaded()
        assertEquals(null, second.getSources("Nothing", "Nobody"))
    }
}
