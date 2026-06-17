package com.parachord.android.resolver

import com.parachord.shared.platform.Log
import com.parachord.shared.api.SpotifyClient
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.ResolverCoordinator
import com.parachord.shared.resolver.ResolverInfo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages track resolution using native Kotlin resolvers.
 *
 * Post-#210 this is a thin Android wrapper around the shared
 * [ResolverCoordinator] (which owns the fan-out, Spotify branch, gating,
 * re-score and ranking). What stays here is Android-only richness: the
 * `resolveWithHints` wrapper (Spotify ID verify, the 429-keep-the-badge
 * fallback, cascade-artwork merge) and the `resolvers` StateFlow.
 */
class ResolverManager constructor(
    private val coordinator: ResolverCoordinator,
    private val spotifyClient: SpotifyClient,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "ResolverManager"
        private const val SC_API_BASE = "https://api.soundcloud.com"
    }

    private val _resolvers = MutableStateFlow(
        listOf(
            ResolverInfo(id = "spotify", name = "Spotify", enabled = true),
            ResolverInfo(id = "applemusic", name = "Apple Music", enabled = true),
            ResolverInfo(id = "soundcloud", name = "SoundCloud", enabled = true),
            ResolverInfo(id = "localfiles", name = "Local Files", enabled = true),
            // .axe-only resolvers — no native Kotlin implementation, executed via JsBridge
            ResolverInfo(id = "bandcamp", name = "Bandcamp", enabled = true),
            ResolverInfo(id = "youtube", name = "YouTube", enabled = true),
        )
    )
    val resolvers: StateFlow<List<ResolverInfo>> = _resolvers.asStateFlow()

    /**
     * No-op kept for binary/source compatibility with callers that still
     * invoke this from `resolve()`. The proactive `GET /v1/me` probe used
     * to live here, intended to refresh stale tokens at the top of every
     * resolve fan-out.
     *
     * **Why it's gone:** `OAuthRefreshPlugin` (registered for
     * `api.spotify.com` in [com.parachord.shared.api.HttpClientFactory])
     * already handles 401-driven refresh + retry per-request transparently.
     * The probe was redundant — and worse, *harmful* during Spotify abuse
     * windows. Spotify's 429 punishment can run 3600s; every cold start
     * fired this probe ungated, spent the first available rate-budget
     * slot on a no-value `/me` call, and Spotify often answered with a
     * fresh `Retry-After: 3600` that re-armed the in-process gate. The
     * cooldown is now persisted across restarts (see
     * [com.parachord.shared.api.RateLimitGate.loadCooldownEpochMs]) and
     * the per-request refresh handles staleness, so this probe has no
     * remaining purpose.
     */
    @Suppress("unused")
    suspend fun ensureTokensFresh() {
        // Intentionally empty — see KDoc.
    }

    /**
     * Resolve a track query through all enabled and configured resolvers in parallel.
     * Only resolvers that are active (per user settings) and have valid credentials
     * will be included in the pipeline.
     *
     * Delegates to the shared [ResolverCoordinator.resolveScored] — the fan-out,
     * gating, and re-score now live in `commonMain`.
     *
     * @param targetTitle Original track title for confidence scoring
     * @param targetArtist Original track artist for confidence scoring
     */
    suspend fun resolve(
        query: String,
        targetTitle: String? = null,
        targetArtist: String? = null,
        /**
         * Resolver ids to SKIP this run. Used by [resolveWithHints] to avoid
         * re-searching a resolver it already has an authoritative ID hint for
         * — notably Spotify, whose search is both redundant (the ID verify
         * already returns artwork) and a wasted poke that re-arms the abuse
         * cooldown. Skipped resolvers never reach the network.
         */
        excludeResolvers: Set<String> = emptySet(),
    ): List<ResolvedSource> {
        // Proactively refresh stale tokens before resolving (no-op, see KDoc).
        ensureTokensFresh()
        val results = coordinator.resolveScored(
            query = query,
            targetTitle = targetTitle,
            targetArtist = targetArtist,
            album = null,
            excludeResolvers = excludeResolvers,
        )
        Log.d(TAG, "Resolved '$query' → ${results.size} sources: ${results.map { "${it.resolver}(${String.format("%.0f%%", (it.confidence ?: 0.0) * 100)})" }}")
        return results
    }

    /**
     * Resolve using pre-existing IDs (from metadata providers).
     * Verifies that ID-based sources are actually playable before trusting them.
     * Falls back to search-based resolution for all enabled resolvers.
     *
     * @param targetTitle Original track title for confidence scoring
     * @param targetArtist Original track artist for confidence scoring
     */
    suspend fun resolveWithHints(
        query: String,
        spotifyId: String? = null,
        soundcloudId: String? = null,
        appleMusicId: String? = null,
        targetTitle: String? = null,
        targetArtist: String? = null,
    ): List<ResolvedSource> = coroutineScope {
        val results = mutableListOf<ResolvedSource>()

        // If we have a Spotify ID from metadata, verify it's actually playable
        // before trusting it (metadata IDs can reference tracks unavailable in
        // the user's market).
        if (spotifyId != null) {
            val source = try {
                verifySpotifyTrack(spotifyId)
            } catch (e: com.parachord.shared.api.SpotifyRateLimitedException) {
                // Spotify is rate-limited. Do NOT drop a known-good ID — that's
                // what made the Spotify badges vanish across a whole list the
                // moment the account got 429'd. Emit the cached ID as a source
                // (playback can use it directly) so the badge persists; artwork
                // fills in on a later resolve once the cooldown clears.
                ResolvedSource(
                    url = "spotify:track:$spotifyId",
                    sourceType = "spotify",
                    resolver = "spotify",
                    spotifyUri = "spotify:track:$spotifyId",
                    spotifyId = spotifyId,
                    confidence = 1.0,
                )
            }
            if (source != null) {
                results.add(source)
            }
        }

        // If we have a SoundCloud ID, use it directly
        if (soundcloudId != null) {
            results.add(
                ResolvedSource(
                    url = "$SC_API_BASE/tracks/$soundcloudId",
                    sourceType = "soundcloud",
                    resolver = "soundcloud",
                    soundcloudId = soundcloudId,
                    // Direct-ID hint: a previously-validated streaming ID is
                    // by definition correct, so stamp 1.0. Reserves 0.95 for
                    // fuzzy scoreConfidence() matches. Matches desktop's
                    // two-tier model (see CLAUDE.md "Achordion Pre-resolution
                    // Plugin" — tier-1 dispatch gates on >= 1.0).
                    confidence = 1.0,
                )
            )
        }

        // If we have an Apple Music ID, use it directly
        if (appleMusicId != null) {
            results.add(
                ResolvedSource(
                    url = "applemusic:song:$appleMusicId",
                    sourceType = "applemusic",
                    resolver = "applemusic",
                    appleMusicId = appleMusicId,
                    // Direct-ID hint — see SoundCloud branch above.
                    confidence = 1.0,
                )
            )
        }

        // Also run the regular resolve pipeline for other sources
        // (with target title/artist for confidence scoring). When the cascade
        // produces a result for the SAME resolver as a hint (e.g. cascade's
        // AM search resolves a track for which we already have an AM ID
        // hint), backfill the hint with the cascade's artworkUrl. Hints are
        // ID-authoritative but don't carry artwork; the cascade's resolver
        // calls (MusicKit search, Spotify search) DO return artwork, and
        // discarding it via the resolver-name filter forces the downstream
        // image-enrichment cascade to RE-ASK metadata providers. Same root
        // cause as the AM-artwork-propagation work in [resolveAppleMusic] /
        // [SpotifyClient.searchTrack].
        // Skip the cascade's Spotify SEARCH when we already produced an
        // authoritative Spotify source from the ID hint (verified, or the
        // cached-ID fallback above). The search is redundant — verifySpotifyTrack
        // already returns artwork — and during an abuse window it's a second
        // wasted poke that re-arms the cooldown. (AM/SC hints still run their
        // cascade search for artwork backfill; they aren't the rate-limit
        // offender. See #176/#177.)
        val excludeFromCascade =
            if (results.any { it.resolver == "spotify" }) setOf("spotify") else emptySet()
        val cascadeResults = resolve(query, targetTitle, targetArtist, excludeResolvers = excludeFromCascade)
        val cascadeByResolver = cascadeResults.associateBy { it.resolver }
        val mergedHints = results.map { hint ->
            if (hint.artworkUrl == null) {
                val cascadeArt = cascadeByResolver[hint.resolver]?.artworkUrl
                if (cascadeArt != null) hint.copy(artworkUrl = cascadeArt) else hint
            } else hint
        }
        val others = cascadeResults.filter { it.resolver !in mergedHints.map { r -> r.resolver } }
        mergedHints + others
    }

    /**
     * Verify a Spotify track ID is actually playable in the user's market.
     * Returns a ResolvedSource if playable, null if not available.
     */
    private suspend fun verifySpotifyTrack(spotifyId: String): ResolvedSource? {
        if (settingsStore.getSpotifyAccessToken().isNullOrBlank()) return null
        return try {
            val track = spotifyClient.getTrack(
                trackId = spotifyId,
            )
            if (track.isPlayable == false) {
                Log.d(TAG, "Spotify track $spotifyId is not playable in user's market")
                return null
            }
            ResolvedSource(
                url = "spotify:track:${track.id}",
                sourceType = "spotify",
                resolver = "spotify",
                spotifyUri = "spotify:track:${track.id}",
                spotifyId = track.id,
                // Capture the ISRC on the cached-ID verify path too (#217) — the
                // getTrack full-track object carries external_ids.isrc, and this is
                // the common path (known spotifyId), so dropping it here starved
                // the ISRC→MBID fallback for most Spotify tracks.
                isrc = com.parachord.shared.resolver.validateIsrc(track.externalIds?.isrc),
                // Direct-ID match — see SoundCloud branch above (~L190).
                confidence = 1.0,
                artworkUrl = track.album?.images?.firstOrNull()?.url,
            )
        } catch (e: com.parachord.shared.api.SpotifyRateLimitedException) {
            // Cooldown active — rethrow so the caller (resolveWithHints) can keep
            // the known-good ID as a source instead of dropping the badge. The
            // gate already logged the first 429 of the cycle, so no per-track log.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to verify Spotify track $spotifyId: ${e.message}")
            null
        }
    }
}

// Models (ResolverInfo, ResolvedSource) and scoring functions (normalizeStr, scoreConfidence)
// are now in the shared module: com.parachord.shared.resolver
