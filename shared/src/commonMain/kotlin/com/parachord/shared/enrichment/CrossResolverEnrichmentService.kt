package com.parachord.shared.enrichment

import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.SubmitTrackLinksRequest
import com.parachord.shared.api.TrackLink
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.metadata.TrackEnrichmentRequest
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Slow-trickle enrichment of local-files-only tracks: tries to find
 * streaming-service IDs (Spotify / Apple Music / SoundCloud) via the
 * resolver cascade and pushes the matches to Achordion's match cache.
 *
 * Mirrors desktop's slow-trickle path (CLAUDE.md "Cross-Resolver
 * Enrichment (Eager Gate + Slow Trickle)"). The purpose isn't user-
 * facing speed-up — it's the **Achordion contribution loop** for the
 * ~30% of users whose libraries are local-files only. Without this,
 * those users never feed track-links back to Achordion's match cache.
 *
 * Throttled per-track to avoid hammering streaming-service APIs:
 *
 *  - At most [BATCH_SIZE] tracks per run (default 20).
 *  - [DELAY_BETWEEN_TRACKS_MS] between tracks (default 3000ms).
 *  - Cooldown of [COOLDOWN_MS] before revisiting a previously-enriched
 *    track (default 30 days).
 *
 * Per-track flow:
 *
 *  1. If the track lacks `recordingMbid`, kick the [MbidEnrichmentService]
 *     synchronously and re-read.
 *  2. Run [resolveByTitleArtist] to find streaming-service matches.
 *  3. Persist any new IDs to the track row (additive — never overwrites
 *     an existing populated ID).
 *  4. If MBID is now present AND we have >=1 streaming ID, submit to
 *     Achordion via [AchordionClient.submitTrackLinks].
 *  5. Stamp `crossResolverEnrichedAt = now` regardless of outcome (so a
 *     track with no findable matches isn't retried until the cooldown
 *     elapses).
 *
 * Lives in shared/commonMain so iOS inherits. Generic over the resolver:
 * takes a `(title, artist) -> ResolvedSources` lambda so the service
 * itself doesn't depend on `:app`-only `ResolverManager`. The Android
 * Koin binding closes over `ResolverManager.resolveWithHints` and applies
 * the per-source confidence gate at 0.95.
 */
class CrossResolverEnrichmentService(
    private val trackDao: TrackDao,
    private val mbidEnrichmentService: MbidEnrichmentService,
    private val achordionClient: AchordionClient,
    private val resolveByTitleArtist: suspend (title: String, artist: String) -> ResolvedSources,
) {
    /** Bundle of IDs that the resolver cascade can return. Any can be null. */
    data class ResolvedSources(
        val spotifyId: String? = null,
        val appleMusicId: String? = null,
        val soundcloudId: String? = null,
    ) {
        val isEmpty: Boolean
            get() = spotifyId.isNullOrBlank() && appleMusicId.isNullOrBlank() && soundcloudId.isNullOrBlank()
    }

    companion object {
        private const val TAG = "CrossResolverEnrichmentService"
        const val BATCH_SIZE: Int = 20
        const val DELAY_BETWEEN_TRACKS_MS: Long = 3_000L
        const val COOLDOWN_MS: Long = 30L * 24L * 60L * 60L * 1000L // 30 days
    }

    /**
     * One run of the slow trickle. Picks up to [BATCH_SIZE] candidates,
     * walks them sequentially with a [DELAY_BETWEEN_TRACKS_MS] gap, and
     * reports counts.
     */
    suspend fun runOnce(): RunResult {
        val cutoff = currentTimeMillis() - COOLDOWN_MS
        val candidates = trackDao.selectCrossResolverEnrichmentCandidates(cutoff, BATCH_SIZE)
        if (candidates.isEmpty()) {
            return RunResult(visited = 0, enrichedAnyId = 0, submittedToAchordion = 0)
        }
        Log.d(TAG, "Slow-trickle run starting: ${candidates.size} candidate(s)")
        var enrichedAnyId = 0
        var submittedToAchordion = 0
        for ((index, track) in candidates.withIndex()) {
            if (index > 0) delay(DELAY_BETWEEN_TRACKS_MS)
            val outcome = enrichOne(track)
            if (outcome.gainedAnyStreamingId) enrichedAnyId++
            if (outcome.submittedToAchordion) submittedToAchordion++
        }
        Log.d(
            TAG,
            "Slow-trickle run done: visited=${candidates.size} enrichedAnyId=$enrichedAnyId submittedToAchordion=$submittedToAchordion",
        )
        return RunResult(
            visited = candidates.size,
            enrichedAnyId = enrichedAnyId,
            submittedToAchordion = submittedToAchordion,
        )
    }

    /** Per-run outcome. */
    data class RunResult(val visited: Int, val enrichedAnyId: Int, val submittedToAchordion: Int)

    private data class PerTrackOutcome(val gainedAnyStreamingId: Boolean, val submittedToAchordion: Boolean)

    private suspend fun enrichOne(originalTrack: Track): PerTrackOutcome {
        var track = originalTrack
        try {
            // 1. MBID backfill if missing.
            if (track.recordingMbid.isNullOrBlank()) {
                try {
                    mbidEnrichmentService.enrichTrack(
                        TrackEnrichmentRequest(track.id, track.artist, track.title),
                    )
                    // Re-read for the freshly-populated MBID.
                    val refreshed = trackDao.getById(track.id)
                    if (refreshed != null) track = refreshed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "MBID enrichment failed for ${track.artist} - ${track.title}: ${e.message}")
                    // Continue — we may still find streaming IDs even without MBID
                    // (just can't submit to Achordion).
                }
            }

            // 2. Resolve streaming-service matches.
            val resolved = try {
                resolveByTitleArtist(track.title, track.artist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Resolve failed for ${track.artist} - ${track.title}: ${e.message}")
                ResolvedSources()
            }

            // 3. Persist additively. Don't overwrite existing IDs.
            val newSpotify = resolved.spotifyId?.takeIf { it.isNotBlank() && track.spotifyId.isNullOrBlank() }
            val newAppleMusic = resolved.appleMusicId?.takeIf { it.isNotBlank() && track.appleMusicId.isNullOrBlank() }
            val newSoundCloud = resolved.soundcloudId?.takeIf { it.isNotBlank() && track.soundcloudId.isNullOrBlank() }
            val gainedAny = newSpotify != null || newAppleMusic != null || newSoundCloud != null

            if (gainedAny) {
                trackDao.backfillResolverIds(
                    trackId = track.id,
                    spotifyId = newSpotify,
                    spotifyUri = newSpotify?.let { "spotify:track:$it" },
                    appleMusicId = newAppleMusic,
                    soundcloudId = newSoundCloud,
                )
                track = trackDao.getById(track.id) ?: track
            }

            // 4. Submit to Achordion when MBID + at least one streaming ID is present.
            val mbid = track.recordingMbid
            val submitted = if (!mbid.isNullOrBlank()) {
                val links = buildList {
                    track.spotifyId?.takeIf { it.isNotBlank() }?.let {
                        add(TrackLink("https://open.spotify.com/track/$it", "spotify.com", "Spotify"))
                    }
                    track.appleMusicId?.takeIf { it.isNotBlank() }?.let {
                        add(TrackLink("https://music.apple.com/song/$it", "music.apple.com", "Apple Music"))
                    }
                    track.soundcloudId?.takeIf { it.isNotBlank() }?.let {
                        add(TrackLink("https://soundcloud.com/$it", "soundcloud.com", "SoundCloud"))
                    }
                }
                if (links.isNotEmpty()) {
                    try {
                        achordionClient.submitTrackLinks(
                            SubmitTrackLinksRequest(
                                mbid = mbid,
                                links = links,
                                trackName = track.title,
                                artistName = track.artist,
                                albumName = track.album,
                            ),
                        )
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "Achordion submit failed for ${track.artist} - ${track.title}: ${e.message}")
                        false
                    }
                } else false
            } else false

            return PerTrackOutcome(gainedAnyStreamingId = gainedAny, submittedToAchordion = submitted)
        } finally {
            // 5. ALWAYS stamp the cooldown — even on failure — so a track
            // with no findable matches isn't revisited until the next
            // cooldown window. Otherwise the loop would thrash on the
            // same un-findable tracks every run.
            try {
                trackDao.markCrossResolverEnriched(track.id, currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mark enrichment timestamp for ${track.id}: ${e.message}")
            }
        }
    }
}
