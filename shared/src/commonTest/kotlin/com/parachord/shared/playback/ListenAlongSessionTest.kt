package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListenAlongSessionTest {
    private val a = FriendTrack("Song A", "Artist", "Album", "http://art/a")
    private val b = FriendTrack("Song B", "Artist", null, null)
    private val listeningAlong = "Listening along with Ana"
    private val stopped = "Ana stopped listening"
    private val notMoved = "Ana hasn't moved on - resuming your queue"
    private val youStopped = "Stopped listening along with Ana"

    private fun session() = ListenAlongSession("Ana")
    private fun started(): ListenAlongSession = session().also { it.start(a) }

    @Test
    fun `starting plays the friend's track and says so`() {
        assertEquals(
            listOf(ListenAlongEffect.Message(listeningAlong), ListenAlongEffect.Play(a)),
            session().start(a),
        )
    }

    @Test
    fun `starting with nothing playing waits for the first poll`() {
        val s = session()
        assertEquals(listOf(ListenAlongEffect.Message(listeningAlong)), s.start(null))
        assertEquals(listOf(ListenAlongEffect.Play(a)), s.onPoll(ListenAlongPoll.Listening(a), isPlaying = false))
    }

    @Test
    fun `the friend's next track is held until ours ends`() {
        val s = started()
        assertEquals(emptyList(), s.onPoll(ListenAlongPoll.Listening(b), true))
        assertTrue(s.friendIsAhead)
        assertEquals(listOf(ListenAlongEffect.Play(b)), s.onSongEnded())
        assertFalse(s.friendIsAhead)
        assertEquals(b.key, s.lastPlayedKey)
    }

    @Test
    fun `the same track again changes nothing`() {
        val s = started()
        assertEquals(emptyList(), s.onPoll(ListenAlongPoll.Listening(a), true))
        assertFalse(s.friendIsAhead)
    }

    @Test
    fun `a held track plays when the user resumes`() {
        val s = started()
        s.onUserPaused()
        assertEquals(emptyList(), s.onPoll(ListenAlongPoll.Listening(b), false))
        assertEquals(listOf(ListenAlongEffect.Play(b)), s.onUserResumed())
    }

    @Test
    fun `resuming with nothing held resumes the paused track`() {
        val s = started()
        s.onUserPaused()
        assertEquals(emptyList(), s.onUserResumed())
    }

    @Test
    fun `the friend not moving on ends the session and resumes the queue`() {
        val s = started()
        s.onPoll(ListenAlongPoll.Listening(a), true)
        assertEquals(
            listOf(ListenAlongEffect.Message(notMoved), ListenAlongEffect.End(true)),
            s.onSongEnded(),
        )
        assertTrue(s.isEnded)
    }

    @Test
    fun `our song ending after a failed poll waits for the next poll`() {
        val s = started()
        s.onPoll(ListenAlongPoll.Failed, true)
        assertEquals(emptyList(), s.onSongEnded())
        assertEquals(listOf(ListenAlongEffect.Play(b)), s.onPoll(ListenAlongPoll.Listening(b), false))
    }

    @Test
    fun `waiting ends when the friend is still on the same track`() {
        val s = started()
        s.onPoll(ListenAlongPoll.Failed, true)
        assertEquals(emptyList(), s.onSongEnded())
        assertEquals(
            listOf(ListenAlongEffect.Message(notMoved), ListenAlongEffect.End(true)),
            s.onPoll(ListenAlongPoll.Listening(a), false),
        )
    }

    @Test
    fun `our song ending before any poll answered waits`() {
        val s = started()
        assertEquals(emptyList(), s.onSongEnded())
        assertFalse(s.isEnded)
    }

    @Test
    fun `waiting after our song ended ends when the friend goes offline`() {
        val s = started()
        s.onSongEnded()
        assertEquals(
            listOf(ListenAlongEffect.Message(stopped), ListenAlongEffect.End(true)),
            s.onPoll(ListenAlongPoll.NotListening, false),
        )
    }

    @Test
    fun `a friend who goes offline mid-song ends it after the song`() {
        val s = started()
        assertEquals(emptyList(), s.onPoll(ListenAlongPoll.NotListening, true))
        assertEquals(
            listOf(ListenAlongEffect.Message(stopped), ListenAlongEffect.End(true)),
            s.onSongEnded(),
        )
    }

    @Test
    fun `a friend who goes offline while nothing plays ends it now`() {
        val s = started()
        assertEquals(
            listOf(ListenAlongEffect.Message(stopped), ListenAlongEffect.End(true)),
            s.onPoll(ListenAlongPoll.NotListening, false),
        )
    }

    @Test
    fun `a friend who goes offline while nothing plays restores and starts the queue`() {
        val s = started()
        val end = s.onPoll(ListenAlongPoll.NotListening, false).filterIsInstance<ListenAlongEffect.End>().single()
        assertTrue(end.restoreQueue)
        assertTrue(end.startPlayback)
    }

    @Test
    fun `a friend who goes offline while the user paused gives the queue back paused`() {
        val s = started()
        s.onUserPaused()
        assertEquals(
            listOf(ListenAlongEffect.Message(stopped), ListenAlongEffect.End(restoreQueue = true, startPlayback = false)),
            s.onPoll(ListenAlongPoll.NotListening, false),
        )
    }

    @Test
    fun `the fourth failed poll while the user paused gives the queue back paused`() {
        val s = started()
        s.onUserPaused()
        repeat(3) { assertEquals(emptyList(), s.onPoll(ListenAlongPoll.Failed, false)) }
        assertEquals(
            listOf(ListenAlongEffect.Message(stopped), ListenAlongEffect.End(restoreQueue = true, startPlayback = false)),
            s.onPoll(ListenAlongPoll.Failed, false),
        )
    }

    @Test
    fun `a user who paused and resumed gets the queue back playing`() {
        val s = started()
        s.onUserPaused()
        s.onUserResumed()
        assertEquals(
            listOf(ListenAlongEffect.Message(stopped), ListenAlongEffect.End(restoreQueue = true, startPlayback = true)),
            s.onPoll(ListenAlongPoll.NotListening, false),
        )
    }

    @Test
    fun `a friend who comes back before the song ends is followed again`() {
        val s = started()
        s.onPoll(ListenAlongPoll.NotListening, true)
        s.onPoll(ListenAlongPoll.Listening(b), true)
        assertEquals(listOf(ListenAlongEffect.Play(b)), s.onSongEnded())
    }

    @Test
    fun `one failed poll changes nothing`() {
        val s = started()
        s.onPoll(ListenAlongPoll.Listening(b), true)
        repeat(3) { assertEquals(emptyList(), s.onPoll(ListenAlongPoll.Failed, true)) }
        assertEquals(listOf(ListenAlongEffect.Play(b)), s.onSongEnded())
    }

    @Test
    fun `four failed polls in a row count as offline`() {
        val s = started()
        repeat(3) { assertEquals(emptyList(), s.onPoll(ListenAlongPoll.Failed, false)) }
        assertEquals(
            listOf(ListenAlongEffect.Message(stopped), ListenAlongEffect.End(true)),
            s.onPoll(ListenAlongPoll.Failed, false),
        )
    }

    @Test
    fun `a good poll in between resets the failed count`() {
        val s = started()
        repeat(3) { s.onPoll(ListenAlongPoll.Failed, true) }
        s.onPoll(ListenAlongPoll.Listening(a), true)
        repeat(3) { assertEquals(emptyList(), s.onPoll(ListenAlongPoll.Failed, false)) }
        assertFalse(s.isEnded)
    }

    @Test
    fun `failed polls keep counting while waiting after our song ended`() {
        val s = started()
        s.onPoll(ListenAlongPoll.Failed, true)
        s.onSongEnded()
        repeat(2) { assertEquals(emptyList(), s.onPoll(ListenAlongPoll.Failed, false)) }
        assertEquals(
            listOf(ListenAlongEffect.Message(stopped), ListenAlongEffect.End(true)),
            s.onPoll(ListenAlongPoll.Failed, false),
        )
    }

    @Test
    fun `an unplayable friend track waits for the next one`() {
        val s = started()
        assertEquals(emptyList(), s.onTrackUnplayable())
        assertEquals(listOf(ListenAlongEffect.Play(b)), s.onPoll(ListenAlongPoll.Listening(b), false))
    }

    @Test
    fun `an unplayable track with one held plays the held one`() {
        val s = started()
        s.onPoll(ListenAlongPoll.Listening(b), true)
        assertEquals(listOf(ListenAlongEffect.Play(b)), s.onTrackUnplayable())
    }

    @Test
    fun `an unplayable track keeps waiting while the friend stays on it`() {
        val s = started()
        s.onTrackUnplayable()
        assertEquals(emptyList(), s.onPoll(ListenAlongPoll.Listening(a), false))
        assertFalse(s.isEnded)
    }

    @Test
    fun `catching up plays the held track`() {
        val s = started()
        assertEquals(emptyList(), s.onCatchUp())
        s.onPoll(ListenAlongPoll.Listening(b), true)
        assertEquals(listOf(ListenAlongEffect.Play(b)), s.onCatchUp())
        assertEquals(emptyList(), s.onCatchUp())
    }

    @Test
    fun `a skip ends the session and resumes the queue`() {
        assertEquals(
            listOf(ListenAlongEffect.Message(youStopped), ListenAlongEffect.End(true)),
            started().onUserSkipped(),
        )
    }

    @Test
    fun `playing something else ends the session and keeps the choice`() {
        assertEquals(
            listOf(ListenAlongEffect.Message(youStopped), ListenAlongEffect.End(false)),
            started().onUserPlayedSomethingElse(),
        )
    }

    @Test
    fun `turning it off ends the session and resumes the queue`() {
        assertEquals(
            listOf(ListenAlongEffect.Message(youStopped), ListenAlongEffect.End(true)),
            started().onUserStopped(),
        )
    }

    @Test
    fun `nothing happens after the session ended`() {
        val s = started()
        s.onUserStopped()
        assertEquals(emptyList(), s.start(b))
        assertEquals(emptyList(), s.onPoll(ListenAlongPoll.Listening(b), false))
        assertEquals(emptyList(), s.onSongEnded())
        assertEquals(emptyList(), s.onTrackUnplayable())
        assertEquals(emptyList(), s.onUserPaused())
        assertEquals(emptyList(), s.onUserResumed())
        assertEquals(emptyList(), s.onCatchUp())
        assertEquals(emptyList(), s.onUserSkipped())
        assertEquals(emptyList(), s.onUserPlayedSomethingElse())
        assertEquals(emptyList(), s.onUserStopped())
    }

    @Test
    fun `starting with another friend restarts the session`() {
        assertEquals(
            listOf(ListenAlongEffect.Message("Listening along with Ben"), ListenAlongEffect.Play(b)),
            ListenAlongSession("Ben").start(b),
        )
    }

    @Test
    fun `listen along tracks are metadata only`() {
        val t = listenAlongTrack(a)
        assertEquals("listen-along:" + a.key, t.id)
        assertEquals("song a|artist", a.key)
        assertEquals("Song A", t.title)
        assertEquals("Artist", t.artist)
        assertEquals("Album", t.album)
        assertEquals("http://art/a", t.artworkUrl)
        assertEquals(0L, t.duration)
        assertNull(t.sourceUrl)
        assertNull(t.resolver)
        assertNull(t.spotifyUri)
        assertNull(t.spotifyId)
        assertNull(t.appleMusicId)
        assertNull(t.soundcloudId)
    }

    @Test
    fun `the messages read as the apps show them`() {
        assertEquals(listeningAlong, PlaybackMessages.listeningAlongWith("Ana"))
        assertEquals(stopped, PlaybackMessages.stoppedListening("Ana"))
        assertEquals(youStopped, PlaybackMessages.stoppedListeningAlongWith("Ana"))
        assertEquals(notMoved, PlaybackMessages.hasntMovedOn("Ana"))
    }
}
