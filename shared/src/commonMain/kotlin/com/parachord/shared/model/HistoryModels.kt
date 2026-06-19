package com.parachord.shared.model

import kotlinx.serialization.Serializable

// @Serializable so the iOS container can persist top-data lists to a disk cache
// (keyed per timeframe). Recently Played is NOT cached, so RecentTrack stays plain.
@Serializable
data class HistoryTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val playCount: Int = 0,
    val rank: Int = 0,
)

@Serializable
data class HistoryAlbum(
    val name: String,
    val artist: String,
    val artworkUrl: String? = null,
    val playCount: Int = 0,
    val rank: Int = 0,
)

@Serializable
data class HistoryArtist(
    val name: String,
    val imageUrl: String? = null,
    val playCount: Int = 0,
    val rank: Int = 0,
)

data class RecentTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val timestamp: Long = 0,
    val source: String = "",
    val nowPlaying: Boolean = false,
)
