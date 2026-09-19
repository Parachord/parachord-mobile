package com.parachord.android.sync

import android.content.Context
import com.parachord.shared.platform.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.parachord.android.auth.OAuthManager
import com.parachord.android.auth.SpotifyReauthRequiredException
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class LibrarySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val syncEngine: SyncEngine by inject()
    private val settingsStore: SettingsStore by inject()
    private val oAuthManager: OAuthManager by inject()

    companion object {
        private const val TAG = "LibrarySyncWorker"
        const val WORK_NAME = "library_sync"
        private const val MIN_SYNC_GAP_MS = 10 * 60 * 1000L
    }

    override suspend fun doWork(): Result {
        val settings = settingsStore.getSyncSettings()
        // Spotify-only flag; see isAnySyncProviderEnabled (#377).
        if (!com.parachord.shared.sync.isAnySyncProviderEnabled(
                settings.enabled,
                settingsStore.getEnabledSyncProviders(),
            )
        ) {
            Log.d(TAG, "Sync not enabled, skipping")
            return Result.success()
        }

        val lastSync = settingsStore.lastSyncAtFlow.first()
        if (System.currentTimeMillis() - lastSync < MIN_SYNC_GAP_MS) {
            Log.d(TAG, "Skipping — last sync was recent")
            return Result.success()
        }

        return try {
            try {
                oAuthManager.refreshSpotifyToken()
            } catch (_: SpotifyReauthRequiredException) {
                // Refresh token revoked. Retrying won't help until the
                // user reconnects via the in-app banner. Skip Spotify
                // sync for this run — let the rest of syncEngine.syncAll
                // proceed (it'll no-op Spotify on missing access token).
                Log.w(TAG, "Spotify reauth required — skipping refresh, no retry")
            }

            val result = syncEngine.syncAll()
            if (result.success) {
                Log.d(TAG, "Background sync complete: $result")
                Result.success()
            } else {
                Log.w(TAG, "Sync reported failure: ${result.error}")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (e: SpotifyReauthRequiredException) {
            Log.w(TAG, "Spotify reauth required — sync aborted, no retry", e)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync worker failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
