package com.parachord.shared.playback.scrobbler

import com.parachord.shared.resolver.ResolvedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [buildAchordionLinks] — the Achordion track-links submit must carry
 * EVERY high-confidence resolved source's shareable link, not just the played /
 * top-ranked one (#276). Mirrors the desktop achordion plugin's `buildLinks`.
 */
class AchordionLinksTest {

    private fun src(
        resolver: String,
        confidence: Double? = 0.95,
        noMatch: Boolean = false,
        url: String = "",
        spotifyId: String? = null,
        appleMusicId: String? = null,
        soundcloudId: String? = null,
        soundcloudUrl: String? = null,
    ) = ResolvedSource(
        url = url,
        sourceType = "stream",
        resolver = resolver,
        spotifyId = spotifyId,
        appleMusicId = appleMusicId,
        soundcloudId = soundcloudId,
        soundcloudUrl = soundcloudUrl,
        confidence = confidence,
        noMatch = noMatch,
    )

    @Test
    fun `all high-confidence sources become links — spotify apple soundcloud bandcamp`() {
        val links = buildAchordionLinks(
            listOf(
                src("spotify", spotifyId = "sp1"),
                src("applemusic", appleMusicId = "am1"),
                src("soundcloud", soundcloudUrl = "https://soundcloud.com/a/b"),
                src("bandcamp", url = "https://artist.bandcamp.com/track/x"),
            ),
        )
        val hosts = links.map { it.host }
        assertEquals(listOf("spotify.com", "music.apple.com", "soundcloud.com", "bandcamp.com"), hosts)
        assertEquals("https://open.spotify.com/track/sp1", links[0].url)
        assertEquals("https://music.apple.com/us/song/am1", links[1].url)
        assertEquals("https://soundcloud.com/a/b", links[2].url)
        assertEquals("https://artist.bandcamp.com/track/x", links[3].url)
    }

    @Test
    fun `sub-threshold sources are excluded`() {
        val links = buildAchordionLinks(
            listOf(
                src("spotify", confidence = 0.94, spotifyId = "sp1"),
                src("applemusic", confidence = 0.95, appleMusicId = "am1"),
                src("soundcloud", confidence = null, soundcloudUrl = "https://soundcloud.com/a/b"),
            ),
        )
        assertEquals(listOf("music.apple.com"), links.map { it.host })
    }

    @Test
    fun `noMatch and non-shareable sources are skipped`() {
        val links = buildAchordionLinks(
            listOf(
                src("spotify", noMatch = true, spotifyId = "sp1"),
                src("localfiles", url = "file:///music/x.mp3"),
                src("bandcamp", url = "not-a-url"),
                src("applemusic", appleMusicId = "am1"),
            ),
        )
        assertEquals(listOf("music.apple.com"), links.map { it.host })
    }

    @Test
    fun `duplicate URLs are deduped`() {
        val links = buildAchordionLinks(
            listOf(
                src("spotify", spotifyId = "sp1"),
                src("spotify", spotifyId = "sp1"),
            ),
        )
        assertEquals(1, links.size)
    }

    @Test
    fun `empty input yields no links`() {
        assertTrue(buildAchordionLinks(emptyList()).isEmpty())
    }
}
