package com.parachord.shared.home

import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.SHUFFLEUPAGUS_ID
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.ServiceColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** What the mixes, playlists and albums rows draw (both apps). */
class HomeRowsTest {

    private fun love(n: Int, album: String, art: String?) =
        Track(id = "t$n", title = "T$n", artist = "A$n", album = album, artworkUrl = art, inCollection = true)

    private fun mix(id: String = "m", description: String? = null, image: String? = null, mosaic: List<String> = emptyList()) =
        HistoryMix(id = id, name = id, title = "Daily Jams", description = description, imageUrl = image,
            url = "https://x", resolverId = "listenbrainz", resolverName = "ListenBrainz", mosaic = mosaic)

    private fun album(resolverId: String?, reason: String? = null, sourceIds: List<String> = emptyList()) =
        RecommendationItem(type = RecommendationType.ALBUM, id = "a", name = "Album", artist = "Artist", reason = reason,
            resolverId = resolverId, sourceIds = sourceIds)

    @Test
    fun `chips take the plug-in colour — Shuffleupagus the fill accent`() {
        assertEquals(DesignTokens.FILL_ACCENT, homeSourceChipColor(SHUFFLEUPAGUS_ID))
        assertEquals(ServiceColors.color("spotify"), homeSourceChipColor("spotify"))
    }

    @Test
    fun `Recent Loves is a 2x2 with four covered albums — else one cover`() {
        val four = (1..4).map { love(it, "al$it", "u$it") }
        val card = HomeMixCard.Loves(four, lovesMosaicAlbums(four), HomeCopy.lovesSubtitle(4))
        assertEquals(JumpTileArt(mosaic = listOf("u1", "u2", "u3", "u4")), mixCardArt(card))
        val three = listOf(love(1, "al1", "u1"), love(2, "al2", null), love(3, "al3", "u3"), love(4, "al4", "u4"))
        val card3 = HomeMixCard.Loves(three, lovesMosaicAlbums(three), HomeCopy.lovesSubtitle(4))
        assertEquals(JumpTileArt(imageUrl = "u1"), mixCardArt(card3))
    }

    @Test
    fun `a mix shows its image — else its mosaic`() {
        assertEquals(JumpTileArt(imageUrl = "img"), mixCardArt(HomeMixCard.Mix(mix(image = "img", mosaic = listOf("a")))))
        assertEquals(JumpTileArt(mosaic = listOf("a", "b")), mixCardArt(HomeMixCard.Mix(mix(mosaic = listOf("a", " ", "b")))))
    }

    @Test
    fun `a mix card's subtitle is its description — else blank`() {
        assertEquals("Your daily mix", mixCardSubtitle(HomeMixCard.Mix(mix(description = "Your daily mix"))))
        assertEquals("", mixCardSubtitle(HomeMixCard.Mix(mix(description = " "))))
        assertEquals("", mixCardSubtitle(HomeMixCard.Mix(mix(description = null))))
        val loves = HomeMixCard.Loves(emptyList(), emptyList(), "Your latest love")
        assertEquals("Your latest love", mixCardSubtitle(loves))
    }

    @Test
    fun `card keys are stable and distinct`() {
        assertEquals("loves", mixCardKey(HomeMixCard.Loves(emptyList(), emptyList(), "")))
        assertNotEquals(mixCardKey(HomeMixCard.Mix(mix("a"))), mixCardKey(HomeMixCard.Mix(mix("b"))))
    }

    @Test
    fun `only Shuffleupagus picks carry a reason badge`() {
        assertEquals("Because you love Portishead", albumReasonBadge(album(SHUFFLEUPAGUS_ID, "Because you love Portishead")))
        assertEquals(HomeCopy.PICKED_BY_SHUFFLEUPAGUS, albumReasonBadge(album(SHUFFLEUPAGUS_ID)))
        assertEquals(HomeCopy.PICKED_BY_SHUFFLEUPAGUS, albumReasonBadge(album(SHUFFLEUPAGUS_ID, "  ")))
        assertNull(albumReasonBadge(album("lastfm", "similar to X")))
        assertNull(albumReasonBadge(album(null)))
        // A merged item Shuffleupagus also sent carries the badge.
        assertEquals("why", albumReasonBadge(album("lastfm", "why", sourceIds = listOf("lastfm", SHUFFLEUPAGUS_ID))))
    }

    @Test
    fun `chips show with any source — All is the other chip`() {
        assertEquals(false, showSourceChips(emptyList()))
        assertEquals(true, showSourceChips(listOf(FilterChipSource("listenbrainz", "ListenBrainz"))))
        assertEquals(true, showSourceChips(listOf(FilterChipSource("a", "A"), FilterChipSource("b", "B"))))
    }

    private val allOn = HomeSections(
        banner = true, jump = true, importCard = false, mixes = true, playlists = true,
        albums = true, fresh = true, critical = true, concerts = true, ask = true,
    )

    @Test
    fun `a section with nothing to show is hidden even when its flag is on`() {
        val v = homeSectionVisibility(HomeState(sections = allOn))
        assertFalse(v.showBanner)
        assertFalse(v.showJump)
        assertFalse(v.showImport)
        assertFalse(v.showMixes)
        assertFalse(v.showPlaylists)
        assertFalse(v.showAlbums)
        assertFalse(v.showConcerts)
        assertTrue(v.showAsk)
    }

    @Test
    fun `a section with items shows only when its flag is on`() {
        val p = HomePlaylistCard(Playlist(id = "p", name = "P"), null, PlaylistSubtitle("Refreshed now", null))
        val on = homeSectionVisibility(HomeState(sections = allOn, playlists = listOf(p)))
        assertTrue(on.showPlaylists)
        val off = homeSectionVisibility(HomeState(sections = allOn.copy(playlists = false), playlists = listOf(p)))
        assertFalse(off.showPlaylists)
    }

    @Test
    fun `albums show their skeleton while loading with none and the import card takes Jump back in's place`() {
        val loading = homeSectionVisibility(HomeState(sections = allOn.copy(albums = false), albumsLoading = true))
        assertTrue(loading.albumsLoading)
        assertTrue(loading.showAlbums)
        val withAlbums = homeSectionVisibility(
            HomeState(sections = allOn, albumsLoading = true, albums = listOf(RecommendationItem(RecommendationType.ALBUM, "a", "A"))),
        )
        assertFalse(withAlbums.albumsLoading)
        assertTrue(withAlbums.showAlbums)

        val import = homeSectionVisibility(HomeState(showImportCard = true))
        assertTrue(import.showJump)
        assertTrue(import.showImport)
        val tiles = homeSectionVisibility(HomeState(showImportCard = true, tiles = listOf(JumpTile.Loves(emptyList()))))
        assertTrue(tiles.showJump)
        assertFalse(tiles.showImport)
    }

    @Test
    fun `the banner shows when there is one`() {
        val b = HomeBannerItem("a1", "Title", null, null, null, "info")
        assertTrue(homeSectionVisibility(HomeState(banner = b)).showBanner)
    }
}
