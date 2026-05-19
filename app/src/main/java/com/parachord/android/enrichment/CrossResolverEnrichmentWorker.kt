package com.parachord.android.enrichment

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.parachord.shared.enrichment.CrossResolverEnrichmentService
import com.parachord.shared.platform.Log
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager wrapper around [CrossResolverEnrichmentService.runOnce].
 * Scheduled by [CrossResolverEnrichmentScheduler.enable] every 24h with
 * unmetered-network + battery-not-low constraints — the slow-trickle
 * doesn't need to fire often, and the streaming-service API hits
 * shouldn't burn the user's data or battery.
 *
 * Mirrors the shape of [com.parachord.android.playlist.HostedPlaylistWorker].
 */
class CrossResolverEnrichmentWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val service: CrossResolverEnrichmentService by inject()

    companion object {
        private const val TAG = "CrossResolverEnrichmentWorker"
        const val WORK_NAME = "cross_resolver_enrichment"
    }

    override suspend fun doWork(): Result = try {
        service.runOnce()
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "Slow-trickle run failed", e)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
