package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #594: one queue save / restore for both apps. */
@OptIn(ExperimentalCoroutinesApi::class)
class QueuePersistenceStoreTest {

    private class FakeStorage(var on: Boolean = true) : QueueStorage {
        var queue: String? = null
        var position: String? = null
        var queueWrites = 0
        override suspend fun enabled() = on
        override suspend fun readQueue() = queue
        override suspend fun writeQueue(json: String) { queue = json; queueWrites++ }
        override suspend fun readPosition() = position
        override suspend fun writePosition(json: String) { position = json }
    }

    private fun t(id: String) = Track(id = id, title = "Title $id", artist = "Artist $id")

    private fun TestScope.store(storage: FakeStorage, qm: QueueManager = QueueManager()) =
        QueuePersistenceStore(storage, qm, this)

    private fun FakeStorage.saved() = QueuePersistenceCodec.decode(queue!!)!!

    @Test
    fun aBurstOfChangesIsOneWriteOfTheLatestQueue() = runTest {
        val storage = FakeStorage()
        val qm = QueueManager()
        val s = store(storage, qm)
        qm.setQueue(listOf(t("a"), t("b")), 0)
        s.saveSoon(t("a"))
        advanceTimeBy(200)
        qm.addToQueue(listOf(t("c")))
        s.saveSoon(t("a"))
        advanceTimeBy(QueuePersistenceStore.SAVE_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(0, storage.queueWrites)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, storage.queueWrites)
        assertEquals("a", storage.saved().currentTrack?.id)
        assertEquals(listOf("b", "c"), storage.saved().upNext.map { it.id })
    }

    @Test
    fun theQueueIsTakenAsItIsAtTheCall() = runTest {
        val storage = FakeStorage()
        val qm = QueueManager()
        val s = store(storage, qm)
        qm.setQueue(listOf(t("a"), t("b")), 0)
        s.saveSoon(t("a"))
        qm.addToQueue(listOf(t("c")))   // no saveSoon for this one
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("b"), storage.saved().upNext.map { it.id })
    }

    @Test
    fun withRememberQueueOffNothingIsSavedOrRestored() = runTest {
        val storage = FakeStorage(on = false)
        val s = store(storage)
        assertFalse(s.write(PersistedQueueState(currentTrack = t("a"))))
        storage.queue = QueuePersistenceCodec.encode(PersistedQueueState(currentTrack = t("a")))
        assertNull(s.load())
        s.savePosition("a", 60_000, 200_000, scrobbled = false)
        runCurrent()
        assertNull(storage.position)
    }

    @Test
    fun anEmptyQueueIsNotSavedOverTheLastSessionsUntilThisRunHadOne() = runTest {
        val storage = FakeStorage()
        storage.queue = "last session"
        val s = store(storage)
        assertFalse(s.write(PersistedQueueState()))
        assertEquals("last session", storage.queue)
        assertTrue(s.write(PersistedQueueState(currentTrack = t("a"))))
        assertTrue(s.write(PersistedQueueState()))
        assertNull(storage.saved().currentTrack)
    }

    @Test
    fun aRestoredQueueGoesBackIntoTheQueueManager() = runTest {
        val storage = FakeStorage()
        storage.queue = QueuePersistenceCodec.encode(
            PersistedQueueState(currentTrack = t("a"), upNext = listOf(t("b")), playHistory = listOf(t("z")), shuffleEnabled = true),
        )
        val qm = QueueManager()
        val s = store(storage, qm)
        val state = s.load()!!
        assertEquals("a", s.apply(state)?.id)
        assertEquals(listOf("b"), qm.snapshot.value.upNext.map { it.id })
        assertEquals(listOf("z"), qm.history.map { it.id })
        assertTrue(qm.snapshot.value.shuffleEnabled)
        assertTrue(s.hadQueueThisRun)
        // Once restored, an emptied queue may be saved.
        assertTrue(s.write(PersistedQueueState()))
    }

    @Test
    fun aMissingEmptyOrBrokenSaveRestoresNothing() = runTest {
        val storage = FakeStorage()
        val s = store(storage)
        assertNull(s.load())
        storage.queue = QueuePersistenceCodec.encode(PersistedQueueState())
        assertNull(s.load())
        storage.queue = "{not json"
        assertNull(s.load())
    }

    /** Android saved its own smaller track shape before #594; those saves still restore. */
    @Test
    fun anOlderAndroidSaveStillRestores() = runTest {
        val storage = FakeStorage()
        storage.queue = """{"currentTrack":{"id":"t1","title":"Jenny Says","artist":"Cowboy Mouth","duration":180000,""" +
            """"resolver":"spotify","spotifyId":"abc","appleMusicId":"123"},""" +
            """"upNext":[{"id":"t2","title":"Hurricane","artist":"Cowboy Mouth"}],"playHistory":[],"shuffleEnabled":false}"""
        val state = store(storage).load()!!
        assertEquals("t1", state.currentTrack?.id)
        assertEquals("abc", state.currentTrack?.spotifyId)
        assertEquals(180_000L, state.currentTrack?.duration)
        assertEquals(listOf("t2"), state.upNext.map { it.id })
    }

    @Test
    fun aRestoredTrackResumesWhereItWas() = runTest {
        val storage = FakeStorage()
        val s = store(storage)
        s.savePosition("a", 60_000, 200_000, scrobbled = false)
        runCurrent()
        assertEquals(60_000L, s.resumePositionMs("a"))
        assertEquals(0L, s.resumePositionMs("b"))
    }

    @Test
    fun aScrobbledListenIsRecordedAndResumesAsScrobbledOnlyAtItsPosition() = runTest {
        // #657: paused at 3:00 of 4:00 after it scrobbled; resuming there mustn't scrobble again.
        val storage = FakeStorage()
        val s = store(storage)
        s.savePosition("a", 180_000, 240_000, scrobbled = true)
        runCurrent()
        assertTrue(s.resumeContinuesScrobbled("a"))
        assertFalse(s.resumeContinuesScrobbled("b"))
        // Within 10 s of the end it starts over: a new listen.
        s.savePosition("a", 235_000, 240_000, scrobbled = true)
        runCurrent()
        assertFalse(s.resumeContinuesScrobbled("a"))
        // Not scrobbled yet: resumes as a listen still to scrobble.
        s.savePosition("a", 60_000, 240_000, scrobbled = false)
        runCurrent()
        assertFalse(s.resumeContinuesScrobbled("a"))
    }

    @Test
    fun thePositionIsSavedOnAPauseAndEvery15SecondsWhilePlaying() = runTest {
        val s = store(FakeStorage())
        assertTrue(s.positionSaveDue(isPlaying = true, nowMs = 100_000))   // first tick: long since a save
        assertFalse(s.positionSaveDue(isPlaying = true, nowMs = 101_000))
        assertFalse(s.positionSaveDue(isPlaying = true, nowMs = 114_999))
        assertTrue(s.positionSaveDue(isPlaying = true, nowMs = 115_000))
        assertTrue(s.positionSaveDue(isPlaying = false, nowMs = 116_000))  // the pause
        assertFalse(s.positionSaveDue(isPlaying = false, nowMs = 140_000))
    }

    @Test
    fun theNextThreeQueuedTracksAreResolved() {
        val upNext = listOf(t("1"), t("2"), t("3"), t("4"))
        assertEquals(listOf("1", "2", "3"), NextTrackPreparation.tracksToPreresolve(upNext).map { it.id })
        assertEquals(listOf("1"), NextTrackPreparation.tracksToPreresolve(listOf(t("1"))).map { it.id })
    }

    @Test
    fun theNextTrackIsReadiedInTheLast30Seconds() {
        assertFalse(NextTrackPreparation.shouldPrepareNext(positionMs = 169_999, durationMs = 200_000))
        assertTrue(NextTrackPreparation.shouldPrepareNext(positionMs = 170_000, durationMs = 200_000))
        assertTrue(NextTrackPreparation.shouldPrepareNext(positionMs = 199_999, durationMs = 200_000))
        assertFalse(NextTrackPreparation.shouldPrepareNext(positionMs = 200_000, durationMs = 200_000))
        assertFalse(NextTrackPreparation.shouldPrepareNext(positionMs = 0, durationMs = 0))
    }
}
