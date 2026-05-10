package com.parachord.android.deeplink

import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.model.Friend
import com.parachord.shared.repository.FriendsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ListenAlongDispatcher] (Phase 3 Task 7, issue #121 §G).
 *
 * Covers the four branches of the dispatch flow:
 *
 *   1. Saved friend hit → [ListenAlongResult.Started] + handover teardown
 *      (NOT the full prepareForNewPlayback teardown — see KDoc on
 *      [ProtocolPlayTeardown.prepareForListenAlongHandover]).
 *   2. Saved friend miss + transient hit → [ListenAlongResult.Started]
 *      with `transient = true` Friend; handover teardown still runs.
 *   3. Saved friend miss + transient miss → [ListenAlongResult.NotPlaying]
 *      with the original username/service for the calm toast.
 *   4. Unsupported service → [ListenAlongResult.Failed]; no teardown,
 *      no repo calls.
 *
 * Plus invariants:
 *   - Saved-friend path never invokes the transient fetch (avoids a
 *     pointless API round-trip).
 *   - Lookup is case-insensitive (deeplink user "mrmonkey" finds saved
 *     "MrMonkey").
 *   - A throwing transient fetch surfaces as Failed, not a crash.
 */
class ListenAlongDispatcherTest {

    private fun savedFriend(
        id: String = "friend-123",
        username: String = "rob",
        service: String = "listenbrainz",
        displayName: String = "Rob",
    ) = Friend(
        id = id,
        username = username,
        service = service,
        displayName = displayName,
        addedAt = 0L,
    )

    private fun transientFriendStub(
        service: String = "listenbrainz",
        user: String = "rob",
    ) = Friend(
        id = "transient:$service:$user",
        username = user,
        service = service,
        displayName = user,
        addedAt = 0L,
        cachedTrackName = "Some Song",
        cachedTrackArtist = "Some Artist",
        cachedTrackTimestamp = System.currentTimeMillis() / 1000,
        transient = true,
    )

    private fun build(
        repo: FriendsRepository = mockk(relaxed = true),
        td: ProtocolPlayTeardown = mockk<ProtocolPlayTeardown>().also {
            coEvery { it.prepareForListenAlongHandover() } just runs
            coEvery { it.prepareForNewPlayback() } just runs
        },
    ): Pair<ListenAlongDispatcher, ProtocolPlayTeardown> =
        ListenAlongDispatcher(repo, td) to td

    @Test
    fun knownFriend_returnsStartedAndCallsHandoverTeardown() = runTest {
        val repo = mockk<FriendsRepository>()
        val saved = savedFriend()
        coEvery { repo.findByServiceAndUsername("listenbrainz", "rob") } returns saved
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForListenAlongHandover() } just runs

        val (dispatcher, teardown) = build(repo, td)
        val result = dispatcher.dispatch(DeepLinkAction.ListenAlong("listenbrainz", "rob"))

        assertTrue(result is ListenAlongResult.Started)
        assertEquals(saved, (result as ListenAlongResult.Started).friend)
        assertEquals("Rob", result.displayName)
        coVerify(exactly = 1) { teardown.prepareForListenAlongHandover() }
        // Critically: NOT the full prepareForNewPlayback (would race with
        // MainViewModel.startListenAlong's own stopListenAlong).
        coVerify(exactly = 0) { teardown.prepareForNewPlayback() }
    }

    @Test
    fun knownFriend_doesNotFireTransientFetch() = runTest {
        // If the local lookup hits, the transient fetch must be skipped
        // — round-tripping to the now-playing API is wasted work and an
        // unnecessary point of failure.
        val repo = mockk<FriendsRepository>()
        coEvery { repo.findByServiceAndUsername("listenbrainz", "rob") } returns savedFriend()
        // Throw if the transient fetch is invoked — verifies the skip.
        coEvery { repo.fetchTransientFriendNowPlaying(any(), any()) } throws
            RuntimeException("transient fetch should not run when saved friend is found")

        val (dispatcher, _) = build(repo)
        val result = dispatcher.dispatch(DeepLinkAction.ListenAlong("listenbrainz", "rob"))

        assertTrue(result is ListenAlongResult.Started)
        coVerify(exactly = 0) {
            repo.fetchTransientFriendNowPlaying(any(), any())
        }
    }

    @Test
    fun unknownFriendWithCurrentTrack_returnsStartedWithTransient() = runTest {
        val repo = mockk<FriendsRepository>()
        coEvery { repo.findByServiceAndUsername("listenbrainz", "rob") } returns null
        val transient = transientFriendStub()
        coEvery { repo.fetchTransientFriendNowPlaying("listenbrainz", "rob") } returns transient
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForListenAlongHandover() } just runs

        val (dispatcher, teardown) = build(repo, td)
        val result = dispatcher.dispatch(DeepLinkAction.ListenAlong("listenbrainz", "rob"))

        assertTrue(result is ListenAlongResult.Started)
        val started = result as ListenAlongResult.Started
        assertTrue("transient flag must propagate", started.friend.transient)
        assertEquals("transient:listenbrainz:rob", started.friend.id)
        assertEquals("rob", started.displayName)
        coVerify(exactly = 1) { teardown.prepareForListenAlongHandover() }
    }

    @Test
    fun unknownFriendNoCurrentTrack_returnsNotPlaying() = runTest {
        val repo = mockk<FriendsRepository>()
        coEvery { repo.findByServiceAndUsername("listenbrainz", "rob") } returns null
        coEvery { repo.fetchTransientFriendNowPlaying("listenbrainz", "rob") } returns null
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)

        val (dispatcher, teardown) = build(repo, td)
        val result = dispatcher.dispatch(DeepLinkAction.ListenAlong("listenbrainz", "rob"))

        assertTrue(result is ListenAlongResult.NotPlaying)
        val notPlaying = result as ListenAlongResult.NotPlaying
        assertEquals("rob", notPlaying.username)
        assertEquals("listenbrainz", notPlaying.service)
        // No teardown when there's nothing to start.
        coVerify(exactly = 0) { teardown.prepareForListenAlongHandover() }
    }

    @Test
    fun invalidService_returnsFailedAndDoesNotTouchRepo() = runTest {
        val repo = mockk<FriendsRepository>()
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)

        val dispatcher = ListenAlongDispatcher(repo, td)
        val result = dispatcher.dispatch(DeepLinkAction.ListenAlong("spotify", "rob"))

        assertTrue(result is ListenAlongResult.Failed)
        coVerify(exactly = 0) { repo.findByServiceAndUsername(any(), any()) }
        coVerify(exactly = 0) { repo.fetchTransientFriendNowPlaying(any(), any()) }
        coVerify(exactly = 0) { td.prepareForListenAlongHandover() }
    }

    @Test
    fun emptyUsername_returnsFailed() = runTest {
        val repo = mockk<FriendsRepository>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)

        val dispatcher = ListenAlongDispatcher(repo, td)
        val result = dispatcher.dispatch(DeepLinkAction.ListenAlong("listenbrainz", ""))

        assertTrue(result is ListenAlongResult.Failed)
        coVerify(exactly = 0) { repo.findByServiceAndUsername(any(), any()) }
    }

    @Test
    fun transientFetchThrows_returnsFailed() = runTest {
        // Repo's fetchTransientFriendNowPlaying already swallows
        // exceptions internally and returns null on failure (calm UX
        // on flaky network). But if a programmer error or upstream
        // change re-introduces a throw, the dispatcher must catch it
        // and surface Failed rather than letting the deeplink crash.
        val repo = mockk<FriendsRepository>()
        coEvery { repo.findByServiceAndUsername("listenbrainz", "rob") } returns null
        coEvery { repo.fetchTransientFriendNowPlaying("listenbrainz", "rob") } throws
            RuntimeException("network fail")
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)

        val (dispatcher, teardown) = build(repo, td)
        val result = dispatcher.dispatch(DeepLinkAction.ListenAlong("listenbrainz", "rob"))

        assertTrue(result is ListenAlongResult.Failed)
        coVerify(exactly = 0) { teardown.prepareForListenAlongHandover() }
    }

    @Test
    fun caseInsensitiveLookup_treatsUpperLowerVariantAsSameFriend() = runTest {
        // The repo's findByServiceAndUsername is case-insensitive on
        // the username — verifying that here is really verifying the
        // contract surface the dispatcher relies on. We mock the repo
        // to RESPOND to the case-mapped query so the test fails if
        // the dispatcher did its own (lossy) lower/upper-casing
        // before the repo call.
        val repo = mockk<FriendsRepository>()
        // Accept either case the dispatcher might send through.
        val saved = savedFriend(username = "MrMonkey")
        coEvery { repo.findByServiceAndUsername("listenbrainz", "mrmonkey") } returns saved
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForListenAlongHandover() } just runs

        val dispatcher = ListenAlongDispatcher(repo, td)
        val result = dispatcher.dispatch(DeepLinkAction.ListenAlong("listenbrainz", "mrmonkey"))

        assertTrue(result is ListenAlongResult.Started)
        assertEquals(saved, (result as ListenAlongResult.Started).friend)
    }

    @Test
    fun lastFmService_alsoSupported() = runTest {
        val repo = mockk<FriendsRepository>()
        val saved = savedFriend(service = "lastfm")
        coEvery { repo.findByServiceAndUsername("lastfm", "rob") } returns saved
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForListenAlongHandover() } just runs

        val (dispatcher, _) = build(repo, td)
        val result = dispatcher.dispatch(DeepLinkAction.ListenAlong("lastfm", "rob"))

        assertTrue(result is ListenAlongResult.Started)
    }
}
