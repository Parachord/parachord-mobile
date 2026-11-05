package com.parachord.shared.ios

import com.parachord.shared.metadata.AlbumSearchResult
import com.parachord.shared.metadata.ArtistInfo
import com.parachord.shared.metadata.MetadataProvider
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.Track
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** #520: the iOS executor never plays or queues a different song from the one requested. */
class IosDjToolExecutorTest {

    private val found = mutableMapOf<String, List<TrackSearchResult>>()

    private val provider = object : MetadataProvider {
        override val name = "fake"
        override val priority = 0
        override suspend fun isAvailable() = true
        override suspend fun searchTracks(query: String, limit: Int) = found[query] ?: listOf(AVETT)
        override suspend fun searchAlbums(query: String, limit: Int) = emptyList<AlbumSearchResult>()
        override suspend fun searchArtists(query: String, limit: Int) = emptyList<ArtistInfo>()
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? = null
    }

    private val secure = object : SecureTokenStore {
        private val m = mutableMapOf<String, String>()
        override fun get(key: String): String? = m[key]
        override fun set(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun contains(key: String) = m.containsKey(key)
        override fun observe(key: String) = flowOf(m[key])
    }

    private var localFiles = emptyList<Track>()
    private val played = mutableListOf<String>()
    private val queued = mutableListOf<String>()
    private var cleared = 0
    private val bridge = IosChatPlaybackBridge().apply {
        onPlayTrack = { played += it.title }
        onAddToQueue = { queued += it.title }
        onClearQueue = { cleared++ }
    }

    private val executor = IosDjToolExecutor(
        MetadataService(listOf(provider), { emptySet() }),
        SettingsStore(secure, KvStore(MapSettings())),
        bridge,
        localFiles = { localFiles },
    )

    private fun searchFinds(artist: String, title: String) {
        found["$artist $title"] = listOf(AVETT, TrackSearchResult(title = title, artist = artist))
    }

    private fun track(artist: String, title: String) = mapOf("artist" to artist, "title" to title)

    @Test
    fun playRefusesASearchThatFoundOnlyOtherSongs() = runTest {
        val result = executor.execute("play", track("Biohio", "Happy Me"))

        assertEquals(false, result["success"])
        assertEquals("Could not find \"Happy Me\" by Biohio", result["error"])
        assertEquals(emptyList(), played)
    }

    /** #529: the catalogs don't have it, or the network is off. */
    @Test
    fun playFindsATrackThatIsOnlyInTheLocalLibrary() = runTest {
        localFiles = listOf(localFile("Zzqart", "Basement Demo"))

        val result = executor.execute("play", track("Zzqart", "Basement Demo"))

        assertEquals(true, result["success"])
        assertEquals(listOf("Basement Demo"), played)
    }

    @Test
    fun queueAddFindsATrackThatIsOnlyInTheLocalLibrary() = runTest {
        localFiles = listOf(localFile("Zzqart", "Basement Demo"))

        val result = executor.execute("queue_add", mapOf("tracks" to listOf(track("Zzqart", "Basement Demo"))))

        assertEquals(1, result["queued"])
        assertEquals(null, result["notFound"])
    }

    private fun localFile(artist: String, title: String) =
        Track(id = "local-1", title = title, artist = artist, resolver = "localfiles")

    @Test
    fun queueAddLeavesOutAMissingTrackAndSaysWhich() = runTest {
        searchFinds("A", "First")
        searchFinds("C", "Third")

        val result = executor.execute(
            "queue_add",
            mapOf("tracks" to listOf(track("A", "First"), track("Biohio", "Happy Me"), track("C", "Third"))),
        )

        assertEquals(true, result["success"])
        assertEquals(2, result["queued"])
        assertEquals(true, result["nowPlaying"])
        assertEquals(listOf(track("Biohio", "Happy Me")), result["notFound"])
        assertEquals(listOf("First"), played)
        assertEquals(listOf("Third"), queued)
    }

    @Test
    fun queueAddStartsWithTheFirstTrackFoundWhenTheFirstRequestedIsMissing() = runTest {
        searchFinds("B", "Second")
        searchFinds("C", "Third")

        val result = executor.execute(
            "queue_add",
            mapOf("tracks" to listOf(track("Biohio", "Happy Me"), track("B", "Second"), track("C", "Third"))),
        )

        assertEquals(true, result["success"])
        assertEquals(true, result["nowPlaying"])
        assertEquals(listOf(track("Biohio", "Happy Me")), result["notFound"])
        assertEquals(null, result["error"])
        assertEquals(listOf("Second"), played)
        assertEquals(listOf("Third"), queued)
        assertEquals(1, cleared)
    }

    @Test
    fun queueAddChangesNothingWhenEveryTrackIsMissing() = runTest {
        val result = executor.execute("queue_add", mapOf("tracks" to listOf(track("Biohio", "Happy Me"))))

        assertEquals(false, result["success"])
        assertEquals("Could not find any of the requested tracks", result["error"])
        assertEquals(0, cleared)
        assertEquals(emptyList(), queued)
    }

    private companion object {
        val AVETT = TrackSearchResult(title = "Untitled #4", artist = "The Avett Brothers")
    }
}
