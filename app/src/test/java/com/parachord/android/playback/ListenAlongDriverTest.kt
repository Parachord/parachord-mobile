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
            stopAndClear = { calls += "stopAndClear"; currentId = null },
            showMessage = { messages += it },
            onEnded = { ended++ },
        )
    }

    private val a = FriendTrack("Song A", "Artist", null, null)
    private val b = FriendTrack("Song B", "Artist", null, null)
    private val mine = listOf(TrackEntity(id = "1", title = "Mine 1", artist = "Me"), TrackEntity(id = "2", title = "Mine 2", artist = "Me"))
    private val myContext = PlaybackContext(type = "playlist", name = "My list")

    /** A poll while our track is (or isn't) playing, nothing starting. */
    private fun poll(result: ListenAlongPoll, playing: Boolean) =
        driver.onPoll(result, isPlaying = playing, isPlayStarting = false, currentTrackId = currentId)

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
        poll(ListenAlongPoll.Listening(b), true)
        assertTrue(calls.isEmpty())
        assertTrue(driver.friendIsAhead.value)
        assertTrue(driver.onOwnTrackEnded(currentId))
        assertEquals(listOf("play Song B for Pat"), calls)
        assertFalse(driver.friendIsAhead.value)
    }

    @Test
    fun `the song ending with the friend still on it restores the queue once from its first track`() {
        startWithA()
        poll(ListenAlongPoll.Listening(a), true)
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
        poll(ListenAlongPoll.Listening(b), false)
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
        poll(ListenAlongPoll.Listening(b), true)
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
        poll(ListenAlongPoll.Listening(b), false)
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
        poll(ListenAlongPoll.Listening(b), true)
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
        poll(ListenAlongPoll.NotListening, true)
        assertTrue(calls.isEmpty())
        assertTrue(driver.onOwnTrackEnded(currentId))
        assertEquals(listOf(PlaybackMessages.stoppedListening("Pat")), messages)
        assertEquals(listOf("restore Mine 1, Mine 2 in My list"), calls)
    }

    @Test
    fun `with no friend track yet the user's own queue playing doesn't hold the friend's song`() {
        // The friend's cached row said nothing (stale on-air): the user's song keeps playing.
        driver.start("Pat", null, mine, myContext)
        currentId = "1"
        calls.clear()
        driver.onPoll(ListenAlongPoll.Listening(b), isPlaying = true, isPlayStarting = false, currentTrackId = "1")
        assertEquals(listOf("play Song B for Pat"), calls)
        // The user's own song ending isn't ours.
        assertFalse(driver.onOwnTrackEnded("1"))
    }

    @Test
    fun `a play of ours still starting counts as playing`() {
        startWithA()
        driver.onPoll(ListenAlongPoll.Listening(b), isPlaying = false, isPlayStarting = true, currentTrackId = "something-before")
        assertTrue(calls.isEmpty())
        assertTrue(driver.friendIsAhead.value)
    }

    @Test
    fun `a song ending with nothing to go back to clears the track`() {
        driver.start("Pat", a, emptyList(), null)
        calls.clear()
        messages.clear()
        poll(ListenAlongPoll.Listening(a), true)
        assertTrue(driver.onOwnTrackEnded(currentId))
        assertEquals(listOf("clear", "stopAndClear"), calls)
        assertEquals(listOf(PlaybackMessages.hasntMovedOn("Pat")), messages)
    }

    @Test
    fun `waiting after the song then the friend leaving clears the track too`() {
        driver.start("Pat", a, emptyList(), null)
        calls.clear()
        assertTrue(driver.onOwnTrackEnded(currentId))   // no news: waits
        assertTrue(calls.isEmpty())
        poll(ListenAlongPoll.NotListening, false)
        assertEquals(listOf("clear", "stopAndClear"), calls)
    }

    @Test
    fun `the toggle with nothing to go back to leaves the playing track`() {
        driver.start("Pat", a, emptyList(), null)
        calls.clear()
        driver.stop()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `the session drives only its own track`() {
        startWithA()
        assertTrue(driver.drivesTrack(currentId!!))
        assertFalse(driver.drivesTrack("other"))
        driver.endSilently()
        assertFalse(driver.drivesTrack(listenAlongTrack(a).id))
    }
}
