package com.parachord.android.enrichment

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.parachord.shared.platform.Log
import java.util.concurrent.TimeUnit

/**
 * Enqueues a periodic [CrossResolverEnrichmentWorker]. Idempotent on
 * subsequent app launches via `KEEP` policy — once scheduled, WorkManager
 * keeps the existing job.
 *
 * Constraints: requires unmetered network (Wi-Fi) + battery-not-low so
 * the slow trickle doesn't burn cellular data or drain the battery for
 * a low-urgency background task.
 */
class CrossResolverEnrichmentScheduler(private val context: Context) {
    companion object {
        private const val TAG = "CrossResolverEnrichmentScheduler"
        const val INTERVAL_HOURS: Long = 24
    }

    fun enable() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<CrossResolverEnrichmentWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CrossResolverEnrichmentWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "Slow-trickle scheduled (${INTERVAL_HOURS}h interval, unmetered, battery-not-low)")
    }

    fun disable() {
        WorkManager.getInstance(context).cancelUniqueWork(CrossResolverEnrichmentWorker.WORK_NAME)
    }
}
