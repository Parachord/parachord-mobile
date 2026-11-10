package com.parachord.shared.playback

import com.parachord.shared.model.Track
import com.parachord.shared.playback.scrobbler.Scrobbler
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The loved-tracks push both platforms run (#125): per-service toggles, the
 * idempotency cache, and the backfill tally.
 */
class LovesPushServiceTest {

    private class FakeSecureStore : SecureTokenStore {
        private val m = mutableMapOf<String, String>()
        override fun get(key: String): String? = m[key]
        override fun set(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun contains(key: String): Boolean = m.containsKey(key)
        override fun observe(key: String): Flow<String?> = flowOf(m[key])
    }

    private class FakeScrobbler(
        override val id: String,
        var connected: Boolean = true,
        val failFor: Set<String> = emptySet(),
    ) : Scrobbler {
        override val displayName: String = id
        val loved = mutableListOf<String>()
        override suspend fun isEnabled(): Boolean = connected
        override suspend fun sendNowPlaying(track: Track, durationMs: Long?) {}
        override suspend fun submitScrobble(track: Track, timestamp: Long, durationMs: Long?) {}
        override suspend fun loveTrack(track: Track) {
            if (track.id in failFor) error("boom")
            loved += track.id
        }
    }

    private fun track(id: String) = Track(id = id, title = "Song $id", artist = "Artist")

    private fun store() = SettingsStore(FakeSecureStore(), KvStore(MapSettings()))

    @Test
    fun nothing_is_pushed_while_every_toggle_is_off() = runTest {
        val lfm = FakeScrobbler("lastfm")
        val service = LovesPushService(setOf(lfm), store(), backfillDelayMs = 0)
        service.pushLovedNow(track("a"))
        assertTrue(lfm.loved.isEmpty())
    }

    @Test
    fun a_love_goes_only_to_enabled_services_and_only_once() = runTest {
        val settings = store()
        settings.setLovePushEnabled("lastfm", true)
        val lfm = FakeScrobbler("lastfm")
        val lb = FakeScrobbler("listenbrainz")
        val service = LovesPushService(setOf(lfm, lb), settings, backfillDelayMs = 0)

        service.pushLovedNow(track("a"))
        service.pushLovedNow(track("a"))

        assertEquals(listOf("a"), lfm.loved)
        assertTrue(lb.loved.isEmpty())
        assertTrue(settings.getLovePushedKeys()["a"]?.containsKey("lastfm") == true)
    }

    @Test
    fun a_disconnected_or_failing_service_is_tried_again_next_time() = runTest {
        val settings = store()
        settings.setLovePushEnabled("listenbrainz", true)
        val lb = FakeScrobbler("listenbrainz", connected = false)
        val service = LovesPushService(setOf(lb), settings, backfillDelayMs = 0)

        service.pushLovedNow(track("a"))
        assertTrue(lb.loved.isEmpty())
        assertTrue(settings.getLovePushedKeys().isEmpty())

        lb.connected = true
        service.pushLovedNow(track("a"))
        assertEquals(listOf("a"), lb.loved)
    }

    @Test
    fun backfill_skips_pushed_tracks_and_counts_failures() = runTest {
        val settings = store()
        val lfm = FakeScrobbler("lastfm", failFor = setOf("c"))
        val service = LovesPushService(setOf(lfm), settings, backfillDelayMs = 0)
        settings.setLovePushEnabled("lastfm", true)
        service.pushLovedNow(track("a"))

        val done = service.runBackfill("lastfm", listOf(track("a"), track("b"), track("c")))

        assertEquals(LovesPushService.BackfillState.Done("lastfm", pushed = 1, skipped = 1, failed = 1), done)
        assertEquals(done, service.backfillState.value)
        assertEquals(listOf("a", "b"), lfm.loved)
    }
}
