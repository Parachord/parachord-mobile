package com.parachord.android.sync

import android.content.Context
import com.parachord.shared.platform.Log
import android.widget.Toast
import androidx.work.*
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class SyncScheduler constructor(
    private val context: Context,
    private val syncEngine: SyncEngine,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SyncScheduler"
        private const val IN_APP_INTERVAL_MS = 15 * 60 * 1000L
        private const val MIN_SYNC_GAP_MS = 10 * 60 * 1000L
    }

    // Default, NOT Main: syncAll must not run on the main thread. On Main, the
    // 10-min watchdog's TimeoutCancellationException is posted to the main looper
    // and can't be delivered while the sync body is busy, so a hang can never
    // self-recover (the WorkManager worker runs off-Main and never wedged — the
    // dispatcher was the differentiator). The Toast below marshals to Main itself.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null

    fun startInAppTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(IN_APP_INTERVAL_MS)
                runBackgroundSync()
            }
        }
        Log.d(TAG, "In-app sync timer started (${IN_APP_INTERVAL_MS / 60000}min interval)")
    }

    fun stopInAppTimer() {
        timerJob?.cancel()
        timerJob = null
        Log.d(TAG, "In-app sync timer stopped")
    }

    private suspend fun runBackgroundSync() {
        val settings = settingsStore.getSyncSettings()
        if (!settings.enabled) return

        val lastSync = settingsStore.lastSyncAtFlow.first()
        if (System.currentTimeMillis() - lastSync < MIN_SYNC_GAP_MS) {
            Log.d(TAG, "Skipping background sync — last sync was recent")
            return
        }

        try {
            val result = syncEngine.syncAll()
            if (result.success) {
                val added = result.tracks.added + result.albums.added +
                    result.artists.added + result.playlists.added
                val removed = result.tracks.removed + result.albums.removed +
                    result.artists.removed + result.playlists.removed
                if (added > 0 || removed > 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Synced: +$added added, -$removed removed",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed", e)
        }
    }

    fun enableWorkManagerSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            LibrarySyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "WorkManager periodic sync enabled (hourly)")
    }

    fun disableWorkManagerSync() {
        WorkManager.getInstance(context).cancelUniqueWork(LibrarySyncWorker.WORK_NAME)
        Log.d(TAG, "WorkManager periodic sync disabled")
    }
}
