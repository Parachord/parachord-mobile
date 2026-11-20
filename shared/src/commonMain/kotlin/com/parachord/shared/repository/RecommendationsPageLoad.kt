package com.parachord.shared.repository

import com.parachord.shared.platform.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Loads the Recommendations page ([page]) and, alongside it in [background],
 * Home's history mixes ([mixes]), both with [force]. The page needs the mix
 * ids even when Home hasn't been visited (desktop `app.js` ~L29172), since its
 * Playlists row leaves them out. The mixes never hold back or fail the page;
 * their own load is throttled unless forced. Both apps' page loads call this.
 */
suspend fun loadRecommendationsPage(
    force: Boolean,
    background: CoroutineScope,
    mixes: suspend (force: Boolean) -> Unit,
    page: suspend (force: Boolean) -> Unit,
) {
    background.launch {
        try {
            mixes(force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("RecommendationsPageLoad", "History mixes load failed: ${e.message}")
        }
    }
    page(force)
}
