package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #604: a play link marks the run as "a play was asked for" as it arrives, so
 * the saved-queue restore on a cold start doesn't land on top of it. Only
 * links that start a play count.
 */
class DeepLinkStartsPlaybackTest {

    private fun parse(host: String, path: List<String> = emptyList(), params: Map<String, String> = emptyMap()) =
        DeepLinkParser.parse(SimpleDeepLinkUri("parachord", host, path, params))

    @Test
    fun playLinksStartPlayback() {
        assertTrue(parse("play", params = mapOf("artist" to "Ramones", "title" to "Judy Is a Punk")).startsPlayback())
        assertTrue(parse("play", path = listOf("album"), params = mapOf("mbid" to "x")).startsPlayback())
        assertTrue(parse("play", path = listOf("playlist"), params = mapOf("url" to "https://open.spotify.com/playlist/abc")).startsPlayback())
        assertTrue(parse("play", path = listOf("radio"), params = mapOf("artist" to "Ramones")).startsPlayback())
        assertTrue(parse("listen-along", params = mapOf("service" to "listenbrainz", "user" to "kutx")).startsPlayback())
        assertTrue(DeepLinkAction.SpotifyTrack("abc").startsPlayback())
        assertTrue(DeepLinkAction.AppleMusicSong("123").startsPlayback())
    }

    @Test
    fun otherLinksDontStartPlayback() {
        assertFalse(parse("control", path = listOf("pause")).startsPlayback())
        assertFalse(parse("artist", path = listOf("Ramones")).startsPlayback())
        assertFalse(parse("queue", path = listOf("clear")).startsPlayback())
        assertFalse(parse("import", params = mapOf("url" to "https://ex.com/p.xspf")).startsPlayback())
        assertFalse(parse("recommendations").startsPlayback())
        // An incomplete play link parses to Unknown and plays nothing.
        assertFalse(parse("play", params = mapOf("artist" to "Ramones")).startsPlayback())
        assertFalse(DeepLinkAction.SpotifyAlbum("abc").startsPlayback())
    }
}

/** #604: a run that starts empty doesn't wipe the saved queue. */
class QueueRestoreSaveTest {
    private val policy = com.parachord.shared.playback.QueueRestorePolicy

    @Test
    fun anEmptyStartIsNotSavedOverTheLastSessionsQueue() {
        assertFalse(policy.shouldSave(isEmpty = true, hadQueueThisRun = false))
    }

    @Test
    fun aQueueIsAlwaysSaved() {
        assertTrue(policy.shouldSave(isEmpty = false, hadQueueThisRun = false))
        assertTrue(policy.shouldSave(isEmpty = false, hadQueueThisRun = true))
    }

    @Test
    fun aQueueClearedOrRunOutThisRunIsSavedEmpty() {
        assertTrue(policy.shouldSave(isEmpty = true, hadQueueThisRun = true))
    }
}
