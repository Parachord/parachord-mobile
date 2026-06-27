package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors desktop `tests/protocol/playlist-url-classify.test.js` (parachord#930) —
 * the pure routing DECISION for `parachord://play/playlist?url=<provider-url>`.
 * The orchestrator does the fetching/sniffing; this verifies classification only.
 */
class PlaylistUrlClassifierTest {

    @Test fun achordionPlaylistMapsToXspfApi() {
        assertEquals(
            PlaylistUrlKind.Achordion("https://achordion.xyz/api/playlist/c2accebd-ccd1-42c6-8ce7-ec0e8cf6cd13/xspf"),
            classifyPlaylistUrl("https://achordion.xyz/playlist/c2accebd-ccd1-42c6-8ce7-ec0e8cf6cd13"),
        )
    }

    @Test fun achordionWwwHostAccepted() {
        assertTrue(
            classifyPlaylistUrl("https://www.achordion.xyz/playlist/c2accebd-ccd1-42c6-8ce7-ec0e8cf6cd13")
                is PlaylistUrlKind.Achordion,
        )
    }

    @Test fun achordionUppercaseMbidLowercased() {
        val k = classifyPlaylistUrl("https://achordion.xyz/playlist/C2ACCEBD-CCD1-42C6-8CE7-EC0E8CF6CD13")
        assertEquals(
            "https://achordion.xyz/api/playlist/c2accebd-ccd1-42c6-8ce7-ec0e8cf6cd13/xspf",
            (k as PlaylistUrlKind.Achordion).xspfUrl,
        )
    }

    @Test fun achordionTrailingSlashTolerated() {
        assertTrue(
            classifyPlaylistUrl("https://achordion.xyz/playlist/c2accebd-ccd1-42c6-8ce7-ec0e8cf6cd13/")
                is PlaylistUrlKind.Achordion,
        )
    }

    @Test fun achordionNonUuidPathIsStandard() {
        assertEquals(PlaylistUrlKind.Standard, classifyPlaylistUrl("https://achordion.xyz/playlist/not-a-uuid"))
        assertEquals(PlaylistUrlKind.Standard, classifyPlaylistUrl("https://achordion.xyz/about"))
    }

    @Test fun achordionExtraSegmentAfterMbidIsStandard() {
        assertEquals(
            PlaylistUrlKind.Standard,
            classifyPlaylistUrl("https://achordion.xyz/playlist/c2accebd-ccd1-42c6-8ce7-ec0e8cf6cd13/extra"),
        )
    }

    @Test fun soundcloudShortLinkFlagged() {
        assertEquals(PlaylistUrlKind.SoundCloudShort, classifyPlaylistUrl("https://on.soundcloud.com/Drk2sCLhCHVNugYtAP"))
    }

    @Test fun spotifyPlaylistPageIsStandard() {
        assertEquals(PlaylistUrlKind.Standard, classifyPlaylistUrl("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test fun appleMusicPlaylistPageIsStandard() {
        assertEquals(
            PlaylistUrlKind.Standard,
            classifyPlaylistUrl("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb"),
        )
    }

    @Test fun soundcloudCanonicalSetIsStandard() {
        assertEquals(PlaylistUrlKind.Standard, classifyPlaylistUrl("https://soundcloud.com/jherskowitz/sets/frozen-in-time-2026"))
    }

    @Test fun hostedXspfDocumentIsStandard() {
        assertEquals(PlaylistUrlKind.Standard, classifyPlaylistUrl("https://example.com/my-playlist.xspf"))
    }

    @Test fun nonShortSoundcloudSubdomainIsStandard() {
        assertEquals(PlaylistUrlKind.Standard, classifyPlaylistUrl("https://m.soundcloud.com/jherskowitz/sets/x"))
    }

    @Test fun malformedUrlIsStandard() {
        assertEquals(PlaylistUrlKind.Standard, classifyPlaylistUrl("not a url"))
        assertEquals(PlaylistUrlKind.Standard, classifyPlaylistUrl(""))
        assertEquals(PlaylistUrlKind.Standard, classifyPlaylistUrl(null))
    }
}
