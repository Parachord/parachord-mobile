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
    private val announcementsRepository: com.parachord.shared.repository.AnnouncementsRepository by inject()
    private val trackTombstoneService: com.parachord.shared.sync.TrackTombstoneService by inject()
    private val settingsStore: com.parachord.android.data.store.SettingsStore by inject()

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

        // Sweep tombstones older than the 365-day TTL once per launch (#172).
        // Fire-and-forget; never fail startup on a prune error.
        appScope.launch {
            try {
                val pruned = trackTombstoneService.prune()
                if (pruned > 0) {
                    com.parachord.shared.platform.Log.d("ParachordApplication", "Pruned $pruned expired track tombstones")
                }
            } catch (_: Exception) {
                // Background sweep; ignore failures.
            }
        }

        // BYO Spotify Client ID migration: Parachord no longer ships a
        // built-in Spotify key, so a token minted under the old built-in
        // client is unusable. If no Client ID is set but a stale Spotify
        // token exists, clear it so the fleet stops refreshing on the
        // removed key and the user gets a clean reconnect with their own
        // Client ID (mirrors iOS's launch-time stale-token prune). This is
        // a hard BYO cutover — existing users must reconnect.
        appScope.launch {
            try {
                val clientId = settingsStore.getSpotifyClientId()?.trim().orEmpty()
                if (clientId.isBlank() &&
                    (settingsStore.getSpotifyAccessToken() != null ||
                        settingsStore.getSpotifyRefreshToken() != null)
                ) {
                    settingsStore.clearSpotifyTokens()
                    com.parachord.shared.platform.Log.d(
                        "ParachordApplication",
                        "Cleared stale Spotify token (no BYO Client ID set)",
                    )
                }
            } catch (_: Exception) {
                // Background migration; ignore failures.
            }
        }

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

        // Announcements feed (#127). Fire-and-forget cold-start refresh —
        // populates the home-screen banner StateFlow before HomeScreen
        // collects it. Failures inside listAnnouncements() return an empty
        // list and the banner just hides. The MainActivity.onResume path
        // (gated to 6h) handles subsequent foreground returns.
        appScope.launch {
            try {
                announcementsRepository.refreshNow()
            } catch (_: Exception) {
                // Swallow; banner stays hidden until next foreground refresh.
            }
        }
    }
}
