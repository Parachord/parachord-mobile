package com.parachord.shared.sync

/**
 * Thrown by [SyncProvider.searchForTrackId] when the lookup could not be
 * ANSWERED — the identity services were unreachable — as opposed to answering
 * "no match".
 *
 * [HydrationCoordinator] treats this as transient: it leaves the track pending
 * and does NOT stamp an attempt, so an outage never feeds the 7→30-day miss
 * cooldown. Every other exception (and a plain `null`) still counts as a miss,
 * which is what makes the cooldown escalate on genuinely un-findable tracks.
 *
 * Why this exists: during the 2026-09 ListenBrainz mapper outage every failed
 * lookup was recorded as a miss, parking LB-synced tracks in a month-long
 * cooldown for a service problem that had nothing to do with the track
 * (parachord-mobile#394, desktop parachord#991).
 */
class TrackIdLookupUnreachableException(
    message: String = "Track-id lookup unreachable",
) : Exception(message)
