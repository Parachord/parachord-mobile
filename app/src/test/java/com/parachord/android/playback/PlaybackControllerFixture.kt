package com.parachord.android.playback

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.parachord.android.data.network.NetworkMonitor
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.shared.playback.QueueManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The real [PlaybackController] over relaxed mocks, for JVM tests. No
 * MediaController connects, so a local (ExoPlayer) route ends with nothing
 * playing; external routes go to whatever handler the test's [router] returns.
 * Nothing the controller launches runs until the test runs the main
 * dispatcher it installed.
 */
internal class PlaybackControllerFixture(
    val stateHolder: PlaybackStateHolder = PlaybackStateHolder(),
    val queueManager: QueueManager = QueueManager(),
    val router: PlaybackRouter = mockk(relaxed = true),
    val queuePersistence: QueuePersistence = mockk(relaxed = true),
    online: Boolean = true,
) {
    val trackResolverCache: TrackResolverCache = mockk(relaxed = true)
    val networkMonitor: NetworkMonitor = mockk(relaxed = true)

    init {
        // Nothing resolved: tracks route by what they carry.
        every { trackResolverCache.trackSources } returns MutableStateFlow(emptyMap())
        every { networkMonitor.isOnline } returns MutableStateFlow(online)
    }

    /** Hands out relaxed wake and Wi-Fi locks, which external playback takes. */
    val context: Context = mockk<Context>(relaxed = true).also { ctx ->
        every { ctx.applicationContext } returns ctx
        every { ctx.getSystemService(Context.POWER_SERVICE) } returns mockk<PowerManager>(relaxed = true)
        every { ctx.getSystemService(Context.WIFI_SERVICE) } returns mockk<WifiManager>(relaxed = true)
    }

    val controller = PlaybackController(
        context = context,
        stateHolder = stateHolder,
        router = router,
        queueManager = queueManager,
        queuePersistence = queuePersistence,
        scrobbleManager = mockk(relaxed = true),
        imageEnrichment = mockk(relaxed = true),
        mbidEnrichment = mockk(relaxed = true),
        lastFmClient = mockk(relaxed = true),
        resolverManager = mockk(relaxed = true),
        resolverScoring = mockk(relaxed = true),
        trackResolverCache = trackResolverCache,
        widgetUpdater = mockk(relaxed = true),
        settingsStore = mockk(relaxed = true),
        networkMonitor = networkMonitor,
    )
}
