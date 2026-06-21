package com.parachord.shared.sync

import com.parachord.shared.resolver.validateIsrc

/**
 * Cross-provider saved-track identity. The collection row id is keyed by ISRC
 * when present, so the same recording saved on multiple providers (Spotify
 * Liked Songs + Apple Music library) collapses to ONE row that accumulates every
 * service ID — instead of one `spotify-…` row and one `applemusic-…` row.
 *
 * Without a valid ISRC it falls back to the provider-prefixed id (no merge). An
 * ISRC is an exact recording identifier, so keying on it can't merge different
 * songs — this is desktop's `isrc` tier (mbid → isrc → artist|title), minus the
 * fuzzy artist|title tier we deliberately don't use. Used by every saved-track
 * sync provider AND the dedup migration, so they must agree byte-for-byte.
 */
object TrackIdentity {
    fun canonicalTrackId(isrc: String?, fallbackId: String): String {
        val valid = validateIsrc(isrc)
        return if (valid != null) "isrc-$valid" else fallbackId
    }
}
