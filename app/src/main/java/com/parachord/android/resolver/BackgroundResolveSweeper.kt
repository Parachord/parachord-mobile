package com.parachord.android.resolver

import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.platform.Log
import com.parachord.shared.resolver.BackgroundResolveSweep
import com.parachord.shared.resolver.SweepStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Runs the shared [BackgroundResolveSweep] while Parachord is on screen:
 * collection tracks, then playlist tracks, that have no source yet, resolved a
 * small batch at a time on the cache's background lane once nothing else is
 * resolving. Desktop's idle background pre-resolution. Results land in
 * [TrackResolverCache] (saved to disk) and, for collection and playlist rows,
 * their service ids are written back to the row, so the next look is cached.
 *
 * [MainActivity][com.parachord.android.ui.MainActivity] starts and stops it
 * with the screen ([setForeground]) and reports touches ([noteUserActivity]).
 * It doesn't run in the background: Android cuts an app's network there anyway.
 */
class BackgroundResolveSweeper(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val cache: TrackResolverCache,
    private val settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sweep = BackgroundResolveSweep(
        collection = { BackgroundResolveSweep.collectionItems(trackDao.getAll().first()) },
        playlists = {
            BackgroundResolveSweep.playlistItems(
                playlistDao.getAllSync().flatMap { playlistTrackDao.getByPlaylistIdSync(it.id) },
            )
        },
        startedAtMs = System.currentTimeMillis(),
    )
    private var job: Job? = null

    @Volatile
    private var lastActivityMs = 0L

    /** The person touched the screen. */
    fun noteUserActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    /** Run while Parachord is on screen, pause when it isn't. */
    @Synchronized
    fun setForeground(foreground: Boolean) {
        if (!foreground) {
            job?.cancel()
            job = null
            return
        }
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    private suspend fun run() {
        while (true) {
            val step = try {
                val active = settingsStore.getActiveResolvers()
                val disabled = settingsStore.getDisabledPlugins()
                val playable = cache.trackResolvers.value.filterValues { it.isNotEmpty() }.keys
                sweep.next(
                    nowMs = System.currentTimeMillis(),
                    lastActivityMs = lastActivityMs,
                    anyResolverOn = active.isEmpty() || active.any { it !in disabled },
                    playableKeys = playable,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Background sweep step failed: ${e.message}")
                SweepStep.Wait(BackgroundResolveSweep.NOTHING_TO_DO_WAIT_MS)
            }
            when (step) {
                is SweepStep.Wait -> delay(step.ms)
                is SweepStep.Resolve -> {
                    Log.d(TAG, "Background sweep: resolving ${step.items.size} tracks")
                    val tracks = step.items.mapNotNull { it.track }
                    val rows = step.items.mapNotNull { it.playlistTrack?.toTrackInfo() }
                    if (tracks.isNotEmpty()) cache.resolveInBackground(tracks, backfillDb = true, priority = false).join()
                    if (rows.isNotEmpty()) cache.resolvePlaylistTracksInBackground(rows, priority = false).join()
                    // Desktop's sweep asks again only once its scheduler is idle.
                    while (cache.inFlightKeys.value.isNotEmpty()) delay(IDLE_POLL_MS)
                }
            }
        }
    }

    private companion object {
        const val TAG = "BackgroundSweep"
        const val IDLE_POLL_MS = 1_000L
    }
}
