package com.parachord.android.app

import android.app.Application
import com.parachord.android.data.metadata.ImageEnrichmentService
import com.parachord.android.di.androidModule
import com.parachord.android.enrichment.CrossResolverEnrichmentScheduler
import com.parachord.android.playlist.HostedPlaylistScheduler
import com.parachord.shared.di.sharedModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ParachordApplication : Application() {

    private val hostedPlaylistScheduler: HostedPlaylistScheduler by inject()
    private val imageEnrichmentService: ImageEnrichmentService by inject()
    private val crossResolverEnrichmentScheduler: CrossResolverEnrichmentScheduler by inject()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(CurrentActivityHolder)
        startKoin {
            androidContext(this@ParachordApplication)
            modules(sharedModule, androidModule)
        }
        // Hosted XSPF polling is orthogonal to Spotify sync — it should run
        // whenever the app has hosted playlists, regardless of sync settings.
        // The scheduler short-circuits when there are no hosted rows.
        hostedPlaylistScheduler.startInAppTimer()
        hostedPlaylistScheduler.enableWorkManagerPolling()

        // Slow-trickle cross-resolver enrichment (#150): backfills
        // streaming-service IDs for local-files-only tracks so the
        // Achordion contribution loop closes for the ~30% of users who
        // never connect a streaming service. 24h periodic, unmetered +
        // battery-not-low. Idempotent via WorkManager KEEP policy.
        crossResolverEnrichmentScheduler.enable()

        // On every app launch, regenerate playlist mosaics for any playlist
        // whose artwork isn't already a locally-generated `file://` mosaic.
        // This catches:
        //  - Synced AM/Spotify playlists where we initially stored the
        //    provider's stock playlist artwork (these get replaced with
        //    mosaics built from the actual track album art).
        //  - Hosted XSPF playlists that lost their mosaic from a prior bug
        //    or re-import.
        //  - Any new playlist that arrived without artwork.
        //
        // Bounded concurrency inside the function (MAX_PLAYLIST_REGEN_CONCURRENCY=4)
        // so a 100-playlist library doesn't slam the network at startup.
        // Skips playlists already on `file://` URLs — idempotent.
        appScope.launch {
            try {
                imageEnrichmentService.regenerateAllPlaylistMosaics()
            } catch (_: Exception) {
                // Background pass; don't take down the app on a failure.
            }
        }
    }
}
