package com.parachord.shared.home

import com.parachord.shared.model.Track
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.SHUFFLEUPAGUS_ID
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.ServiceColors
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
