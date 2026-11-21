package com.parachord.android.playback.drm

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select

/** A play a newer play replaced while it was still starting: never a failure. */
class PlaySupersededException : Exception("superseded by a newer play")

/**
 * Desktop's play token (widevine-player.js `playToken` / `supersededError`):
 * every play asked for takes a new generation the moment it's asked, before
 * it waits for the play before it, and a play still starting checks its own
 * at every wait. A plugin DRM start (the manifest, the licence, the start
 * wait, the family replay) runs its waits through [untilSuperseded], so a
 * newer play cuts it at once instead of queueing behind it.
 */
class PlaySupersession {
    private val generation = MutableStateFlow(0)

    /** A new play was asked for: it supersedes every play still starting. Answers its generation. */
    fun next(): Int {
        generation.update { it + 1 }
        return generation.value
    }

    val current: Int get() = generation.value

    fun isSuperseded(mine: Int): Boolean = generation.value != mine

    /** Throws [PlaySupersededException] when a play newer than [mine] was asked for. */
    fun check(mine: Int) {
        if (isSuperseded(mine)) throw PlaySupersededException()
    }

    /** Runs [block], cut short with [PlaySupersededException] the moment a play newer than [mine] is asked for. */
    suspend fun <T> untilSuperseded(mine: Int, block: suspend () -> T): T = coroutineScope {
        check(mine)
        val work = async { block() }
        val watch = async { generation.first { it != mine } }
        select<T> {
            work.onAwait { value ->
                watch.cancel()
                value
            }
            watch.onAwait {
                work.cancel()
                throw PlaySupersededException()
            }
        }
    }
}
