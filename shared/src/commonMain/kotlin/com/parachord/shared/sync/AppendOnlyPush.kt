package com.parachord.shared.sync

/**
 * What an add-only playlist (Apple Music's library playlists) needs appended to
 * hold a list. Apple refuses a replace of a library playlist, so a push can only
 * append; appending the whole list again duplicated every track on every push
 * (#802: a ListenBrainz-sourced playlist's Apple Music copy reached 1,182 entries
 * of 341 songs).
 */
object AppendOnlyPush {

    /**
     * The tracks of [intended], in order, that [present] doesn't already hold,
     * counting repeats: a track listed twice in [intended] and once in [present]
     * is appended once. Tracks on the remote that [intended] doesn't list stay
     * (an add-only playlist can't remove them).
     */
    fun missingIds(present: List<String>, intended: List<String>): List<String> {
        val left = present.groupingBy { it }.eachCount().toMutableMap()
        return intended.filter { id ->
            val n = left[id] ?: 0
            if (n > 0) {
                left[id] = n - 1
                false
            } else {
                true
            }
        }
    }
}
