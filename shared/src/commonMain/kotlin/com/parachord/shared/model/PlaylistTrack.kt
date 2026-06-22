package com.parachord.shared.model

data class PlaylistTrack(
    val playlistId: String,
    val position: Int,
    val trackTitle: String,
    val trackArtist: String,
    val trackAlbum: String? = null,
    val trackDuration: Long? = null,
    val trackArtworkUrl: String? = null,
    val trackSourceUrl: String? = null,
    val trackResolver: String? = null,
    val trackSpotifyUri: String? = null,
    val trackSoundcloudId: String? = null,
    val trackSpotifyId: String? = null,
    val trackAppleMusicId: String? = null,
    val trackRecordingMbid: String? = null,
    val addedAt: Long = 0L,
    // Transient (in-memory) ISRC carried from a provider fetch (e.g. Spotify
    // external_ids.isrc) so cross-provider hydration can resolve a recording
    // MBID via MusicBrainz `/isrc/` when the LB mapper is down. NOT a DB column —
    // mirrors the resolver/scrobbler "ISRC lives only on the live track" rule;
    // re-fetched each pass, dropped on any DB round-trip.
    val trackIsrc: String? = null,
) {
    fun availableResolvers(resolverOrder: List<String> = emptyList()): List<String> {
        val available = buildList {
            if (!trackSpotifyId.isNullOrBlank() || !trackSpotifyUri.isNullOrBlank()) add("spotify")
            if (!trackAppleMusicId.isNullOrBlank()) add("applemusic")
            if (!trackSoundcloudId.isNullOrBlank()) add("soundcloud")
            if (trackResolver != null && !contains(trackResolver)) add(trackResolver)
        }
        if (resolverOrder.isEmpty()) return available
        return available.sortedBy { r ->
            val idx = resolverOrder.indexOf(r)
            if (idx < 0) Int.MAX_VALUE else idx
        }
    }
}
