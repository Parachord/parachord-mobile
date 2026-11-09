package com.parachord.android.deeplink

import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.MbMedia
import com.parachord.shared.api.MbReleaseDetail
import com.parachord.shared.api.MbTrack
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.metadata.MetadataService
import io.ktor.client.HttpClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [AndroidProtocolInputResolver.resolveByMbid] — verifies the
 * release-group fallback path added for Phase 3 (Achordion emits release-group
 * MBIDs as the canonical "album" identifier).
 */
class AndroidProtocolInputResolverTest {

    private fun buildResolver(mbClient: MusicBrainzClient): AndroidProtocolInputResolver =
        AndroidProtocolInputResolver(
            musicBrainzClient = mbClient,
            spotifyClient = mockk(relaxed = true),
            appleMusicClient = mockk(relaxed = true),
            metadataService = mockk<MetadataService>(relaxed = true),
            httpClient = mockk<HttpClient>(relaxed = true),
        )

    @Test
    fun resolveByMbid_releaseGroupFallback_browsesReleasesAndMapsTracks() = runTest {
        val mbClient = mockk<MusicBrainzClient>()
        coEvery { mbClient.getRelease("RG_MBID", any()) } throws
            SerializationException("Not Found")
        coEvery {
            mbClient.albumReleaseOfGroup("RG_MBID", any())
        } returns MbReleaseDetail(
            id = "REL_MBID",
            title = "I Built You a Tower",
            media = listOf(
                MbMedia(
                    tracks = listOf(
                        MbTrack(id = "T1", title = "Song 1"),
                        MbTrack(id = "T2", title = "Song 2"),
                    ),
                ),
            ),
        )

        val resolver = buildResolver(mbClient)
        val resolved = resolver.resolveByMbid("RG_MBID")

        assertNotNull(resolved)
        assertEquals("I Built You a Tower", resolved!!.displayName)
        assertEquals(2, resolved.tracks.size)
        assertEquals("Song 1", resolved.tracks[0].title)
        assertEquals("T1", resolved.tracks[0].mbid)
        coVerifyOrder {
            mbClient.getRelease("RG_MBID", "recordings+artist-credits")
            mbClient.albumReleaseOfGroup("RG_MBID", any())
        }
    }

    @Test
    fun resolveByMbid_release_succeedsWithoutCallingReleaseGroupBrowse() = runTest {
        val mbClient = mockk<MusicBrainzClient>()
        coEvery { mbClient.getRelease("REL_MBID", any()) } returns MbReleaseDetail(
            id = "REL_MBID",
            title = "Release Title",
            media = listOf(
                MbMedia(
                    tracks = listOf(
                        MbTrack(id = "T1", title = "Song 1"),
                    ),
                ),
            ),
        )

        val resolver = buildResolver(mbClient)
        val resolved = resolver.resolveByMbid("REL_MBID")

        assertNotNull(resolved)
        assertEquals("Release Title", resolved!!.displayName)
        assertEquals(1, resolved.tracks.size)
        coVerify(exactly = 0) {
            mbClient.albumReleaseOfGroup(any(), any())
        }
    }

    @Test
    fun resolveByMbid_bothFail_returnsNull() = runTest {
        val mbClient = mockk<MusicBrainzClient>()
        coEvery { mbClient.getRelease(any(), any()) } throws
            SerializationException("Not Found")
        coEvery {
            mbClient.albumReleaseOfGroup(any(), any())
        } returns null

        val resolver = buildResolver(mbClient)
        assertNull(resolver.resolveByMbid("BAD_MBID"))
    }

    @Test
    fun resolveByMbid_emptyMediaOnRelease_fallsThroughToReleaseGroup() = runTest {
        // If /release/{mbid} returns a structurally-valid MbReleaseDetail but
        // its media[] is empty (e.g. the release exists but has no tracks
        // attached), fall through to the release-group browse rather than
        // returning null — the sibling edition usually has the full tracklist.
        val mbClient = mockk<MusicBrainzClient>()
        coEvery { mbClient.getRelease("MBID", any()) } returns MbReleaseDetail(
            id = "MBID", title = "Empty", media = emptyList(),
        )
        coEvery {
            mbClient.albumReleaseOfGroup("MBID", any())
        } returns MbReleaseDetail(
            id = "REL2", title = "Sibling Edition",
            media = listOf(MbMedia(tracks = listOf(MbTrack(id = "T1", title = "Song")))),
        )

        val resolver = buildResolver(mbClient)
        val resolved = resolver.resolveByMbid("MBID")

        assertNotNull(resolved)
        assertEquals("Sibling Edition", resolved!!.displayName)
        assertEquals(1, resolved.tracks.size)
    }
}
