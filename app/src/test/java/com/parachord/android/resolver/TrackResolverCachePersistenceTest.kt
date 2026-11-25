package com.parachord.android.resolver

import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.cache.PersistentJsonCache
import com.parachord.shared.resolver.ResolvedSource
import io.mockk.coEvery
import io.mockk.coVerify
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

    private val library = mockk<com.parachord.android.data.repository.LibraryRepository>(relaxed = true)

    private fun cache(wrongSongsDropped: Boolean = true): TrackResolverCache {
        val settings = mockk<SettingsStore>(relaxed = true)
        coEvery { settings.isTrackSourcesStoredIdPurgeDone() } returns true
        coEvery { settings.isWrongSongPurgeDone() } returns wrongSongsDropped
        every { settings.getResolverOrderFlow() } returns flowOf(emptyList())
        return TrackResolverCache(
            resolverManager = mockk(relaxed = true),
            resolverScoring = mockk(relaxed = true),
            libraryRepository = library,
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

    @Test fun `sources holding another song are dropped once`() = runBlocking {
        val first = cache()
        first.awaitLoaded()
        val local = ResolvedSource(url = "content://media/1", sourceType = "localfiles", resolver = "localfiles", matchedTitle = "Clover", matchedArtist = "Tundra 212", confidence = 0.95)
        val other = ResolvedSource(url = "spotify:track:x", sourceType = "spotify", resolver = "spotify", matchedTitle = "Worth Your Wild", matchedArtist = "Russell Dickerson", confidence = 1.0)
        first.putSources("Clover", "Tundra 212", listOf(local, other))
        first.putSources("Alive", "Tundra 212", listOf(local))
        repeat(50) { if (disk?.contains("alive") != true) kotlinx.coroutines.delay(20) }

        val second = cache(wrongSongsDropped = false)
        second.awaitLoaded()
        assertEquals(null, second.getSources("Clover", "Tundra 212"))
        assertEquals(1, second.getSources("Alive", "Tundra 212")?.size)
        coVerify(exactly = 1) { library.clearLookedUpLocalArtwork() }
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
