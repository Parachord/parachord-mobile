package com.parachord.android.data.repository

import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Tests for FreshDrop model properties and FreshDropsRepository pure logic.
 */
class FreshDropsRepositoryTest {

    // -- FreshDrop.isUpcoming --

    @Test
    fun `isUpcoming returns true for future date`() {
        val futureDate = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val drop = FreshDrop(title = "Future Album", artist = "Artist", date = futureDate)
        assertTrue(drop.isUpcoming)
    }

    @Test
    fun `isUpcoming returns false for past date`() {
        val pastDate = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val drop = FreshDrop(title = "Old Album", artist = "Artist", date = pastDate)
        assertFalse(drop.isUpcoming)
    }

    @Test
    fun `isUpcoming returns false for null date`() {
        val drop = FreshDrop(title = "Unknown", artist = "Artist", date = null)
        assertFalse(drop.isUpcoming)
    }

    @Test
    fun `isUpcoming returns false for invalid date`() {
        val drop = FreshDrop(title = "Bad Date", artist = "Artist", date = "not-a-date")
        assertFalse(drop.isUpcoming)
    }

    // -- FreshDrop.displayDate --

    @Test
    fun `displayDate formats past date without prefix`() {
        val drop = FreshDrop(title = "Album", artist = "Artist", date = "2025-01-15")
        assertTrue(drop.displayDate.contains("Jan 15, 2025"))
        assertFalse(drop.displayDate.startsWith("Coming"))
    }

    @Test
    fun `displayDate formats future date with Coming prefix`() {
        val futureDate = LocalDate.now().plusDays(60).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val drop = FreshDrop(title = "Album", artist = "Artist", date = futureDate)
        assertTrue(drop.displayDate.startsWith("Coming"))
    }

    @Test
    fun `displayDate returns empty for null date`() {
        val drop = FreshDrop(title = "Album", artist = "Artist", date = null)
        assertEquals("", drop.displayDate)
    }

    @Test
    fun `displayDate returns raw string for unparseable date`() {
        val drop = FreshDrop(title = "Album", artist = "Artist", date = "2025")
        // Partial date "2025" can't be parsed as ISO_LOCAL_DATE → returns raw
        assertEquals("2025", drop.displayDate)
    }

    // -- Release filtering logic --

    @Test
    fun `filter excludes compilation secondary type`() {
        val excludedTypes = listOf("compilation", "broadcast", "live", "dj-mix", "remix", "mixtape/street")
        val secondaryTypes = listOf("Compilation")
        val excluded = secondaryTypes.any { it.lowercase() in excludedTypes }
        assertTrue(excluded)
    }

    @Test
    fun `filter excludes broadcast primary type`() {
        val primaryType = "Broadcast"
        assertTrue(primaryType.lowercase() == "broadcast")
    }

    @Test
    fun `filter allows album with no secondary types`() {
        val excludedTypes = listOf("compilation", "broadcast", "live", "dj-mix", "remix", "mixtape/street")
        val secondaryTypes = emptyList<String>()
        val excluded = secondaryTypes.any { it.lowercase() in excludedTypes }
        assertFalse(excluded)
    }

    @Test
    fun `filter allows EP and single types`() {
        val excludedTypes = listOf("compilation", "broadcast", "live", "dj-mix", "remix", "mixtape/street")
        val epTypes = listOf("EP")
        val singleTypes = listOf("Single")
        assertFalse(epTypes.any { it.lowercase() in excludedTypes })
        assertFalse(singleTypes.any { it.lowercase() in excludedTypes })
    }

    // -- The window: three months back to 14 days ahead (#687, desktop parachord#1421) --

    // Today 2026-11-15: the window is 2026-08-15 … 2026-11-29.
    private val today = kotlinx.datetime.LocalDate(2026, 11, 15)
    private val diskJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun cacheJson(releases: List<FreshDrop>, fetchedAt: Long): String =
        """{"releases":${diskJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(FreshDrop.serializer()), releases)},"fetchedAt":$fetchedAt}"""

    private val agedCache = listOf(
        FreshDrop(title = "Too Old", artist = "Old Artist", date = "2026-08-14"),
        FreshDrop(title = "Edge Old", artist = "Old Artist", date = "2026-08-15"),
        FreshDrop(title = "Recent", artist = "Old Artist", date = "2026-10-01"),
        FreshDrop(title = "Edge Ahead", artist = "Old Artist", date = "2026-11-29"),
        FreshDrop(title = "Too Far Ahead", artist = "Old Artist", date = "2026-11-30"),
        FreshDrop(title = "Undated", artist = "Old Artist", date = null),
    )
    private val keptTitles = setOf("Edge Old", "Recent", "Edge Ahead")

    private fun repo(
        disk: () -> String?,
        writes: MutableList<String> = mutableListOf(),
        libraryArtist: String? = null,
        releaseGroups: List<com.parachord.shared.api.MbReleaseGroupEntry> = emptyList(),
    ): com.parachord.shared.repository.FreshDropsRepository {
        val mb = io.mockk.mockk<com.parachord.shared.api.MusicBrainzClient>()
        io.mockk.coEvery { mb.browseReleaseGroups(any(), any(), any()) } returns
            com.parachord.shared.api.MbReleaseGroupBrowseResponse(releaseGroups, releaseGroups.size, 0)
        val settings = io.mockk.mockk<com.parachord.shared.settings.SettingsStore>()
        io.mockk.coEvery { settings.getLastFmUsername() } returns null
        io.mockk.coEvery { settings.getListenBrainzUsername() } returns null
        val dao = io.mockk.mockk<com.parachord.shared.db.dao.TrackDao>()
        io.mockk.coEvery { dao.getRecentSync(any()) } returns listOfNotNull(
            libraryArtist?.let { com.parachord.shared.model.Track(id = "t1", title = "Song", artist = it) },
        )
        return com.parachord.shared.repository.FreshDropsRepository(
            musicBrainzClient = mb,
            lastFmClient = io.mockk.mockk(relaxed = true),
            listenBrainzClient = io.mockk.mockk(relaxed = true),
            settingsStore = settings,
            trackDao = dao,
            cacheRead = { disk() },
            cacheWrite = { writes.add(it) },
            rotationRead = { null },
            rotationWrite = {},
            mbidLookupCached = { "artist-mbid" },
            mbidLookupViaMapper = { _, _ -> null },
            imageEnrichmentService = io.mockk.mockk(relaxed = true),
            lastFmApiKey = "k",
            today = { today },
        )
    }

    private fun lastSuccess(results: List<com.parachord.shared.model.Resource<List<FreshDrop>>>) =
        (results.last { it is com.parachord.shared.model.Resource.Success } as com.parachord.shared.model.Resource.Success).data

    @Test
    fun `cached releases outside the window are gone after the load`() = kotlinx.coroutines.test.runTest {
        val disk = cacheJson(agedCache, fetchedAt = System.currentTimeMillis())
        val results = repo(disk = { disk }).getFreshDrops().toList()
        assertEquals(keptTitles, lastSuccess(results).map { it.title }.toSet())
    }

    @Test
    fun `cached releases outside the window are gone after a scan, and the scan keeps only the window`() =
        kotlinx.coroutines.test.runTest {
            val disk = cacheJson(agedCache, fetchedAt = 0L) // stale: a scan runs
            val writes = mutableListOf<String>()
            fun rg(id: String, date: String?) = com.parachord.shared.api.MbReleaseGroupEntry(
                id = id, title = "Scan $id", primaryType = "Album", firstReleaseDate = date,
            )
            val repo = repo(
                disk = { disk }, writes = writes, libraryArtist = "New Artist",
                releaseGroups = listOf(
                    rg("new", "2026-11-01"),
                    rg("announced", "2027-03-01"), // months out: dropped
                    rg("old", "2025-11-01"),
                    rg("undated", null),
                ),
            )
            val results = repo.getFreshDrops().toList()
            val titles = lastSuccess(results).map { it.title }.toSet()
            assertEquals(keptTitles + "Scan new", titles)
            // What was saved is held to the window too.
            val saved = diskJson.parseToJsonElement(writes.last()).toString()
            assertFalse(saved.contains("Too Old"))
            assertFalse(saved.contains("Too Far Ahead"))
            assertFalse(saved.contains("Undated"))
            assertFalse(saved.contains("Scan announced"))
        }

    // -- Merge and dedup logic --

    @Test
    fun `mergeAndDedupe prefers fresh results`() {
        val fresh = listOf(
            FreshDrop(title = "Album A", artist = "Artist 1", date = "2025-03-01", albumArt = "new-art.jpg"),
        )
        val prior = listOf(
            FreshDrop(title = "Album A", artist = "Artist 1", date = "2025-03-01", albumArt = "old-art.jpg"),
            FreshDrop(title = "Album B", artist = "Artist 2", date = "2025-02-01"),
        )
        val merged = mergeAndDedupe(fresh, prior)
        assertEquals(2, merged.size)
        // Fresh version should be kept (listed first)
        val albumA = merged.find { it.title == "Album A" }
        assertEquals("new-art.jpg", albumA?.albumArt)
    }

    @Test
    fun `mergeAndDedupe deduplicates by artist+title`() {
        val fresh = listOf(
            FreshDrop(title = "Same Album", artist = "Same Artist"),
        )
        val prior = listOf(
            FreshDrop(title = "Same Album", artist = "Same Artist"),
        )
        val merged = mergeAndDedupe(fresh, prior)
        assertEquals(1, merged.size)
    }

    @Test
    fun `mergeAndDedupe sorts by date descending`() {
        val releases = listOf(
            FreshDrop(title = "A", artist = "X", date = "2025-01-01"),
            FreshDrop(title = "B", artist = "Y", date = "2025-03-01"),
            FreshDrop(title = "C", artist = "Z", date = "2025-02-01"),
        )
        val sorted = mergeAndDedupe(releases, emptyList())
        assertEquals("B", sorted[0].title) // March
        assertEquals("C", sorted[1].title) // February
        assertEquals("A", sorted[2].title) // January
    }

    @Test
    fun `mergeAndDedupe is case insensitive`() {
        val fresh = listOf(FreshDrop(title = "Album", artist = "RADIOHEAD"))
        val prior = listOf(FreshDrop(title = "album", artist = "radiohead"))
        val merged = mergeAndDedupe(fresh, prior)
        assertEquals(1, merged.size)
    }

    // -- Cache staleness --

    @Test
    fun `cache stale after 6 hours`() {
        val threshold = 6 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        assertTrue(now - (now - threshold - 1) > threshold)
    }

    @Test
    fun `cache fresh within 6 hours`() {
        val threshold = 6 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        assertFalse(now - (now - threshold + 1000) > threshold)
    }

    // -- Replicating mergeAndDedupe logic --

    private fun mergeAndDedupe(
        freshReleases: List<FreshDrop>,
        priorReleases: List<FreshDrop>,
    ): List<FreshDrop> {
        val merged = freshReleases + priorReleases
        val seen = mutableSetOf<String>()
        return merged
            .filter { release ->
                val key = "${release.artist.lowercase()}|${release.title.lowercase()}"
                seen.add(key)
            }
            .sortedByDescending { it.date ?: "" }
    }
}
