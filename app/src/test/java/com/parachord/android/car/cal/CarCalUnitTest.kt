package com.parachord.android.car.cal

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.car.app.model.CarIcon
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.WeeklyPlaylistsRepository
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.shared.car.CarBrowse
import com.parachord.shared.deeplink.ProviderPlaylistResolver
import com.parachord.shared.model.Track
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationRow
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.WeeklyInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CarCalUnitTest {

    // ── CarLauncherMode ──────────────────────────────────────────────

    @Test fun `CarLauncherMode does nothing on phones without templates host feature`() {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.hasSystemFeature(CarLauncherMode.TEMPLATES_HOST_FEATURE) } returns false
        val context = mockk<Context>()
        every { context.packageManager } returns pm
        every { context.packageName } returns "com.parachord.android"

        CarLauncherMode.apply(context)

        verify(exactly = 0) { pm.setComponentEnabledSetting(any(), any(), any()) }
    }

    @Test fun `CarLauncherMode flips components once and short-circuits when already applied`() {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.hasSystemFeature(CarLauncherMode.TEMPLATES_HOST_FEATURE) } returns true
        val alias = ComponentName("com.parachord.android", CarLauncherMode.PHONE_LAUNCHER_ALIAS)
        val carService = ComponentName("com.parachord.android", CarLauncherMode.CAR_APP_SERVICE)
        val carActivity = ComponentName("com.parachord.android", CarLauncherMode.CAR_APP_ACTIVITY)
        every { pm.getComponentEnabledSetting(alias) } returns PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        every { pm.getComponentEnabledSetting(carService) } returns PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        every { pm.getComponentEnabledSetting(carActivity) } returns PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

        val context = mockk<Context>()
        every { context.packageManager } returns pm
        every { context.packageName } returns "com.parachord.android"

        CarLauncherMode.apply(context)

        verify(exactly = 1) {
            pm.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        verify(exactly = 1) {
            pm.setComponentEnabledSetting(
                carService,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        verify(exactly = 1) {
            pm.setComponentEnabledSetting(
                carActivity,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        // Second run sees all three components already in desired state — zero writes.
        every { pm.getComponentEnabledSetting(alias) } returns PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        every { pm.getComponentEnabledSetting(carService) } returns PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        every { pm.getComponentEnabledSetting(carActivity) } returns PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        CarLauncherMode.apply(context)

        verify(exactly = 1) { pm.setComponentEnabledSetting(alias, any(), any()) }
        verify(exactly = 1) { pm.setComponentEnabledSetting(carService, any(), any()) }
        verify(exactly = 1) { pm.setComponentEnabledSetting(carActivity, any(), any()) }
    }

    // ── CalArt ───────────────────────────────────────────────────────

    @Test fun `CalArt enforces bounded LRU cache and failure backoff`() {
        val art = CalArt()
        try {
            assertNull(art.icon(null))
            assertNull(art.icon(""))
            assertNull(art.icon("   "))

            // Insert more than MAX_CACHED_ICONS; oldest entries must be evicted.
            val total = CalArt.MAX_CACHED_ICONS + 10
            for (i in 0 until total) {
                art.putForTest("https://example.com/$i.jpg", CarIcon.APP_ICON)
            }
            assertNull("Oldest icon should have been evicted", art.icon("https://example.com/0.jpg"))
            assertNotNull("Newest icon should stay cached", art.icon("https://example.com/${total - 1}.jpg"))

            // Failure backoff holds for RETRY_MS then expires.
            val t0 = 1_000_000L
            art.recordFailure("https://example.com/bad.jpg", t0)
            assertTrue(art.isInFailureBackoff("https://example.com/bad.jpg", t0 + 1_000L))
            assertFalse(art.isInFailureBackoff("https://example.com/bad.jpg", t0 + CalArt.RETRY_MS + 1L))
        } finally {
            art.shutdown()
        }
    }

    // ── CalPlaybackBridge ────────────────────────────────────────────

    private fun makeBridge(
        testScope: TestScope,
        controller: PlaybackController = mockk(relaxed = true),
        stateHolder: PlaybackStateHolder = PlaybackStateHolder(),
        carBrowse: CarBrowse = mockk(relaxed = true),
        libraryRepository: LibraryRepository = mockk(relaxed = true),
        metadataService: MetadataService = mockk(relaxed = true),
        weeklyPlaylistsRepository: WeeklyPlaylistsRepository = mockk(relaxed = true),
        providerPlaylistResolver: ProviderPlaylistResolver = mockk(relaxed = true),
    ) = CalPlaybackBridge(
        playbackController = controller,
        stateHolder = stateHolder,
        carBrowse = carBrowse,
        libraryRepository = libraryRepository,
        metadataService = metadataService,
        weeklyPlaylistsRepository = weeklyPlaylistsRepository,
        providerPlaylistResolver = providerPlaylistResolver,
        scope = testScope,
        postToMain = { it() },
    )

    @Test fun `toggleSpinoff ignores tap while pool is loading`() = runTest {
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val bridge = makeBridge(this, controller = controller, stateHolder = stateHolder)

        stateHolder.update { copy(spinoffLoading = true) }
        bridge.toggleSpinoff()
        verify(exactly = 0) { controller.toggleSpinoff() }

        stateHolder.update { copy(spinoffLoading = false) }
        bridge.toggleSpinoff()
        verify(exactly = 1) { controller.toggleSpinoff() }
    }

    @Test fun `playRecommendation catches failures and emits error messages without crashing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridgeScope = TestScope(dispatcher)
        val metadataService = mockk<MetadataService>(relaxed = true)
        coEvery { metadataService.getAlbumTracks(any(), any()) } throws RuntimeException("network down")
        coEvery { metadataService.getArtistTopTracks(any(), any()) } throws RuntimeException("network down")
        val weeklyRepo = mockk<WeeklyPlaylistsRepository>(relaxed = true)
        coEvery { weeklyRepo.loadPlaylistTracks(any()) } throws RuntimeException("503")
        val providerResolver = mockk<ProviderPlaylistResolver>(relaxed = true)
        coEvery { providerResolver.resolve(any()) } throws RuntimeException("timeout")

        val bridge = makeBridge(
            bridgeScope,
            metadataService = metadataService,
            weeklyPlaylistsRepository = weeklyRepo,
            providerPlaylistResolver = providerResolver,
        )
        val messages = mutableListOf<String>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bridge.messages.collect { messages.add(it) }
        }

        val row = RecommendationRow(key = "r", title = "For You", type = RecommendationType.ALBUM, items = emptyList())
        bridge.playRecommendation(
            row,
            RecommendationItem(id = "alb-1", type = RecommendationType.ALBUM, name = "Broken Album", artist = "Band"),
            emptyList(),
        )
        bridge.playRecommendation(
            row,
            RecommendationItem(id = "art-1", type = RecommendationType.ARTIST, name = "Broken Artist"),
            emptyList(),
        )
        bridge.playRecommendation(
            row,
            RecommendationItem(
                id = "pl-wk-1",
                type = RecommendationType.PLAYLIST,
                name = "Weekly Mix",
                weekly = WeeklyInfo(
                    id = "wk-1",
                    contextType = "weekly-exploration",
                    label = "Weekly Mix",
                    weekLabel = "This Week",
                ),
            ),
            emptyList(),
        )
        bridge.playRecommendation(
            row,
            RecommendationItem(
                id = "pl-svc-1",
                type = RecommendationType.PLAYLIST,
                name = "Service List",
                url = "https://example.com/playlist/1",
            ),
            emptyList(),
        )
        bridgeScope.advanceUntilIdle()
        advanceUntilIdle()
        collectJob.cancel()

        assertEquals(
            listOf(
                "Couldn’t load Broken Album",
                "Couldn’t load Broken Artist",
                "Couldn’t load Weekly Mix",
                "Couldn’t load Service List",
            ),
            messages,
        )
    }

    @Test fun `playCarItem dispatches CarBrowse playRequest and reports empty`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridgeScope = TestScope(dispatcher)
        val controller = mockk<PlaybackController>(relaxed = true)
        val carBrowse = mockk<CarBrowse>(relaxed = true)
        coEvery { carBrowse.playRequest("action:collection-radio") } returns null

        val bridge = makeBridge(bridgeScope, controller = controller, carBrowse = carBrowse)
        val messages = mutableListOf<String>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bridge.messages.collect { messages.add(it) }
        }

        bridge.playCarItem("action:collection-radio")
        bridgeScope.advanceUntilIdle()
        advanceUntilIdle()
        assertEquals(listOf("Nothing to play yet"), messages)
        verify(exactly = 0) { controller.playQueue(any(), any(), any(), any()) }

        val tracks = listOf(Track(id = "t1", title = "Song", artist = "Band", inCollection = true))
        coEvery { carBrowse.playRequest("action:collection-radio") } returns
            com.parachord.shared.car.CarPlayRequest(tracks, 0, true)
        bridge.playCarItem("action:collection-radio")
        bridgeScope.advanceUntilIdle()
        advanceUntilIdle()
        verify(exactly = 1) { controller.playQueue(tracks, 0, null, true) }

        collectJob.cancel()
    }
}
