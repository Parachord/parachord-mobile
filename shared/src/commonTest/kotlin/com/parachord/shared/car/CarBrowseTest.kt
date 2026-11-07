package com.parachord.shared.car

import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSource(
    var playlists: List<Playlist> = emptyList(),
    var rows: Map<String, List<PlaylistTrack>> = emptyMap(),
    var collection: List<Track> = emptyList(),
) : CarLibrarySource {
    override suspend fun playlists() = playlists
    override suspend fun playlist(id: String) = playlists.firstOrNull { it.id == id }
    override suspend fun playlistTracks(playlistId: String) = rows[playlistId].orEmpty()
    override suspend fun collectionTracks() = collection
    override fun toTrack(row: PlaylistTrack) =
        Track(id = "t-${row.playlistId}-${row.position}", title = row.trackTitle, artist = row.trackArtist)
}

private fun pl(id: String, name: String = id, updatedAt: Long = 0, owner: String? = null, art: String? = null) =
    Playlist(id = id, name = name, updatedAt = updatedAt, ownerName = owner, artworkUrl = art)

private fun row(pid: String, pos: Int) =
    PlaylistTrack(playlistId = pid, position = pos, trackTitle = "T$pos", trackArtist = "A$pos", trackAlbum = "Al$pos")

private fun tr(id: String) = Track(id = id, title = "Title $id", artist = "Artist $id", album = "Album $id", artworkUrl = "http://art/$id")

class CarBrowseTest {

    @Test
    fun `root lists Playlists — Loved Songs — Collection Radio in order`() = runTest {
        val items = CarBrowse(FakeSource()).children(CarIds.ROOT)
        assertEquals(listOf("browse:playlists", "browse:loved", "action:collection-radio"), items.map { it.id })
        assertEquals(listOf("Playlists", "Loved Songs", "Collection Radio"), items.map { it.title })
        assertTrue(items[0].isBrowsable && items[1].isBrowsable)
        assertTrue(items[2].isPlayable && !items[2].isBrowsable)
        assertEquals("Shuffle all loved tracks", items[2].subtitle)
    }

    @Test
    fun `playlists are newest-updated first with owner subtitle`() = runTest {
        val src = FakeSource(playlists = listOf(
            pl("old", updatedAt = 1, art = "file:/x"),
            pl("new", updatedAt = 9, owner = "Jane", art = "http://a"),
        ))
        val items = CarBrowse(src).children(CarIds.PLAYLISTS)
        assertEquals(listOf("playlist:new", "playlist:old"), items.map { it.id })
        assertEquals("Jane", items[0].subtitle)
        assertEquals("Parachord", items[1].subtitle)
        assertEquals("file:/x", items[1].artworkUrl)
        assertTrue(items.all { it.kind == CarItemKind.PLAYLIST && it.isBrowsable && !it.isPlayable })
    }

    @Test
    fun `a playlist opens to Shuffle then its tracks`() = runTest {
        val src = FakeSource(playlists = listOf(pl("p1", "Road")), rows = mapOf("p1" to listOf(row("p1", 0), row("p1", 1))))
        val items = CarBrowse(src).children("playlist:p1")
        assertEquals(listOf("playlist-shuffle:p1", "playlist-track:0:p1", "playlist-track:1:p1"), items.map { it.id })
        assertEquals("Shuffle", items[0].title)
        assertEquals("Road", items[0].subtitle)
        assertEquals(CarItemKind.TRACK, items[1].kind)
        assertEquals("T0", items[1].title)
        assertEquals("A0", items[1].subtitle)
        assertEquals("Al0", items[1].album)
    }

    @Test
    fun `a missing playlist row labels Shuffle with the track count`() = runTest {
        val src = FakeSource(rows = mapOf("gone" to listOf(row("gone", 0), row("gone", 1))))
        assertEquals("2 tracks", CarBrowse(src).children("playlist:gone")[0].subtitle)
    }

    @Test
    fun `an empty playlist has no Shuffle row`() = runTest {
        val src = FakeSource(playlists = listOf(pl("p1")))
        assertEquals(emptyList(), CarBrowse(src).children("playlist:p1"))
    }

    @Test
    fun `a playlist list is capped at 500`() = runTest {
        val src = FakeSource(playlists = (0 until 600).map { pl("p$it", updatedAt = it.toLong()) })
        assertEquals(500, CarBrowse(src).children(CarIds.PLAYLISTS).size)
        val rows = FakeSource(rows = mapOf("p" to (0 until 600).map { row("p", it) }))
        assertEquals(501, CarBrowse(rows).children("playlist:p").size) // Shuffle + 500
    }

    @Test
    fun `loved songs are the 100 most recent`() = runTest {
        val src = FakeSource(collection = (0 until 150).map { tr("$it") })
        val items = CarBrowse(src).children(CarIds.LOVED)
        assertEquals(100, items.size)
        assertEquals("loved-track:0", items[0].id)
        assertEquals("loved-track:99", items.last().id)
        assertTrue(items.all { it.isPlayable && !it.isBrowsable })
        assertEquals("http://art/0", items[0].artworkUrl)
    }

    @Test
    fun `playlist track ids round-trip when the playlist id contains a colon`() {
        assertEquals("hosted:xspf:abc" to 3, CarIds.parsePlaylistTrack(CarIds.playlistTrack("hosted:xspf:abc", 3)))
        assertNull(CarIds.parsePlaylistTrack("playlist:local-1"))
        assertNull(CarIds.parsePlaylistTrack("playlist-shuffle:local-1"))
        assertNull(CarIds.parsePlaylistTrack("playlist-track:x:local-1"))
        assertNull(CarIds.parsePlaylistTrack("playlist-track:3:"))
        assertNull(CarIds.parsePlaylistTrack("loved-track:abc"))
    }

    @Test
    fun `unknown parents have no children`() = runTest {
        assertEquals(emptyList(), CarBrowse(FakeSource()).children("nope"))
    }

    @Test
    fun `collection radio plays the whole collection shuffled`() = runTest {
        val src = FakeSource(collection = (0 until 150).map { tr("$it") })
        val req = CarBrowse(src).playRequest(CarIds.COLLECTION_RADIO)!!
        assertEquals(150, req.tracks.size)
        assertEquals(0, req.startIndex)
        assertEquals(true, req.shuffle)
    }

    @Test
    fun `a loved track plays the capped list from its index keeping shuffle`() = runTest {
        val src = FakeSource(collection = (0 until 150).map { tr("$it") })
        val req = CarBrowse(src).playRequest("loved-track:7")!!
        assertEquals(100, req.tracks.size)
        assertEquals(7, req.startIndex)
        assertNull(req.shuffle)
    }

    @Test
    fun `playlist requests play every row`() = runTest {
        val rows = (0 until 600).map { row("p", it) }
        val b = CarBrowse(FakeSource(rows = mapOf("p" to rows)))
        val whole = b.playRequest("playlist:p")!!
        assertEquals(600, whole.tracks.size)
        assertEquals(0, whole.startIndex)
        assertEquals(false, whole.shuffle)
        assertEquals(true, b.playRequest("playlist-shuffle:p")!!.shuffle)
    }

    @Test
    fun `a playlist track starts at its stored position — or 0 when it's gone`() = runTest {
        // positions 5, 8, 9: the stored position is not the index
        val src = FakeSource(rows = mapOf("p" to listOf(row("p", 5), row("p", 8), row("p", 9))))
        val b = CarBrowse(src)
        val req = b.playRequest(CarIds.playlistTrack("p", 8))!!
        assertEquals(1, req.startIndex)
        assertEquals(false, req.shuffle)
        assertEquals(0, b.playRequest(CarIds.playlistTrack("p", 42))!!.startIndex)
    }

    @Test
    fun `stale ids play nothing`() = runTest {
        val b = CarBrowse(FakeSource(collection = listOf(tr("a"))))
        assertNull(b.playRequest("loved-track:zzz"))
        assertNull(b.playRequest("playlist:missing"))
        assertNull(b.playRequest("playlist-shuffle:missing"))
        assertNull(b.playRequest(CarIds.playlistTrack("missing", 0)))
        assertNull(CarBrowse(FakeSource()).playRequest(CarIds.COLLECTION_RADIO))
        assertNull(b.playRequest("browse:playlists"))
    }

    @Test
    fun `item answers for the root — folders — radio — a playlist and a loved track`() = runTest {
        val src = FakeSource(playlists = listOf(pl("p1", "Road", owner = "Jane")), collection = listOf(tr("a")))
        val b = CarBrowse(src)
        val root = b.item(CarIds.ROOT)!!
        assertEquals("Parachord", root.title)
        assertTrue(root.isBrowsable && !root.isPlayable)
        assertEquals(CarItemKind.FOLDER, b.item(CarIds.PLAYLISTS)!!.kind)
        assertEquals("Loved Songs", b.item(CarIds.LOVED)!!.title)
        assertEquals("Shuffle all loved tracks", b.item(CarIds.COLLECTION_RADIO)!!.subtitle)
        val p = b.item("playlist:p1")!!
        assertEquals("Road", p.title)
        assertEquals("Jane", p.subtitle)
        val t = b.item("loved-track:a")!!
        assertEquals("Title a", t.title)
        assertEquals("Album a", t.album)
        assertNull(b.item("loved-track:zzz"))
        assertNull(b.item("playlist:nope"))
        assertNull(b.item("something-else"))
    }

    @Test
    fun `unknown ids are not browse-tree ids`() {
        val b = CarBrowse(FakeSource())
        assertTrue(b.isBrowseTreeId("action:collection-radio"))
        assertTrue(b.isBrowseTreeId("playlist:x"))
        assertTrue(b.isBrowseTreeId("playlist-shuffle:x"))
        assertTrue(b.isBrowseTreeId("playlist-track:1:x"))
        assertTrue(b.isBrowseTreeId("loved-track:x"))
        assertFalse(b.isBrowseTreeId("search-track:1"))
        assertFalse(b.isBrowseTreeId("something"))
        assertFalse(b.isBrowseTreeId("playlist-track:x:y"))
    }
}
