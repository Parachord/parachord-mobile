package com.parachord.shared.recommendations

import com.parachord.shared.deeplink.ProtocolTrack
import com.parachord.shared.deeplink.ResolvedProtocolPlay
import com.parachord.shared.model.Track
import com.parachord.shared.playback.PlaybackContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class RecommendationQueuesTest {

    private fun song(name: String, artist: String? = "Artist", duration: Double? = null, mbid: String? = null, isrc: String? = null) =
        RecommendationItem(
            type = RecommendationType.SONG, id = "id-$name", name = name, artist = artist,
            imageUrl = "https://img/$name", duration = duration, mbid = mbid, isrc = isrc,
        )

    @Test
    fun `recommendedSongTrack converts seconds to ms and keeps only valid ids`() {
        val t = recommendedSongTrack(song("Hello", duration = 215.0, mbid = "not-an-mbid", isrc = "gbaye0601498"))!!
        assertEquals(215000L, t.duration)
        assertNull(t.recordingMbid)
        assertEquals("GBAYE0601498", t.isrc)
        assertEquals("rec-" + trackSlug("Artist", "Hello"), t.id)
        assertEquals("https://img/Hello", t.artworkUrl)
        assertNull(recommendedSongTrack(song("X", artist = null)))
        assertNull(recommendedSongTrack(song("X", duration = 0.0))!!.duration)
    }

    @Test
    fun `songQueue starts at the tapped song of the filtered list`() {
        val items = listOf("a", "b", "c", "d").map { song(it) }
        val row = RecommendationRow(key = "type:song", type = RecommendationType.SONG, title = "For you")
        val q = songQueue(items, 2, row)!!
        assertEquals(listOf("c", "d"), q.tracks.map { it.title })
        assertEquals(PlaybackContext("recommendations", "For you", "type:song"), q.context)
        assertEquals("Recommendations", songQueue(items, 0, row.copy(title = null))!!.context.name)
    }

    @Test
    fun `songQueue skips songs with no artist`() {
        val items = listOf(song("a"), song("b", artist = null), song("c"))
        val row = RecommendationRow(key = "type:song", type = RecommendationType.SONG)
        assertEquals(listOf("c"), songQueue(items, 2, row)!!.tracks.map { it.title })
    }

    @Test
    fun `weeklyContext names the week`() {
        assertEquals(
            PlaybackContext("weekly-jam", "Week of Nov 17's Weekly Jams", "abc"),
            weeklyContext(WeeklyInfo("abc", "weekly-jam", "Weekly Jams", "Week of Nov 17")),
        )
    }

    private val playlist = RecommendationItem(
        type = RecommendationType.PLAYLIST, id = "p", name = "Mix", url = "https://x/p",
    )

    @Test
    fun `servicePlaylistQueue — no url or no tracks or a throw or null all give null`() = runTest {
        assertNull(servicePlaylistQueue(playlist.copy(url = null)) { error("not called") })
        assertNull(servicePlaylistQueue(playlist) { null })
        assertNull(servicePlaylistQueue(playlist) { ResolvedProtocolPlay("Mix", emptyList()) })
        assertNull(servicePlaylistQueue(playlist) { throw IllegalStateException("boom") })
    }

    @Test
    fun `servicePlaylistQueue — tracks keep their order with a playlist context`() = runTest {
        val q = servicePlaylistQueue(playlist) {
            ResolvedProtocolPlay("Mix", listOf(ProtocolTrack("A", "one"), ProtocolTrack("B", "two")), providerId = "plug")
        }!!
        assertEquals(listOf("one", "two"), q.tracks.map { it.title })
        assertEquals(PlaybackContext("playlist", "Mix", "https://x/p"), q.context)
    }

    @Test
    fun `listenerPlay — tracks play — none and failures say why`() = runTest {
        val tracks = listOf(Track(id = "1", title = "t", artist = "a"))
        val ok = listenerPlay("lastfm", "bob", "Bob") { _, _ -> tracks }
        assertEquals(PlaybackContext("friend", "Bob", "lastfm:bob"), assertIs<ListenerPlay.Play>(ok).queue.context)
        assertEquals(
            ListenerPlay.Message(RecommendationCopy.listenerNoTracks("Bob")),
            listenerPlay("lastfm", "bob", "Bob") { _, _ -> emptyList() },
        )
        assertEquals(
            ListenerPlay.Message(RecommendationCopy.listenerTracksFailed("Bob")),
            listenerPlay("lastfm", "bob", "Bob") { _, _ -> throw IllegalStateException("x") },
        )
    }

    @Test
    fun `achordionUserUrl escapes the name`() {
        assertEquals("https://achordion.xyz/user/a%20b%2Fc", achordionUserUrl("a b/c"))
    }

    @Test
    fun `View on Achordion is for ListenBrainz listeners only`() {
        assertTrue(showsAchordionLink("listenbrainz"))
        assertFalse(showsAchordionLink("lastfm"))
    }

    @Test
    fun `a song queue's context is the Recommendations context keyed by the row`() {
        val r = RecommendationRow(key = "type:song", type = RecommendationType.SONG, title = "Tracks")
        val items = listOf(RecommendationItem(type = RecommendationType.SONG, id = "s", name = "Song", artist = "A"))
        val ctx = songQueue(items, 0, r)!!.context
        assertEquals(RECOMMENDATIONS_CONTEXT, ctx.type)
        assertEquals("type:song", ctx.id)
    }
}
