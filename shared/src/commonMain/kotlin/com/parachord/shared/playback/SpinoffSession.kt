package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlin.coroutines.cancellation.CancellationException

/** The playback context type while the user follows a friend (Listen Along). */
const val LISTEN_ALONG_CONTEXT = "listen-along"

/** A spinoff pool is topped up when it holds fewer tracks than this. */
const val SPINOFF_REFILL_BELOW = 3

/** How many of the pool's next tracks are resolved ahead of time. */
const val SPINOFF_PRERESOLVE_AHEAD = 3

/** How many pool tracks a radio start checks for one that can play. */
const val RADIO_PLAYABLE_PROBE = 5

/** True when the spinoff pool is small enough that it should be refilled. */
fun spinoffNeedsRefill(poolSize: Int): Boolean = poolSize < SPINOFF_REFILL_BELOW

/** The pool tracks to resolve ahead of time: the first [SPINOFF_PRERESOLVE_AHEAD]. */
fun <T> spinoffTracksToPreresolve(pool: List<T>): List<T> = pool.take(SPINOFF_PRERESOLVE_AHEAD)

/** Shuffle is refused during a spinoff or Listen Along: the queue isn't the user's to reorder. */
fun shuffleAllowed(spinoffMode: Boolean, playbackContextType: String?): Boolean =
    !spinoffMode && playbackContextType != LISTEN_ALONG_CONTEXT

/** A metadata-only spinoff pool track; both apps use this id scheme. */
fun spinoffTrack(title: String, artist: String): Track = Track(
    id = "spinoff:${title.lowercase()}|${artist.lowercase()}",
    title = title,
    artist = artist,
    duration = 0L,
)

/**
 * The index of the first of the pool's first [RADIO_PLAYABLE_PROBE] tracks for which
 * [isPlayable] is true, or null. A probe that throws counts as not playable;
 * cancellation is rethrown.
 */
suspend fun <T> firstPlayableIndex(pool: List<T>, isPlayable: suspend (T) -> Boolean): Int? {
    for ((index, track) in pool.take(RADIO_PLAYABLE_PROBE).withIndex()) {
        val playable = try {
            isPlayable(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (playable) return index
    }
    return null
}

/** What leaving a spinoff does ([spinoffExit]). */
enum class SpinoffExit { END_SPINOFF, CANCEL_FETCH, NOTHING }

/**
 * Leaving a spinoff (the Spinoff button, Listen Along starting, a link taking
 * over): a running spinoff ends; one still fetching its similar tracks is
 * cancelled, so its tracks can't start it later and end what the user chose
 * since (#612: a Listen Along session started while the fetch ran was ended
 * when the tracks arrived). Both apps follow it in `exitSpinoff`.
 */
fun spinoffExit(spinoffMode: Boolean, fetching: Boolean): SpinoffExit = when {
    spinoffMode -> SpinoffExit.END_SPINOFF
    fetching -> SpinoffExit.CANCEL_FETCH
    else -> SpinoffExit.NOTHING
}
