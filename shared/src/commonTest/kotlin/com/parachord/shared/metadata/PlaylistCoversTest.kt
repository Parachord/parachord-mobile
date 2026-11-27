package com.parachord.shared.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A playlist's own cover wins; the mosaic is the fallback (#852, desktop parachord#1394). */
class PlaylistCoversTest {
    private val mosaic = "file:/data/playlist_mosaics/p.jpg?v=1"
    private val own = "https://i.scdn.co/image/ab67706c0000da84"
    private val spotifyStock = "https://mosaic.scdn.co/640/ab67616d0000b273"
    private val appleStock = "https://is1-ssl.mzstatic.com/image/thumb/Features/AM.PDCXS11.jpg/600x600bb.jpg"

    @Test fun stockArtIsNotACover() {
        assertTrue(PlaylistCovers.isStockCover(spotifyStock))
        assertTrue(PlaylistCovers.isStockCover(appleStock))
        assertFalse(PlaylistCovers.isStockCover(own))
        assertFalse(PlaylistCovers.isStockCover(null))
    }

    @Test fun onlyTheProvidersOwnImageIsTheCover() {
        assertTrue(PlaylistCovers.isOwnCover(own))
        assertFalse(PlaylistCovers.isOwnCover(mosaic))
        assertFalse(PlaylistCovers.isOwnCover(spotifyStock))
        assertFalse(PlaylistCovers.isOwnCover(""))
        // A track's cover standing in for the playlist's.
        assertFalse(PlaylistCovers.isOwnCover("https://img/a.jpg", listOf("https://img/a.jpg", null)))
    }

    @Test fun aPullGivesTheSourcesCoverAndStockArtGivesTheMosaicBack() {
        assertEquals(own, PlaylistCovers.coverAfterPull(mosaic, own))
        assertEquals(own, PlaylistCovers.coverAfterPull(null, own))
        assertEquals(mosaic, PlaylistCovers.coverAfterPull(mosaic, spotifyStock))
        assertNull(PlaylistCovers.coverAfterPull(own, spotifyStock))
        // A service that sends no covers (ListenBrainz) leaves the art alone.
        assertEquals(own, PlaylistCovers.coverAfterPull(own, null))
        assertEquals(mosaic, PlaylistCovers.coverAfterPull(mosaic, null))
    }

    @Test fun aHostedUpdateKeepsTheDocumentsImageOrTheCoverItHad() {
        val image = "https://example.com/cover.png"
        assertEquals(image, PlaylistCovers.coverAfterHostedUpdate(own, image, emptyList()))
        assertEquals(own, PlaylistCovers.coverAfterHostedUpdate(own, null, emptyList()))
        assertNull(PlaylistCovers.coverAfterHostedUpdate(mosaic, null, emptyList()))
        assertNull(PlaylistCovers.coverAfterHostedUpdate("https://img/a.jpg", null, listOf("https://img/a.jpg")))
    }
}
