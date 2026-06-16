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
    private lateinit var resolver: ResolverManager

    @Before
    fun setUp() {
        spotifyClient = mockk(relaxed = true)
        val settingsStore = mockk<com.parachord.shared.settings.SettingsStore>(relaxed = true)
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
}
