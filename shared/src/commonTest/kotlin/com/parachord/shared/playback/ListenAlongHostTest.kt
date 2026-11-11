package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ListenAlongHost] against a fake platform that calls back the way both apps' playback
 * layers do: every play reports PLAY_SOMETHING_ELSE first (Android's `playTrack` /
 * `playQueue`), then the track starting (`onTrackStarting`).
 */
class ListenAlongHostTest {

    private val calls = mutableListOf<String>()
    private val messages = mutableListOf<String>()
    private var ended = 0
    private var released = 0
    private var currentId: String? = null
    private var playing = false
    private var starting = false
    private var queue = ListenAlongSuspendedQueue(emptyList(), null)

    private lateinit var host: ListenAlongHost

    private val platform = object : ListenAlongPlatform {
        override fun play(track: Track, context: PlaybackContext) {
            host.onUserAction(UserPlaybackAction.PLAY_SOMETHING_ELSE)
            assertEquals(LISTEN_ALONG_CONTEXT, context.type)
            calls += "play ${track.title} for ${context.name}"
            currentId = track.id
            playing = true
            queue = ListenAlongSuspendedQueue(listOf(track), context)
            host.onTrackStarting(track.id)
        }
        override fun currentQueue() = queue
        override fun exitSpinoff() { calls += "exitSpinoff" }
        override fun restoreQueue(queue: ListenAlongSuspendedQueue, startPlayback: Boolean) {
            if (startPlayback) host.onUserAction(UserPlaybackAction.PLAY_SOMETHING_ELSE)
            calls += (if (startPlayback) "restore " else "restore paused ") +
                "${queue.tracks.joinToString { it.title }} in ${queue.context?.name}"
            currentId = queue.tracks.first().id
            playing = startPlayback
            this@ListenAlongHostTest.queue = queue
        }
        override fun clearQueue() { calls += "clear" }
        override fun stopAndClear() { calls += "stopAndClear"; currentId = null; playing = false }
        override fun showMessage(text: String) { messages += text }
        override fun releaseHold() { released++ }
        override fun ended() { ended++ }
        override fun stateChanged() {}
        override fun isPlaying() = playing
        override fun isPlayStarting() = starting
        override fun currentTrackId() = currentId
    }

    init {
        host = ListenAlongHost(platform)
    }

    private val a = FriendTrack("Song A", "Artist", null, null)
    private val b = FriendTrack("Song B", "Artist", null, null)
    private val mine = listOf(Track(id = "1", title = "Mine 1", artist = "Me"), Track(id = "2", title = "Mine 2", artist = "Me"))
    private val myContext = PlaybackContext(type = "playlist", name = "My list")

    /** The user's queue is [tracks]; its first track (if any) is current and playing. */
    private fun userQueue(tracks: List<Track>, context: PlaybackContext?) {
        queue = ListenAlongSuspendedQueue(tracks, context)
        currentId = tracks.firstOrNull()?.id
        playing = tracks.isNotEmpty()
    }

    private fun startWithA(tracks: List<Track> = mine, context: PlaybackContext? = myContext) {
        userQueue(tracks, context)
        host.start("Pat", a)
        calls.clear()
        messages.clear()
    }

    /** Our song ends: the engine stops, the platform asks the host. */
    private fun songEnds(): Boolean {
        playing = false
        return host.onOwnTrackEnded()
    }

    @Test
    fun `start exits a spinoff then plays the friend's track`() {
        userQueue(mine, myContext)
        host.start("Pat", a)
        assertEquals(listOf("exitSpinoff", "play Song A for Pat"), calls)
        assertEquals(listOf(PlaybackMessages.listeningAlongWith("Pat")), messages)
        assertTrue(host.isActive)
        assertEquals(mine, host.suspendedQueue)
        assertEquals(listenAlongTrack(a).id, currentId)
    }

    @Test
    fun `a new song while ours plays is held and plays when ours ends`() {
        startWithA()
        host.onPoll(ListenAlongPoll.Listening(b))
        assertTrue(calls.isEmpty())
        assertTrue(host.friendIsAhead)
        assertTrue(songEnds())
        assertEquals(listOf("play Song B for Pat"), calls)
        assertFalse(host.friendIsAhead)
        assertFalse(host.isHolding)
    }

    @Test
    fun `the song ending with the friend still on it restores the queue once from its first track`() {
        startWithA()
        host.onPoll(ListenAlongPoll.Listening(a))
        assertTrue(songEnds())
        assertEquals(listOf("restore Mine 1, Mine 2 in My list"), calls)
        assertEquals(listOf(PlaybackMessages.hasntMovedOn("Pat")), messages)
        assertFalse(host.isActive)
        assertEquals(1, ended)
        assertTrue(host.suspendedQueue.isEmpty())
        // The restored queue's own track ending is the platform's business.
        assertFalse(songEnds())
    }

    @Test
    fun `the song ending with no news holds the track and a resume plays nothing`() {
        startWithA()
        assertTrue(songEnds())
        assertTrue(calls.isEmpty())
        assertTrue(host.isActive)
        assertTrue(host.isHolding)
        // Play during the wait: handled, nothing plays.
        assertTrue(host.onUserAction(UserPlaybackAction.RESUME))
        assertTrue(calls.isEmpty())
        // The next poll with a new song plays it.
        host.onPoll(ListenAlongPoll.Listening(b))
        assertEquals(listOf("play Song B for Pat"), calls)
        assertFalse(host.isHolding)
    }

    @Test
    fun `an unplayable track with nothing held is held and a resume plays nothing`() {
        startWithA()
        playing = false
        assertTrue(host.onTrackUnplayable(currentId!!))
        assertTrue(host.isHolding)
        assertTrue(host.onUserAction(UserPlaybackAction.RESUME))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `ending while holding lets the platform release the held track`() {
        startWithA()
        songEnds()
        host.stop()
        assertEquals(1, released)
        assertFalse(host.isHolding)
        // After the end a resume is the platform's again.
        assertFalse(host.onUserAction(UserPlaybackAction.RESUME))
    }

    @Test
    fun `our track starting again is no longer at its end`() {
        startWithA(tracks = emptyList(), context = null)
        songEnds()
        // Our track plays again (not through the session): no longer at its end or held.
        playing = true
        host.onTrackStarting(currentId!!)
        assertFalse(host.isHolding)
        // So the toggle with nothing to go back to leaves it playing.
        host.stop()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `a track that isn't ours ending isn't handled`() {
        startWithA()
        currentId = "something-else"
        assertFalse(host.onOwnTrackEnded())
        assertFalse(host.onTrackUnplayable("something-else"))
    }

    @Test
    fun `an unplayable track of ours is handled and plays a held track`() {
        startWithA()
        host.onPoll(ListenAlongPoll.Listening(b))
        assertTrue(host.onTrackUnplayable(currentId!!))
        assertEquals(listOf("play Song B for Pat"), calls)
        assertFalse(host.isHolding)
    }

    @Test
    fun `a skip ends the session and restores the queue so the platform doesn't skip too`() {
        startWithA()
        assertTrue(host.onUserAction(UserPlaybackAction.SKIP))
        assertEquals(listOf("restore Mine 1, Mine 2 in My list"), calls)
        assertEquals(listOf(PlaybackMessages.stoppedListeningAlongWith("Pat")), messages)
        assertFalse(host.isActive)
    }

    @Test
    fun `a skip with nothing suspended stops and clears and is handled`() {
        startWithA(tracks = emptyList(), context = null)
        assertTrue(host.onUserAction(UserPlaybackAction.SKIP))
        assertEquals(listOf("clear", "stopAndClear"), calls)
        assertEquals(null, currentId)
        assertFalse(host.isActive)
    }

    @Test
    fun `playing something else ends the session drops the suspended queue and never restores`() {
        startWithA()
        assertFalse(host.onUserAction(UserPlaybackAction.PLAY_SOMETHING_ELSE))
        assertTrue(calls.isEmpty())
        assertEquals(listOf(PlaybackMessages.stoppedListeningAlongWith("Pat")), messages)
        assertFalse(host.isActive)
        assertTrue(host.suspendedQueue.isEmpty())
        // Its track ending later is the platform's.
        assertFalse(songEnds())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a play link takes over with the message and a radio link silently`() {
        startWithA()
        host.takeOver(silently = false)
        assertEquals(listOf(PlaybackMessages.stoppedListeningAlongWith("Pat")), messages)
        assertTrue(calls.isEmpty())
        assertFalse(host.isActive)

        startWithA()
        host.takeOver(silently = true)
        assertTrue(messages.isEmpty())
        assertTrue(calls.isEmpty())
        assertFalse(host.isActive)
    }

    @Test
    fun `while paused a new song is held and resume plays it instead`() {
        startWithA()
        host.onUserAction(UserPlaybackAction.PAUSE)
        playing = false
        host.onPoll(ListenAlongPoll.Listening(b))
        assertTrue(calls.isEmpty())
        assertTrue(host.onUserAction(UserPlaybackAction.RESUME))
        assertEquals(listOf("play Song B for Pat"), calls)
        // Nothing held now: a later resume is the platform's.
        host.onUserAction(UserPlaybackAction.PAUSE)
        assertFalse(host.onUserAction(UserPlaybackAction.RESUME))
    }

    @Test
    fun `the friend going quiet while the user paused gives the queue back paused`() {
        startWithA()
        host.onUserAction(UserPlaybackAction.PAUSE)
        playing = false
        host.onPoll(ListenAlongPoll.NotListening)
        assertEquals(listOf("restore paused Mine 1, Mine 2 in My list"), calls)
        assertEquals(listOf(PlaybackMessages.stoppedListening("Pat")), messages)
        assertEquals("1", currentId)
        assertFalse(playing)
        assertFalse(host.isActive)
    }

    @Test
    fun `four failed polls while the user paused give the queue back paused`() {
        startWithA()
        host.onUserAction(UserPlaybackAction.PAUSE)
        playing = false
        repeat(4) { host.onPoll(ListenAlongPoll.Failed) }
        assertEquals(listOf("restore paused Mine 1, Mine 2 in My list"), calls)
    }

    @Test
    fun `the friend going quiet while nothing plays and not paused restores playing`() {
        startWithA()
        assertTrue(songEnds())
        host.onPoll(ListenAlongPoll.NotListening)
        assertEquals(listOf("restore Mine 1, Mine 2 in My list"), calls)
        assertTrue(playing)
    }

    @Test
    fun `catch up plays the held song`() {
        startWithA()
        host.onPoll(ListenAlongPoll.Listening(b))
        host.catchUp()
        assertEquals(listOf("play Song B for Pat"), calls)
    }

    @Test
    fun `stop restores the queue with a message and ending silently drops it`() {
        startWithA()
        host.stop()
        assertEquals(listOf("restore Mine 1, Mine 2 in My list"), calls)
        assertEquals(listOf(PlaybackMessages.stoppedListeningAlongWith("Pat")), messages)

        startWithA()
        host.endSilently()
        assertTrue(calls.isEmpty())
        assertTrue(messages.isEmpty())
        assertFalse(host.isActive)
        assertEquals(2, ended)
    }

    @Test
    fun `a spinoff ends it silently`() {
        startWithA()
        assertFalse(host.onUserAction(UserPlaybackAction.SPINOFF_STARTED))
        assertTrue(calls.isEmpty())
        assertTrue(messages.isEmpty())
        assertFalse(host.isActive)
    }

    @Test
    fun `switching friends keeps the suspension and the old friend's song as ours`() {
        startWithA()
        // By now the platform's queue is Listen Along's, and Song A plays.
        host.start("Sam", null)
        assertEquals(listOf("exitSpinoff"), calls)
        assertEquals(mine, host.suspendedQueue)
        // Song A is still ours: a new song for Sam is held while it plays, and its end is consumed.
        host.onPoll(ListenAlongPoll.Listening(b))
        assertEquals(listOf("exitSpinoff"), calls)
        assertTrue(songEnds())
        assertEquals("play Song B for Sam", calls.last())
        host.stop()
        assertEquals("restore Mine 1, Mine 2 in My list", calls.last())
    }

    @Test
    fun `not listening mid-song finishes the song then restores`() {
        startWithA()
        host.onPoll(ListenAlongPoll.NotListening)
        assertTrue(calls.isEmpty())
        assertTrue(songEnds())
        assertEquals(listOf(PlaybackMessages.stoppedListening("Pat")), messages)
        assertEquals(listOf("restore Mine 1, Mine 2 in My list"), calls)
    }

    @Test
    fun `with no friend track yet the user's own song playing doesn't hold the friend's`() {
        userQueue(mine, myContext)
        host.start("Pat", null)
        calls.clear()
        host.onPoll(ListenAlongPoll.Listening(b))
        assertEquals(listOf("play Song B for Pat"), calls)
        // The user's own song ending isn't ours.
        currentId = "1"
        assertFalse(host.onOwnTrackEnded())
    }

    @Test
    fun `a play of ours still starting counts as playing`() {
        startWithA()
        playing = false
        starting = true
        currentId = "something-before"
        host.onPoll(ListenAlongPoll.Listening(b))
        assertTrue(calls.isEmpty())
        assertTrue(host.friendIsAhead)
    }

    @Test
    fun `a song ending with nothing to go back to clears the track`() {
        startWithA(tracks = emptyList(), context = null)
        host.onPoll(ListenAlongPoll.Listening(a))
        assertTrue(songEnds())
        assertEquals(listOf("clear", "stopAndClear"), calls)
        assertEquals(listOf(PlaybackMessages.hasntMovedOn("Pat")), messages)
    }

    @Test
    fun `waiting after the song then the friend leaving clears the track too`() {
        startWithA(tracks = emptyList(), context = null)
        assertTrue(songEnds())
        assertTrue(calls.isEmpty())
        host.onPoll(ListenAlongPoll.NotListening)
        assertEquals(listOf("clear", "stopAndClear"), calls)
    }

    @Test
    fun `the toggle with nothing to go back to leaves the playing track`() {
        startWithA(tracks = emptyList(), context = null)
        host.stop()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `a queue that was already listen along is not suspended`() {
        userQueue(mine, PlaybackContext(type = LISTEN_ALONG_CONTEXT, name = "Old"))
        host.start("Pat", a)
        assertTrue(host.suspendedQueue.isEmpty())
    }

    @Test
    fun `the session drives only its own track`() {
        startWithA()
        assertTrue(host.drivesTrack(currentId!!))
        assertFalse(host.drivesTrack("other"))
        host.endSilently()
        assertFalse(host.drivesTrack(listenAlongTrack(a).id))
    }
}
