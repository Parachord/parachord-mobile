package com.parachord.android.resolver

import com.parachord.shared.api.SpTrack
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.SpotifyRateLimitedException
import com.parachord.shared.resolver.ResolverCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spotify call-volume + badge-persistence behavior of
 * [ResolverManager.resolveWithHints] (Spotify abuse-ban remediation).
 *
 * 1. When a track already has a Spotify ID, the cascade must NOT also fire a
 *    Spotify *search* — verifying the ID via `getTrack` already yields the
 *    source (and artwork). The redundant search doubled Spotify load per
 *    cached track and the result was discarded anyway.
 * 2. When `getTrack` hits a 429, the known Spotify ID must STILL produce a
 *    Spotify source so the badge doesn't vanish on a transient rate-limit.
 */
class ResolverManagerHintsTest {

    private lateinit var spotifyClient: SpotifyClient
    private lateinit var settingsStore: com.parachord.shared.settings.SettingsStore
    private lateinit var resolver: ResolverManager

    @Before
    fun setUp() {
        spotifyClient = mockk(relaxed = true)
        settingsStore = mockk(relaxed = true)
        // Post-#210, ResolverManager delegates the cascade to the shared
        // ResolverCoordinator. A relaxed mock returns an empty scored list by
        // default, so these tests isolate resolveWithHints' Spotify-verify +
        // 429-keep-the-badge logic (which still lives in ResolverManager).
        val coordinator = mockk<ResolverCoordinator>(relaxed = true)

        coEvery { settingsStore.getSpotifyAccessToken() } returns "token"

        resolver = ResolverManager(
            coordinator = coordinator,
            spotifyClient = spotifyClient,
            settingsStore = settingsStore,
        )
    }

    @Test
    fun `hint with spotify id does not fire a redundant spotify search`() = runTest {
        coEvery { spotifyClient.getTrack(eq("abc"), any()) } returns SpTrack(id = "abc", isPlayable = true)

        val sources = resolver.resolveWithHints(
            query = "Song Artist",
            spotifyId = "abc",
            targetTitle = "Song",
            targetArtist = "Artist",
        )

        assertTrue("expected a spotify source from the verified ID", sources.any { it.resolver == "spotify" })
        coVerify(exactly = 1) { spotifyClient.getTrack(eq("abc"), any()) }
        coVerify(exactly = 0) { spotifyClient.searchTrack(any()) }
    }

    @Test
    fun `429 on verify keeps the known spotify source so the badge persists`() = runTest {
        coEvery { spotifyClient.getTrack(eq("abc"), any()) } throws SpotifyRateLimitedException(120L)

        val sources = resolver.resolveWithHints(
            query = "Song Artist",
            spotifyId = "abc",
            targetTitle = "Song",
            targetArtist = "Artist",
        )

        val spotify = sources.firstOrNull { it.resolver == "spotify" }
        assertTrue("rate-limited verify must still emit the known spotify source", spotify != null)
        assertEquals("abc", spotify!!.spotifyId)
        // ...and it must not fall through to a search (which would also 429).
        coVerify(exactly = 0) { spotifyClient.searchTrack(any()) }
    }

    // An Apple Music ID becomes a source at confidence 1.0 with no search, so the
    // native resolver's Music User Token gate (#327) never sees it. Unconnected,
    // that source ranked Apple Music first and playback went to Apple's sign-in.

    @Test
    fun `apple music id is not a source when apple music isn't connected`() = runTest {
        coEvery { settingsStore.getAppleMusicUserToken() } returns null

        val sources = resolver.resolveWithHints(
            query = "Song Artist",
            appleMusicId = "1440857781",
            targetTitle = "Song",
            targetArtist = "Artist",
        )

        assertTrue("no apple music source without a connection", sources.none { it.resolver == "applemusic" })
    }

    @Test
    fun `apple music id is a source when apple music is connected`() = runTest {
        coEvery { settingsStore.getAppleMusicUserToken() } returns "mut"

        val sources = resolver.resolveWithHints(
            query = "Song Artist",
            appleMusicId = "1440857781",
            targetTitle = "Song",
            targetArtist = "Artist",
        )

        val am = sources.firstOrNull { it.resolver == "applemusic" }
        assertEquals("1440857781", am?.appleMusicId)
        assertEquals(1.0, am?.confidence)
    }
}
