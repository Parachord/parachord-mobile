package com.parachord.shared.playback

import kotlinx.coroutines.CancellationException

/** A complete spinoff seed: Last.fm `track.getsimilar` needs BOTH fields. */
data class SpinoffSeed(val artist: String, val title: String)

/**
 * Turn an artist-only seed into an artist+title seed — port of desktop's
 * `resolveSpinoffSeed` (app.js, parachord#1005 / PR #1015), shared so Android
 * and iOS behave identically (#397).
 *
 * Last.fm `track.getsimilar` needs a TRACK, so `parachord://play/radio?artist=X`
 * with no title has nothing to seed from. Desktop's answer, matched here: use
 * the artist's top Last.fm track.
 *
 * - [title] present (non-blank) → used as-is; [topTrackTitle] is NOT called.
 * - [title] missing → `topTrackTitle(artist)`.
 * - Blank artist, no top track, or a lookup failure → `null`.
 *
 * `null` means "don't start the radio": callers must resolve the seed BEFORE
 * touching the user's queue and bail without clearing it — the desktop bug was
 * clearing the queue first and then playing nothing.
 *
 * [topTrackTitle] is forwarded per platform (both bind it to
 * `LastFmClient.getArtistTopTracks(limit = 1)`), which keeps this testable
 * without a network.
 */
suspend fun resolveSpinoffSeed(
    artist: String?,
    title: String?,
    topTrackTitle: suspend (artist: String) -> String?,
): SpinoffSeed? {
    if (artist.isNullOrBlank()) return null
    if (!title.isNullOrBlank()) return SpinoffSeed(artist, title)
    val top = try {
        topTrackTitle(artist)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A failed lookup is the same as "no track to seed from": no radio,
        // and the queue is left alone. Never a crash.
        null
    }
    return top?.takeIf { it.isNotBlank() }?.let { SpinoffSeed(artist, it) }
}

/** Toast copy when no seed can be found — desktop's exact wording (parachord#1005). */
fun noSpinoffSeedMessage(artist: String): String = "Couldn't find a track by $artist to seed the radio"
