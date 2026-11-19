package com.parachord.shared.recommendations

/**
 * The category rows Albums and Playlists split into: a port of desktop's
 * category tables in `src/renderer/lib/recommendation-model.js`.
 *
 * One table per item type that has category rows, each in page order. `artist`
 * has no fixed title: its row title is built from the seed artists by
 * [artistRowTitle]. `other` is the fallback [categoryFor] returns, never a
 * value a plugin sends. The same id can carry a different title per type
 * (`personal` is "Made for you" on playlists, "Based on your listening" on
 * albums).
 */
object RecommendationCategories {

    /** One row of a category table. A null [title] is the artist row, titled by [artistRowTitle]. */
    data class Category(val id: String, val title: String?)

    val PLAYLIST: List<Category> = listOf(
        Category("personal", "Made for you"),
        Category("artist", null),
        Category("editorial", "From the editors"),
        Category("community", "From other listeners"),
        Category("other", "More playlists"),
    )

    /** `new` is valid on albums only (Amazon Music's New Releases for You). */
    val ALBUM: List<Category> = listOf(
        Category("new", "New for you"),
        Category("personal", "Based on your listening"),
        Category("artist", null),
        Category("editorial", "From the editors"),
        Category("other", "More albums"),
    )

    const val OTHER = "other"

    val TABLES: Map<RecommendationType, List<Category>> = mapOf(
        RecommendationType.PLAYLIST to PLAYLIST,
        RecommendationType.ALBUM to ALBUM,
    )

    /** Each category's position in its type's table. */
    val CATEGORY_RANK: Map<RecommendationType, Map<String, Int>> =
        TABLES.mapValues { (_, table) -> table.withIndex().associate { (i, c) -> c.id to i } }

    /** The ids a plugin may send for a type: its table's, minus the `other` fallback. */
    private val VALID: Map<RecommendationType, List<String>> =
        TABLES.mapValues { (_, table) -> table.map { it.id }.filter { it != OTHER } }

    /** True when [id] is a category a plugin may send for [type]. */
    fun validFor(type: RecommendationType, id: String?): Boolean =
        id != null && VALID[type]?.contains(id) == true

    /** True when [id] is valid for any type: what a section's own category may be. */
    fun validForAny(id: String?): Boolean = VALID.keys.any { validFor(it, id) }

    /**
     * Which category row an item of [type] belongs in: for playlists a weekly
     * playlist is personal; then the item's own category, then its section's,
     * each only when valid for the type, else `other`. Null for a type with no
     * category table.
     */
    fun categoryFor(type: RecommendationType, item: RecommendationItem?, sectionCategory: String?): String? {
        if (type !in TABLES) return null
        if (type == RecommendationType.PLAYLIST && item?.weekly != null) return "personal"
        item?.category?.takeIf { validFor(type, it) }?.let { return it }
        sectionCategory?.takeIf { validFor(type, it) }?.let { return it }
        return OTHER
    }

    /** Title of the artist row: "Because you like A, B and C", up to three names. */
    fun artistRowTitle(seeds: List<String?>): String {
        val seen = mutableSetOf<String>()
        val names = mutableListOf<String>()
        for (raw in seeds) {
            val name = raw?.trim().orEmpty()
            if (name.isEmpty() || !seen.add(name.lowercase())) continue
            names += name
        }
        return when (names.size) {
            0 -> "Because of artists you like"
            1 -> "Because you like ${names[0]}"
            2 -> "Because you like ${names[0]} and ${names[1]}"
            3 -> "Because you like ${names[0]}, ${names[1]} and ${names[2]}"
            else -> "Because you like ${names.take(3).joinToString(", ")} and more"
        }
    }
}
