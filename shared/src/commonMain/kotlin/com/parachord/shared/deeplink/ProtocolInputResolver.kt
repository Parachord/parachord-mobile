package com.parachord.shared.deeplink

/**
 * Result of resolving a [ProtocolPlayInput] into something playable.
 *
 * - [displayName] — what to show in the now-playing card / toast (album
 *   title for `play/album`, playlist title for `play/playlist`, station
 *   name for `play/radio`).
 * - [tracks] — non-empty list ready for queue insertion. Each track is
 *   the on-the-wire `ProtocolTrack` shape; queue-build code is responsible
 *   for stamping `id` / `sources` / `_playbackContext` per the per-track
 *   tagging discipline (foundation item 8).
 * - [albumArt] — optional cover URL surfaced from the resolved metadata.
 */
data class ResolvedProtocolPlay(
    val displayName: String,
    val tracks: List<ProtocolTrack>,
    val albumArt: String? = null,
)

/**
 * Per-command gating for [resolveProtocolPlayInput].
 *
 * Different verbs accept different subsets of identifier types:
 *
 * | Verb                          | mbid | provider | url | tracks | art+title |
 * |-------------------------------|------|----------|-----|--------|-----------|
 * | `play/album`                  |  ✓   |    ✓     |  ✗  |   ✗    |     ✗     |
 * | `play/playlist`               |  ✗   |    ✓     |  ✓  |   ✓    |     ✗     |
 * | `play/radio` (Mode B)         |  ✗   |    ✗     |  ✗  |   ✗    |     ✓     |
 * | `play/radio` (Mode C)         |  ✗   |    ✗     |  ✗  |   ✓    |     ✗     |
 *
 * Disallowed inputs silently fall through to the next priority slot
 * (rather than throwing) — an album MBID passed to `play/playlist`
 * just isn't tried; if no other field works the resolver returns null
 * and the caller surfaces "couldn't resolve" UX.
 */
data class ProtocolResolveOptions(
    val allowMbid: Boolean = true,
    val allowProviderId: Boolean = true,
    val allowUrl: Boolean = true,
    val allowTracks: Boolean = true,
    val allowArtistTitleAlbum: Boolean = true,
    /**
     * `play/playlist` only (parachord#930): when the `url` slot is a provider
     * playlist *page* (Spotify / Apple Music / SoundCloud / Achordion), resolve
     * it via [ProtocolInputResolver.resolveProviderPlaylist] BEFORE the hosted-
     * tracklist-document fetch. Off for album/radio (they keep XSPF/JSON behavior).
     */
    val allowProviderPlaylist: Boolean = false,
)

/**
 * Platform / dependency injection seam for the actual lookups.
 *
 * Phase 1 (this commit) defines the interface. Phase 2 wires concrete
 * implementations against `MetadataService`, `SpotifyApi`,
 * `AppleMusicLibraryClient`, and an HTTP client (for url-fetched
 * tracklists). Wired through Koin in `AndroidModule`.
 *
 * Each method returns null when its identifier type can't be resolved;
 * [resolveProtocolPlayInput] then falls through to the next priority
 * slot. Throw only on hard errors (network 5xx, malformed payloads,
 * SSRF guard rejection — see [validatePublicHttpsUrl]).
 */
interface ProtocolInputResolver {
    suspend fun resolveByMbid(mbid: String): ResolvedProtocolPlay?
    suspend fun resolveBySpotify(spotifyIdOrUri: String): ResolvedProtocolPlay?
    suspend fun resolveByAppleMusic(appleMusicId: String): ResolvedProtocolPlay?
    suspend fun resolveByUrl(url: String): ResolvedProtocolPlay?
    suspend fun resolveByArtistTitle(artist: String, title: String?, album: String? = null): ResolvedProtocolPlay?

    /**
     * `play/playlist` only (parachord#930): resolve a provider playlist *page*
     * URL (Spotify / Apple Music / SoundCloud / Achordion) into tracks via the
     * provider path. Returns null when [url] isn't a recognized provider playlist
     * page, so [resolveProtocolPlayInput] falls back to [resolveByUrl] (a hosted
     * XSPF/JSPF/JSON tracklist document). Default null = not supported.
     */
    suspend fun resolveProviderPlaylist(url: String): ResolvedProtocolPlay? = null
}

/**
 * Walk a [ProtocolPlayInput] in priority order and return the first
 * successful resolution.
 *
 * Priority (from desktop `protocol-schema.md` §3):
 * `mbid → spotify → applemusic → url → tracks → artist+title`
 *
 * Each step is gated by the corresponding [ProtocolResolveOptions] flag;
 * disallowed steps are skipped silently. Inline tracks (`input.tracks`)
 * bypass [ProtocolInputResolver] entirely — they're already resolved
 * and just need wrapping in a [ResolvedProtocolPlay] result.
 *
 * Returns null when the input has no identifiers the command can use
 * AND no inline tracks. Caller surfaces "wrong input" / "couldn't
 * resolve" toast.
 */
suspend fun resolveProtocolPlayInput(
    input: ProtocolPlayInput,
    opts: ProtocolResolveOptions,
    resolver: ProtocolInputResolver,
): ResolvedProtocolPlay? {
    if (opts.allowMbid && input.mbid != null && isValidMbid(input.mbid.lowercase())) {
        resolver.resolveByMbid(input.mbid.lowercase())?.let { return it }
    }
    if (opts.allowProviderId && !input.spotify.isNullOrBlank()) {
        resolver.resolveBySpotify(input.spotify)?.let { return it }
    }
    if (opts.allowProviderId && !input.applemusic.isNullOrBlank()) {
        resolver.resolveByAppleMusic(input.applemusic)?.let { return it }
    }
    if (opts.allowUrl && !input.url.isNullOrBlank()) {
        // play/playlist: a provider playlist *page* resolves via the provider path
        // first (parachord#930); null falls through to the tracklist-document fetch.
        if (opts.allowProviderPlaylist) {
            resolver.resolveProviderPlaylist(input.url)?.let { return it }
        }
        resolver.resolveByUrl(input.url)?.let { return it }
    }
    if (opts.allowTracks && !input.tracks.isNullOrEmpty()) {
        return ResolvedProtocolPlay(
            displayName = input.title ?: "Untitled",
            tracks = input.tracks,
        )
    }
    if (opts.allowArtistTitleAlbum && !input.artist.isNullOrBlank()) {
        resolver.resolveByArtistTitle(input.artist, input.title, /* album = */ null)?.let { return it }
    }
    return null
}
