package com.parachord.shared.playback

import com.parachord.shared.model.Track

/** How often a platform asks where the friend is, in milliseconds. */
const val LISTEN_ALONG_POLL_MS = 15_000L

/** This many failed polls in a row count as the friend having stopped listening. */
const val LISTEN_ALONG_FAILED_POLLS_LIMIT = 4

/** What the friend is playing, as the polls report it. */
data class FriendTrack(
    val name: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
) {
    /** Identifies the song across polls; case-insensitive on name and artist. */
    val key: String get() = name.lowercase() + "|" + artist.lowercase()
}

/** A metadata-only track for the friend's song; it resolves like any other when it plays. */
fun listenAlongTrack(track: FriendTrack): Track = Track(
    id = "listen-along:" + track.key,
    title = track.name,
    artist = track.artist,
    album = track.album,
    artworkUrl = track.artworkUrl,
    duration = 0L,
)

/** One poll's answer about the friend. */
sealed interface ListenAlongPoll {
    /** The friend is playing [track] now. */
    data class Listening(val track: FriendTrack) : ListenAlongPoll

    /** The friend isn't playing anything. */
    data object NotListening : ListenAlongPoll

    /** The poll couldn't answer (offline, rate limit, bad response): not the same as not listening. */
    data object Failed : ListenAlongPoll
}

/** What the platform does after a session event, in order. */
sealed interface ListenAlongEffect {
    /** Play [track] now ([listenAlongTrack]); the session has recorded it as the song we're on. */
    data class Play(val track: FriendTrack) : ListenAlongEffect

    /** Show [text] to the user. */
    data class Message(val text: String) : ListenAlongEffect

    /** The session is over; when [restoreQueue] the user's own queue resumes, else their new choice stands. */
    data class End(val restoreQueue: Boolean) : ListenAlongEffect
}

/**
 * Follows one friend, Listen Along's rules for both apps (desktop's). A plain single-threaded
 * state machine: the platform polls, plays and shows messages, and tells the session what
 * happened; each method returns what to do. After an [ListenAlongEffect.End] every method
 * returns an empty list. Starting a session for another friend means creating a new one
 * (the old one isn't told).
 */
class ListenAlongSession(val friendName: String) {
    private var ended = false
    private var held: FriendTrack? = null
    private var playedKey: String? = null
    private var lastPollKey: String? = null
    private var userPaused = false
    private var waitingAfterSongEnded = false
    private var endAfterSong = false
    private var failedPolls = 0

    /** True while the friend is on a track we haven't played yet. */
    val friendIsAhead: Boolean get() = held != null

    /** The key of the song [ListenAlongEffect.Play] last asked for. */
    val lastPlayedKey: String? get() = playedKey

    /** True once an [ListenAlongEffect.End] was returned. */
    val isEnded: Boolean get() = ended

    /** Begins following; plays [current] (what the friend is playing now) when known. */
    fun start(current: FriendTrack?): List<ListenAlongEffect> = guarded {
        val effects = mutableListOf<ListenAlongEffect>(
            ListenAlongEffect.Message(PlaybackMessages.listeningAlongWith(friendName)),
        )
        if (current != null) effects += play(current)
        effects
    }

    /** A poll answered; [isPlaying] is whether Listen Along's song is playing right now. */
    fun onPoll(result: ListenAlongPoll, isPlaying: Boolean): List<ListenAlongEffect> = guarded {
        when (result) {
            is ListenAlongPoll.Listening -> {
                failedPolls = 0
                endAfterSong = false
                val track = result.track
                lastPollKey = track.key
                if (track.key == playedKey) {
                    held = null
                    if (waitingAfterSongEnded) end(PlaybackMessages.hasntMovedOn(friendName))
                    else emptyList()
                } else if (isPlaying || userPaused) {
                    held = track
                    emptyList()
                } else {
                    listOf(play(track))
                }
            }
            ListenAlongPoll.NotListening -> {
                lastPollKey = null
                friendWentQuiet(isPlaying)
            }
            ListenAlongPoll.Failed -> {
                lastPollKey = null
                failedPolls++
                if (failedPolls >= LISTEN_ALONG_FAILED_POLLS_LIMIT) friendWentQuiet(isPlaying)
                else emptyList()
            }
        }
    }

    /** Our song finished playing. */
    fun onSongEnded(): List<ListenAlongEffect> = guarded {
        val next = held
        when {
            endAfterSong -> end(PlaybackMessages.stoppedListening(friendName))
            next != null -> listOf(play(next))
            lastPollKey != null && lastPollKey == playedKey ->
                end(PlaybackMessages.hasntMovedOn(friendName))
            else -> {
                // We don't know where the friend is: wait for the next poll, track kept on screen.
                waitingAfterSongEnded = true
                emptyList()
            }
        }
    }

    /** Our song couldn't be played (no source, refused). Plays a held track, else waits for the next poll. */
    fun onTrackUnplayable(): List<ListenAlongEffect> = guarded {
        held?.let { listOf(play(it)) } ?: emptyList()
    }

    /** The user paused Listen Along's song. */
    fun onUserPaused(): List<ListenAlongEffect> = guarded {
        userPaused = true
        emptyList()
    }

    /** The user pressed play again; a held track plays instead of resuming the paused one. */
    fun onUserResumed(): List<ListenAlongEffect> = guarded {
        userPaused = false
        held?.let { listOf(play(it)) } ?: emptyList()
    }

    /** The user asked to jump to what the friend is playing now. */
    fun onCatchUp(): List<ListenAlongEffect> = guarded {
        held?.let { listOf(play(it)) } ?: emptyList()
    }

    /** The user pressed Next or Previous (not the catch-up): they've taken control. */
    fun onUserSkipped(): List<ListenAlongEffect> = guarded { userEnded(restoreQueue = true) }

    /** The user started something else: their choice stands. */
    fun onUserPlayedSomethingElse(): List<ListenAlongEffect> = guarded { userEnded(restoreQueue = false) }

    /** The user turned Listen Along off. */
    fun onUserStopped(): List<ListenAlongEffect> = guarded { userEnded(restoreQueue = true) }

    private fun guarded(block: () -> List<ListenAlongEffect>): List<ListenAlongEffect> {
        if (ended) return emptyList()
        val effects = block()
        if (effects.any { it is ListenAlongEffect.End }) ended = true
        return effects
    }

    private fun play(track: FriendTrack): ListenAlongEffect.Play {
        playedKey = track.key
        lastPollKey = null
        held = null
        userPaused = false
        waitingAfterSongEnded = false
        return ListenAlongEffect.Play(track)
    }

    private fun friendWentQuiet(isPlaying: Boolean): List<ListenAlongEffect> {
        if (isPlaying) {
            endAfterSong = true
            held = null
            return emptyList()
        }
        return end(PlaybackMessages.stoppedListening(friendName))
    }

    private fun end(message: String): List<ListenAlongEffect> =
        listOf(ListenAlongEffect.Message(message), ListenAlongEffect.End(restoreQueue = true))

    private fun userEnded(restoreQueue: Boolean): List<ListenAlongEffect> = listOf(
        ListenAlongEffect.Message(PlaybackMessages.stoppedListeningAlongWith(friendName)),
        ListenAlongEffect.End(restoreQueue),
    )
}
