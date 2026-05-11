package com.parachord.android.share

import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.EntityLink
import com.parachord.shared.api.EntityType
import com.parachord.shared.api.SmartLinksClient
import com.parachord.shared.api.SubmitResult
import com.parachord.shared.api.SubmitTrackLinksRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareManagerTest {

    private fun buildManager(
        achordion: AchordionClient = mockk(relaxed = true),
        smartLinks: SmartLinksClient = mockk(relaxed = true),
    ): ShareManager = ShareManager(
        smartLinksClient = smartLinks,
        achordionClient = achordion,
        playlistDao = mockk(relaxed = true),
        playlistTrackDao = mockk(relaxed = true),
    )

    private fun sampleTrack(
        recordingMbid: String? = "550e8400-e29b-41d4-a716-446655440000",
        spotifyId: String? = "SP123",
        appleMusicId: String? = "AM456",
        soundcloudId: String? = "user/track",
    ) = TrackEntity(
        id = "t1",
        title = "Sugar For The Pill",
        artist = "Slowdive",
        album = "Slowdive",
        recordingMbid = recordingMbid,
        spotifyId = spotifyId,
        appleMusicId = appleMusicId,
        soundcloudId = soundcloudId,
    )

    @Test
    fun shareTrack_withMbid_callsBothEntityLinkAndSubmit() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(EntityType.Track, "550e8400-e29b-41d4-a716-446655440000", any()) } returns
            EntityLink(url = "https://achordion.xyz/recording/slowdive-sugar")
        coEvery { achordion.submitTrackLinks(any()) } returns SubmitResult.Ok

        val mgr = buildManager(achordion = achordion)
        val result = mgr.shareTrack(sampleTrack())

        assertEquals("https://achordion.xyz/recording/slowdive-sugar", result.url)
        assertTrue(result.isSmartLink)
        coVerify(exactly = 1) { achordion.fetchEntityLink(EntityType.Track, any(), any()) }
        coVerify(exactly = 1) { achordion.submitTrackLinks(any()) }
    }

    @Test
    fun shareTrack_withoutMbid_callsNeitherApi_returnsLookupFallback() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val mgr = buildManager(achordion = achordion)
        val result = mgr.shareTrack(sampleTrack(recordingMbid = null))

        assertEquals(
            "https://achordion.xyz/recording/lookup?artist=Slowdive&title=Sugar%20For%20The%20Pill",
            result.url,
        )
        assertFalse(result.isSmartLink)
        coVerify(exactly = 0) { achordion.fetchEntityLink(any(), any(), any()) }
        coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
    }

    @Test
    fun shareTrack_entityLinkReturnsNull_returnsLookupFallback_butStillSubmits() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(any(), any(), any()) } returns null
        coEvery { achordion.submitTrackLinks(any()) } returns SubmitResult.Ok

        val mgr = buildManager(achordion = achordion)
        val result = mgr.shareTrack(sampleTrack())

        assertTrue(result.url.startsWith("https://achordion.xyz/recording/lookup?"))
        assertFalse(result.isSmartLink)
        coVerify(exactly = 1) { achordion.submitTrackLinks(any()) }
    }

    @Test
    fun shareTrack_submitPayloadIncludesAllAvailableSourceUrls() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(any(), any(), any()) } returns null
        val payloadSlot = slot<SubmitTrackLinksRequest>()
        coEvery { achordion.submitTrackLinks(capture(payloadSlot)) } returns SubmitResult.Ok

        val mgr = buildManager(achordion = achordion)
        mgr.shareTrack(sampleTrack())

        val payload = payloadSlot.captured
        assertEquals("550e8400-e29b-41d4-a716-446655440000", payload.mbid)
        assertEquals("Sugar For The Pill", payload.trackName)
        assertEquals("Slowdive", payload.artistName)
        assertEquals("Slowdive", payload.albumName)
        val hosts = payload.links.map { it.host }.toSet()
        assertEquals(setOf("spotify.com", "music.apple.com", "soundcloud.com"), hosts)
    }

    @Test
    fun shareTrack_skipsSourceUrlsWithBlankIds() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(any(), any(), any()) } returns null
        val payloadSlot = slot<SubmitTrackLinksRequest>()
        coEvery { achordion.submitTrackLinks(capture(payloadSlot)) } returns SubmitResult.Ok

        val mgr = buildManager(achordion = achordion)
        mgr.shareTrack(sampleTrack(spotifyId = "SP", appleMusicId = "", soundcloudId = null))

        val hosts = payloadSlot.captured.links.map { it.host }
        assertEquals(listOf("spotify.com"), hosts)
    }

    @Test
    fun shareArtist_withMbid_callsEntityLinkOnly_noSubmit() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(EntityType.Artist, "artist-mbid-1", any()) } returns
            EntityLink(url = "https://achordion.xyz/artist/slowdive")
        val mgr = buildManager(achordion = achordion)
        val result = mgr.shareArtist("Slowdive", artistMbid = "artist-mbid-1")
        assertEquals("https://achordion.xyz/artist/slowdive", result.url)
        assertTrue(result.isSmartLink)
        coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
    }

    @Test
    fun shareArtist_noMbid_returnsLookupFallback() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val mgr = buildManager(achordion = achordion)
        val result = mgr.shareArtist("Slowdive", artistMbid = null)
        assertEquals("https://achordion.xyz/artist/lookup?name=Slowdive", result.url)
        assertFalse(result.isSmartLink)
    }

    @Test
    fun shareAlbum_withMbid_callsEntityLinkOnly_noSubmit() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(EntityType.ReleaseGroup, "rg-mbid-1", any()) } returns
            EntityLink(url = "https://achordion.xyz/release-group/death-cab-tower")

        val mgr = buildManager(achordion = achordion)
        val result = mgr.shareAlbum(
            title = "I Built You a Tower",
            artist = "Death Cab for Cutie",
            artworkUrl = null,
            releaseGroupMbid = "rg-mbid-1",
        )

        assertEquals("https://achordion.xyz/release-group/death-cab-tower", result.url)
        assertTrue(result.isSmartLink)
        coVerify(exactly = 1) { achordion.fetchEntityLink(EntityType.ReleaseGroup, "rg-mbid-1", any()) }
        coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
    }

    @Test
    fun shareAlbum_noMbid_returnsLookupFallback() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val mgr = buildManager(achordion = achordion)
        val result = mgr.shareAlbum(
            title = "I Built You a Tower",
            artist = "Death Cab for Cutie",
            artworkUrl = null,
            releaseGroupMbid = null,
        )
        assertEquals(
            "https://achordion.xyz/release-group/lookup?artist=Death%20Cab%20for%20Cutie&title=I%20Built%20You%20a%20Tower",
            result.url,
        )
        assertFalse(result.isSmartLink)
        coVerify(exactly = 0) { achordion.fetchEntityLink(any(), any(), any()) }
    }

    @Test
    fun shareAlbum_entityLinkFails_returnsLookupFallback() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(any(), any(), any()) } returns null
        val mgr = buildManager(achordion = achordion)
        val result = mgr.shareAlbum("Tower", "Death Cab", null, "rg-mbid-1")
        assertTrue(result.url.contains("/release-group/lookup?"))
        assertFalse(result.isSmartLink)
    }

    @Test
    fun shareTrack_noSourceIds_doesNotCallSubmit() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(any(), any(), any()) } returns null

        val mgr = buildManager(achordion = achordion)
        mgr.shareTrack(sampleTrack(spotifyId = null, appleMusicId = null, soundcloudId = null))

        // ShareManager filters source-list at build time and skips submit when empty.
        coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
    }

    @Test
    fun sharePlaylist_unchanged_stillUsesSmartLinksClient_notAchordion() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val smartLinks = mockk<SmartLinksClient>(relaxed = true)
        coEvery { smartLinks.create(any()) } returns com.parachord.shared.api.SmartLinkCreateResponse(
            id = "abc123",
            url = "https://go.parachord.com/abc123",
        )

        val playlistDao = mockk<PlaylistDao>()
        val playlistTrackDao = mockk<PlaylistTrackDao>()
        coEvery { playlistDao.getById("p1") } returns com.parachord.android.data.db.entity.PlaylistEntity(
            id = "p1",
            name = "My Playlist",
            ownerName = "me",
        )
        coEvery { playlistTrackDao.getByPlaylistIdSync("p1") } returns emptyList()

        val mgr = ShareManager(
            smartLinksClient = smartLinks,
            achordionClient = achordion,
            playlistDao = playlistDao,
            playlistTrackDao = playlistTrackDao,
        )

        val result = mgr.sharePlaylist("p1")
        assertEquals("https://go.parachord.com/abc123", result?.url)
        coVerify(exactly = 0) { achordion.fetchEntityLink(any(), any(), any()) }
        coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
        coVerify(exactly = 1) { smartLinks.create(any()) }
    }
}
