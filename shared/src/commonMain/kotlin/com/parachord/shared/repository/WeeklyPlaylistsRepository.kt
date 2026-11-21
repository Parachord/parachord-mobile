package com.parachord.shared.repository

import com.parachord.shared.api.LbPlaylistTrack
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.platform.Log

/**
 * A ListenBrainz playlist's tracks, by playlist MBID (`GET /1/playlist/{id}`).
 * The Recommendations page and the weekly playlist views on both apps use it
 * for the Weekly Jams / Weekly Exploration tracks and their covers.
 *
 * Which weekly playlists exist, and their headers, come from the ListenBrainz
 * plugin's recommendation sections
 * ([com.parachord.shared.recommendations.weeklyPlaylistHeader]), not from here.
 * Nothing is cached.
 */
class WeeklyPlaylistsRepository(
    private val listenBrainzClient: ListenBrainzClient,
) {
    companion object {
        private const val TAG = "WeeklyPlaylistsRepo"
    }

    /** The playlist's tracks; empty when the request fails. */
    suspend fun loadPlaylistTracks(playlistId: String): List<LbPlaylistTrack> {
        return try {
            listenBrainzClient.getPlaylistTracksRich(playlistId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to load tracks for playlist $playlistId", e)
            emptyList()
        }
    }

    /** Up to 4 distinct album covers from the playlist's tracks, for the 2x2 mosaic. */
    suspend fun loadPlaylistCovers(playlistId: String): List<String> {
        val tracks = loadPlaylistTracks(playlistId)
        return tracks.mapNotNull { it.albumArt }.distinct().take(4)
    }
}
