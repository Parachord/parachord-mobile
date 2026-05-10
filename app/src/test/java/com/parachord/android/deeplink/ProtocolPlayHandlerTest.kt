package com.parachord.android.deeplink

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolInputResolver
import com.parachord.shared.deeplink.ProtocolPlayInput
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.deeplink.ProtocolTrack
import com.parachord.shared.deeplink.ResolvedProtocolPlay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ProtocolPlayHandler] — verifies the contracts in issue
 * #120's "Mandatory invariants" section using fakes / mocks for the
 * resolver, teardown, and playback surface.
 */
class ProtocolPlayHandlerTest {

    private val sampleTracks = listOf(
        ProtocolTrack("Witch Post", "Twin Fawn"),
        ProtocolTrack("Wintersleep", "Wishing Moon"),
        ProtocolTrack("Radiohead", "Karma Police"),
    )

    private fun buildHandler(
        resolved: ResolvedProtocolPlay? = ResolvedProtocolPlay("Test", sampleTracks),
        resolver: ProtocolInputResolver = mockk {
            // For PlayAlbum the priority walk would hit `resolveByArtistTitle`
            // first when only artist+title is set; for tests below we control
            // which slot is exercised by which input.
            coEvery { resolveByMbid(any()) } returns resolved
            coEvery { resolveBySpotify(any()) } returns resolved
            coEvery { resolveByAppleMusic(any()) } returns resolved
            coEvery { resolveByUrl(any()) } returns resolved
            coEvery { resolveByArtistTitle(any(), any(), any()) } returns resolved
        },
        teardown: ProtocolPlayTeardown = mockk(relaxed = true),
        playbackController: PlaybackController = mockk(relaxed = true),
        trackResolverCache: TrackResolverCache = mockk(relaxed = true),
    ): ProtocolPlayHandler = ProtocolPlayHandler(
        resolver = resolver,
        teardown = teardown,
        playbackController = playbackController,
        trackResolverCache = trackResolverCache,
    )

    // ── Happy-path: resolved input flows through to playback ──

    @Test
    fun playAlbum_callsTeardownThenPlay_inOrder() = runTest {
        val teardown = mockk<ProtocolPlayTeardown>(relaxed = true)
        val playback = mockk<PlaybackController>(relaxed = true)
        val handler = buildHandler(teardown = teardown, playbackController = playback)
        handler.handle(DeepLinkAction.PlayAlbum(ProtocolPlayInput(spotify = "spotify:album:abc")))
        coVerifyOrder {
            teardown.prepareForNewPlayback()
            playback.playTrack(any(), any())
        }
    }

    @Test
    fun playAlbum_emptyTracks_returnsFailedAndDoesNotTearDown() = runTest {
        val teardown = mockk<ProtocolPlayTeardown>(relaxed = true)
        val handler = buildHandler(
            resolved = ResolvedProtocolPlay("Empty", emptyList()),
            teardown = teardown,
        )
        val result = handler.handle(DeepLinkAction.PlayAlbum(ProtocolPlayInput(spotify = "spotify:album:abc")))
        assertTrue(result is ProtocolPlayResult.Failed)
        coVerify(exactly = 0) { teardown.prepareForNewPlayback() }
    }

    @Test
    fun playAlbum_resolverReturnsNull_returnsFailedAndDoesNotTearDown() = runTest {
        val resolver = mockk<ProtocolInputResolver> {
            coEvery { resolveByMbid(any()) } returns null
            coEvery { resolveBySpotify(any()) } returns null
            coEvery { resolveByAppleMusic(any()) } returns null
            coEvery { resolveByArtistTitle(any(), any(), any()) } returns null
        }
        val teardown = mockk<ProtocolPlayTeardown>(relaxed = true)
        val handler = buildHandler(resolver = resolver, teardown = teardown)
        val result = handler.handle(DeepLinkAction.PlayAlbum(ProtocolPlayInput(spotify = "spotify:album:abc")))
        assertTrue(result is ProtocolPlayResult.Failed)
        coVerify(exactly = 0) { teardown.prepareForNewPlayback() }
    }

    @Test
    fun playAlbum_returnsStartedWithDisplayNameAndCount() = runTest {
        val handler = buildHandler(resolved = ResolvedProtocolPlay("Glow On", sampleTracks))
        val result = handler.handle(DeepLinkAction.PlayAlbum(ProtocolPlayInput(spotify = "spotify:album:abc")))
        result as ProtocolPlayResult.Started
        assertEquals("Glow On", result.displayName)
        assertEquals(3, result.trackCount)
    }

    // ── Track tagging ──

    @Test
    fun playAlbum_assignsStableProtocolPlayIds() = runTest {
        val playback = mockk<PlaybackController>(relaxed = true)
        val firstSlot = slot<TrackEntity>()
        every { playback.playTrack(capture(firstSlot), any()) } returns Unit
        val queueSlot = slot<List<TrackEntity>>()
        every { playback.addToQueue(capture(queueSlot)) } returns Unit

        val handler = buildHandler(playbackController = playback)
        handler.handle(DeepLinkAction.PlayAlbum(ProtocolPlayInput(spotify = "spotify:album:abc")))

        // First track ID matches the convention.
        assertTrue(
            "first track id was '${firstSlot.captured.id}'",
            firstSlot.captured.id.startsWith("protocol-play-album-") &&
                firstSlot.captured.id.endsWith("-0"),
        )
        // Remaining tracks are sequentially indexed (-1, -2 …).
        assertEquals(2, queueSlot.captured.size)
        assertTrue(queueSlot.captured[0].id.endsWith("-1"))
        assertTrue(queueSlot.captured[1].id.endsWith("-2"))
        // All IDs share the same timestamp (proves we don't re-stamp per call).
        val sharedTs = firstSlot.captured.id.substringAfter("protocol-play-album-").substringBeforeLast("-")
        assertTrue(queueSlot.captured.all { it.id.contains("protocol-play-album-$sharedTs-") })
    }

    // ── Pre-resolve gate ──

    @Test
    fun playAlbum_preResolvesFirstTrackBeforeQueueing() = runTest {
        val cache = mockk<TrackResolverCache>(relaxed = true)
        val playback = mockk<PlaybackController>(relaxed = true)
        val handler = buildHandler(playbackController = playback, trackResolverCache = cache)
        handler.handle(DeepLinkAction.PlayAlbum(ProtocolPlayInput(spotify = "spotify:album:abc")))
        coVerifyOrder {
            // First-track pre-warm fires BEFORE playTrack.
            cache.resolveInBackground(match { it.size == 1 }, backfillDb = false)
            playback.playTrack(any(), any())
        }
    }

    // ── Queue handoff ──

    @Test
    fun playAlbum_singleTrack_doesNotCallAddToQueue() = runTest {
        val playback = mockk<PlaybackController>(relaxed = true)
        val handler = buildHandler(
            resolved = ResolvedProtocolPlay("Single", listOf(ProtocolTrack("A", "T"))),
            playbackController = playback,
        )
        handler.handle(DeepLinkAction.PlayAlbum(ProtocolPlayInput(spotify = "spotify:album:abc")))
        coVerify(exactly = 1) { playback.playTrack(any(), any()) }
        coVerify(exactly = 0) { playback.addToQueue(any()) }
    }

    @Test
    fun playAlbum_multiTrack_callsAddToQueueWithRest() = runTest {
        val playback = mockk<PlaybackController>(relaxed = true)
        val handler = buildHandler(playbackController = playback)
        handler.handle(DeepLinkAction.PlayAlbum(ProtocolPlayInput(spotify = "spotify:album:abc")))
        coVerify(exactly = 1) { playback.playTrack(any(), any()) }
        coVerify(exactly = 1) { playback.addToQueue(match { it.size == 2 }) }
    }

    // ── Per-command gating ──

    @Test
    fun playPlaylist_url_routesToResolveByUrl() = runTest {
        val resolver = mockk<ProtocolInputResolver> {
            coEvery { resolveByMbid(any()) } returns null
            coEvery { resolveBySpotify(any()) } returns null
            coEvery { resolveByAppleMusic(any()) } returns null
            coEvery { resolveByUrl(any()) } returns ResolvedProtocolPlay("From URL", sampleTracks)
            coEvery { resolveByArtistTitle(any(), any(), any()) } returns null
        }
        val handler = buildHandler(resolver = resolver)
        val result = handler.handle(
            DeepLinkAction.PlayPlaylist(ProtocolPlayInput(url = "https://example.com/x.xspf"))
        )
        result as ProtocolPlayResult.Started
        assertEquals("From URL", result.displayName)
        // Playlist gating: mbid / spotify / applemusic / artist+title NOT called.
        coVerify(exactly = 0) { resolver.resolveByMbid(any()) }
        coVerify(exactly = 0) { resolver.resolveBySpotify(any()) }
        coVerify(exactly = 0) { resolver.resolveByAppleMusic(any()) }
        coVerify(exactly = 0) { resolver.resolveByArtistTitle(any(), any(), any()) }
        coVerify(exactly = 1) { resolver.resolveByUrl("https://example.com/x.xspf") }
    }

    @Test
    fun playPlaylist_inlineTracks_bypassResolverEntirely() = runTest {
        val resolver = mockk<ProtocolInputResolver>()  // strict — any call fails
        val handler = buildHandler(resolver = resolver)
        val result = handler.handle(
            DeepLinkAction.PlayPlaylist(
                input = ProtocolPlayInput(tracks = sampleTracks, title = "Inline"),
                title = "Inline",
            )
        )
        result as ProtocolPlayResult.Started
        assertEquals("Inline", result.displayName)
        // No resolver method was called — strict mock would throw if any were.
    }

    @Test
    fun playAlbum_albumGatingExcludesUrlAndTracksSlots() = runTest {
        // Issue #120: play/album does not accept url= or tracks=. Provide only
        // those + a fallback artist+title — the resolver should walk past
        // url/tracks to artist+title.
        val resolver = mockk<ProtocolInputResolver> {
            coEvery { resolveByMbid(any()) } returns null
            coEvery { resolveBySpotify(any()) } returns null
            coEvery { resolveByAppleMusic(any()) } returns null
            coEvery { resolveByArtistTitle(any(), any(), any()) } returns
                ResolvedProtocolPlay("Fallback", sampleTracks)
            // resolveByUrl deliberately unstubbed — would throw if called.
        }
        val handler = buildHandler(resolver = resolver)
        handler.handle(
            DeepLinkAction.PlayAlbum(
                ProtocolPlayInput(
                    url = "https://example.com/x.xspf",  // ignored for album
                    tracks = sampleTracks,                // ignored for album
                    artist = "Witch Post",
                    title = "Witch Post",
                )
            )
        )
        coVerify(exactly = 0) { resolver.resolveByUrl(any()) }
        coVerify(exactly = 1) { resolver.resolveByArtistTitle("Witch Post", "Witch Post", any()) }
    }
}
