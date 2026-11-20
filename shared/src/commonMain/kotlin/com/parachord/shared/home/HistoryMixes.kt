package com.parachord.shared.home

/**
 * One provider's history mix as a Home card: a playlist with an id, a name and
 * the URL that opens and plays it. [title] is what the card and the Jump back
 * in tile print; [resolverId] and [resolverName] name the service.
 */
data class HistoryMix(
    val id: String,
    val name: String,
    val title: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val url: String,
    val resolverId: String?,
    val resolverName: String?,
    val mosaic: List<String> = emptyList(),
)
