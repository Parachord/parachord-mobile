package com.parachord.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.Job

/**
 * Resolves a track row while it's on screen (#177, #181), as desktop's
 * `ResolutionScheduler` does for a page: [resolve] starts when the row is
 * composed (a lazy list composes the visible rows plus a few beyond them) and
 * its job is cancelled when the row leaves, or the screen does, so rows the
 * person scrolled past aren't searched. Opening a list used to resolve every
 * track in it at once, a burst of catalog searches per track that tripped
 * Spotify's account-wide rate limit.
 *
 * [key] identifies the track; [resolve] returns the job doing the work (null
 * when there's nothing to do). Already-resolved tracks cost nothing: the
 * resolver cache answers from memory.
 */
@Composable
fun ResolveWhileVisible(key: Any, resolve: () -> Job?) {
    DisposableEffect(key) {
        val job = resolve()
        onDispose { job?.cancel() }
    }
}
