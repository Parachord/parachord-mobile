package com.parachord.android.deeplink

import android.net.Uri
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.QueueManager
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.resolver.ResolvedSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `parachord://play` and `parachord://queue/add` must pick through
 * [ResolverScoring], not take whichever resolver answered first. On a track's
 * first play nothing is cached, so `PlaybackController.reselectBestSource` can't
 * correct the routing: a Pixel with Bandcamp first in the order went to Spotify,
 * which woke, blipped and failed before the fallback.
 *
 * The scoring here is the real one; only the settings behind it are stubbed.
 */
class DeepLinkViewModelSourceSelectionTest {

    private val testDispatcher = StandardTestDispatcher()

    private val deepLinkHandler = mockk<DeepLinkHandler>()
    private val playbackController = mockk<PlaybackController>(relaxed = true)
    private val queueManager = mockk<QueueManager>(relaxed = true)
    private val resolverManager = mockk<ResolverManager>()
    private val teardown = mockk<ProtocolPlayTeardown>(relaxed = true)
    private val settingsStore = mockk<SettingsStore>()
    private val uri = mockk<Uri>()

    private val play = DeepLinkAction.Play(artist = "Swapmeet", title = "Mount Zero")
    private val queueAdd = DeepLinkAction.QueueAdd(artist = "Swapmeet", title = "Mount Zero", album = null)

    private val bandcamp = source("bandcamp", confidence = 0.95)
    private val spotify = source("spotify", confidence = 0.95)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { settingsStore.getResolverOrder() } returns listOf("bandcamp", "spotify", "soundcloud")
        coEvery { settingsStore.getActiveResolvers() } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun source(resolver: String, confidence: Double) = ResolvedSource(
        url = "https://$resolver.example/track",
        sourceType = "stream",
        resolver = resolver,
        confidence = confidence,
    )

    private fun resolverReturns(vararg sources: ResolvedSource) {
        coEvery { resolverManager.resolve(any(), any(), any(), any(), any(), any()) } returns sources.toList()
    }

    private val lockedLinkNotifier = mockk<LockedLinkNotifier>(relaxed = true)

    private fun viewModel() = DeepLinkViewModel(
        deepLinkHandler = deepLinkHandler,
        externalLinkResolver = mockk(relaxed = true),
        playbackController = playbackController,
        playbackStateHolder = mockk(relaxed = true),
        queueManager = queueManager,
        resolverManager = resolverManager,
        resolverScoring = ResolverScoring(settingsStore),
        playlistImportManager = mockk(relaxed = true),
        chatService = mockk(relaxed = true),
        protocolPlayHandler = mockk(relaxed = true),
        protocolPlayTeardown = teardown,
        playRadioDispatcher = mockk(relaxed = true),
        listenAlongDispatcher = mockk(relaxed = true),
        friendsRepository = mockk(relaxed = true),
        pluginManager = mockk(relaxed = true),
        trackResolverCache = mockk(relaxed = true),
        lockedLinkNotifier = lockedLinkNotifier,
    )

    /** Runs [action] through the entry point the app uses, returning the toasts it emitted. */
    private fun kotlinx.coroutines.test.TestScope.run(action: DeepLinkAction): List<String> {
        val vm = viewModel()
        val events = mutableListOf<DeepLinkNavEvent>()
        // Unconfined: advanceUntilIdle stops once only background work is left, so
        // a queued collector would never receive a toast and its emit would hang.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navEvents.toList(events) }
        every { deepLinkHandler.parse(uri) } returns action
        vm.handleUri(uri)
        // queue/add asks first; play doesn't, and then there's nothing pending.
        vm.confirmPendingAction()
        advanceUntilIdle()
        return events.filterIsInstance<DeepLinkNavEvent.Toast>().map { it.message }
    }

    private fun playedTrack(): TrackEntity {
        val played = slot<TrackEntity>()
        verify(exactly = 1) { playbackController.playTrack(capture(played), any()) }
        return played.captured
    }

    private fun queuedTrack(): TrackEntity {
        val queued = slot<List<TrackEntity>>()
        verify(exactly = 1) { queueManager.addToQueue(capture(queued)) }
        return queued.captured.single()
    }

    @Test
    fun `play uses the higher-ranked source when it answered second`() = runTest(testDispatcher) {
        resolverReturns(spotify, bandcamp)

        run(play)

        assertEquals("bandcamp", playedTrack().resolver)
        assertEquals(bandcamp.url, playedTrack().sourceUrl)
    }

    @Test
    fun `play skips a higher-ranked source below the confidence floor`() = runTest(testDispatcher) {
        resolverReturns(source("bandcamp", confidence = 0.50), spotify)

        run(play)

        assertEquals("spotify", playedTrack().resolver)
    }

    @Test
    fun `play with only below-floor sources says not found and keeps the current playback`() = runTest(testDispatcher) {
        resolverReturns(source("spotify", confidence = 0.50))

        val toasts = run(play)

        assertEquals(listOf("Could not find: Swapmeet - Mount Zero"), toasts)
        verify(exactly = 0) { playbackController.playTrack(any(), any()) }
        coVerify(exactly = 0) { teardown.prepareForNewPlayback(any()) }
    }

    @Test
    fun `queue add uses the higher-ranked source when it answered second`() = runTest(testDispatcher) {
        resolverReturns(spotify, bandcamp)

        val toasts = run(queueAdd)

        assertEquals("bandcamp", queuedTrack().resolver)
        assertEquals(bandcamp.url, queuedTrack().sourceUrl)
        assertEquals(listOf("Added to queue: Mount Zero"), toasts)
    }

    @Test
    fun `queue add skips a higher-ranked source below the confidence floor`() = runTest(testDispatcher) {
        resolverReturns(source("bandcamp", confidence = 0.50), spotify)

        run(queueAdd)

        assertEquals("spotify", queuedTrack().resolver)
    }

    @Test
    fun `queue add with only below-floor sources says not found and adds nothing`() = runTest(testDispatcher) {
        resolverReturns(source("spotify", confidence = 0.50))

        val toasts = run(queueAdd)

        assertEquals(listOf("Could not find: Swapmeet - Mount Zero"), toasts)
        verify(exactly = 0) { queueManager.addToQueue(any()) }
    }
}
