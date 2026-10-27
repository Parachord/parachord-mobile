package com.parachord.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class RecommendedArtist(
    val name: String,
    val imageUrl: String? = null,
    val reason: String? = null,
    val source: String = "",
)

@Serializable
data class RecommendedTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    /** Track length in **milliseconds**, as [Track.duration] (#349). */
    val duration: Long? = null,
    val source: String = "",
    val resolvers: List<String> = emptyList(),
    val resolverConfidences: Map<String, Float> = emptyMap(),
)
