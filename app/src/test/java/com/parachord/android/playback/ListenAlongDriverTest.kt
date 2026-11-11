package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.playback.FriendTrack
import com.parachord.shared.playback.LISTEN_ALONG_CONTEXT
import com.parachord.shared.playback.ListenAlongPoll
import com.parachord.shared.playback.PlaybackMessages
import com.parachord.shared.playback.listenAlongTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ListenAlongDriver] against a fake controller that calls back the way
 * [PlaybackController] does: every play reports PLAY_SOMETHING_ELSE first.
 */
class ListenAlongDriverTest {

    private val calls = mutableListOf<String>()
    private val messages = mutableListOf<String>()
    private var ended = 0
    private var currentId: String? = null

    private lateinit var driver: ListenAlongDriver

    init {
        driver = ListenAlongDriver(
            play = { track, ctx ->
                driver.onUserAction(UserPlaybackAction.PLAY_SOMETHING_ELSE)
                assertEquals(LISTEN_ALONG_CONTEXT, ctx.type)
                calls += "play ${track.title} for ${ctx.name}"
                currentId = track.id
            },
            restoreQueue = { tracks, ctx ->
                driver.onUserAction(UserPlaybackAction.PLAY_SOMETHING_ELSE)
                calls += "restore ${tracks.joinToString { it.title }} in ${ctx?.name}"
                currentId = tracks.first().id
            },
            clearQueue = { calls += "clear" },
            showMessage = { messages += it },
            onEnded = { ended++ },
        )
    }

    private val a = FriendTrack("Song A", "Artist", null, null)
    private val b = FriendTrack("Song B", "Artist", null, null)
    private val mine = listOf(TrackEntity(id = "1", title = "Mine 1", artist = "Me"), TrackEntity(id = "2", title = "Mine 2", artist = "Me"))
    private val myContext = PlaybackContext(type = "playlist", name = "My list")

    private fun startWithA() {
        driver.start("Pat", a, mine, myContext)
        calls.clear()
        messages.clear()
    }

    @Test
    fun `start plays the friend's track without ending the session`() {
        driver.start("Pat", a, mine, myContext)
        assertEquals(listOf("play Song A for Pat"), calls)
        assertEquals(listOf(PlaybackMessages.listeningAlongWith("Pat")), messages)
        assertTrue(driver.isActive)
        assertEquals(mine, driver.suspendedQueue.value)
        assertEquals(listenAlongTrack(a).id, currentId)
    }

    @Test
    fun `a new song while ours plays is held and plays when ours ends`() {
        startWithA()
        driver.onPoll(ListenAlongPoll.Listening(b), isPlaying = true)
        assertTrue(calls.isEmpty())
        assertTrue(driver.friendIsAhead.value)
        assertTrue(driver.onOwnTrackEnded(currentId))
        assertEquals(listOf("play Song B for Pat"), calls)
        assertFalse(driver.friendIsAhead.value)
    }

    @Test
    fun `the song ending with the friend still on it restores the queue once from its first track`() {
        startWithA()
        driver.onPoll(ListenAlongPoll.Listening(a), isPlaying = true)
        assertTrue(driver.onOwnTrackEnded(currentId))
        assertEquals(listOf("restore Mine 1, Mine 2 in My list"), calls)
        assertEquals(listOf(PlaybackMessages.hasntMovedOn("Pat")), messages)
        assertFalse(driver.isActive)
        assertEquals(1, ended)
        assertTrue(driver.suspendedQueue.value.isEmpty())
        // The restored queue's own track ending is the controller's business.
        assertFalse(driver.onOwnTrackEnded(currentId))
    }

    @Test
    fun `the song ending with no news waits — handled with nothing played`() {
        startWithA()
        assertTrue(driver.onOwnTrackEnded(currentId))
        assertTrue(calls.isEmpty())
        assertTrue(driver.isActive)
        driver.onPoll(ListenAlongPoll.Listening(b), isPlaying = false)
        assertEquals(listOf("play Song B for Pat"), calls)
    }

    @Test
    fun `a track that isn't ours ending isn't handled`() {
        startWithA()
        assertFalse(driver.onOwnTrackEnded("something-else"))
        assertFalse(driver.onTrackUnplayable("something-else"))
    }

    @Test
    fun `an unplayable track of ours is handled and plays a held track`() {
        startWithA()
        driver.onPoll(ListenAlongPoll.Listening(b), isPlaying = true)
        assertTrue(driver.onTrackUnplayable(currentId!!))
        assertEquals(listOf("play Song B for Pat"), calls)
    }

    @Test
    fun `a skip ends the session and restores the queue so the controller doesn't skip too`() {
        startWithA()
        assertTrue(driver.onUserAction(UserPlaybackAction.SKIP))
        assertEquals(listOf("restore Mine 1, Mine 2 in My list"), calls)
        assertEquals(listOf(PlaybackMessages.stoppedListeningAlongWith("Pat")), messages)
        assertFalse(driver.isActive)
    }

    @Test
    fun `a skip with nothing suspended clears and lets the controller act`() {
        driver.start("Pat", a, emptyList(), null)
        calls.clear()
        assertFalse(driver.onUserAction(UserPlaybackAction.SKIP))
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `playing something else ends the session and drops the suspended queue`() {
        startWithA()
        assertFalse(driver.onUserAction(UserPlaybackAction.PLAY_SOMETHING_ELSE))
        assertTrue(calls.isEmpty())
        assertEquals(listOf(PlaybackMessages.stoppedListeningAlongWith("Pat")), messages)
        assertFalse(driver.isActive)
        assertTrue(driver.suspendedQueue.value.isEmpty())
    }

    @Test
    fun `while paused a new song is held, and resume plays it instead`() {
        startWithA()
        driver.onUserAction(UserPlaybackAction.PAUSE)
        driver.onPoll(ListenAlongPoll.Listening(b), isPlaying = false)
        assertTrue(calls.isEmpty())
        assertTrue(driver.onUserAction(UserPlaybackAction.RESUME))
        assertEquals(listOf("play Song B for Pat"), calls)
        // Nothing held now: a later resume is the controller's.
        driver.onUserAction(UserPlaybackAction.PAUSE)
        assertFalse(driver.onUserAction(UserPlaybackAction.RESUME))
    }

    @Test
    fun `catch up plays the held song`() {
        startWithA()
        driver.onPoll(ListenAlongPoll.Listening(b), isPlaying = true)
        driver.catchUp()
        assertEquals(listOf("play Song B for Pat"), calls)
    }

    @Test
    fun `stop restores the queue with a message — ending silently drops it`() {
        startWithA()
        driver.stop()
        assertEquals(listOf("restore Mine 1, Mine 2 in My list"), calls)
        assertEquals(listOf(PlaybackMessages.stoppedListeningAlongWith("Pat")), messages)

        startWithA()
        driver.endSilently()
        assertTrue(calls.isEmpty())
        assertTrue(messages.isEmpty())
        assertFalse(driver.isActive)
        assertEquals(2, ended)
    }

    @Test
    fun `a spinoff ends it silently`() {
        startWithA()
        assertFalse(driver.onUserAction(UserPlaybackAction.SPINOFF_STARTED))
        assertTrue(calls.isEmpty())
        assertTrue(messages.isEmpty())
        assertFalse(driver.isActive)
    }

    @Test
    fun `switching friends keeps the first suspension`() {
        startWithA()
        // By now the controller's queue is Listen Along's.
        driver.start("Sam", b, emptyList(), PlaybackContext(type = LISTEN_ALONG_CONTEXT, name = "Pat"))
        assertEquals(listOf("play Song B for Sam"), calls)
        assertEquals(mine, driver.suspendedQueue.value)
        driver.stop()
        assertEquals("restore Mine 1, Mine 2 in My list", calls.last())
    }

    @Test
    fun `not listening mid-song finishes the song, then restores`() {
        startWithA()
        driver.onPoll(ListenAlongPoll.NotListening, isPlaying = true)
        assertTrue(calls.isEmpty())
        assertTrue(driver.onOwnTrackEnded(currentId))
        assertEquals(listOf(PlaybackMessages.stoppedListening("Pat")), messages)
        assertEquals(listOf("restore Mine 1, Mine 2 in My list"), calls)
    }
}
