package com.parachord.shared.recommendations

import com.parachord.shared.platform.nfkd
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/*
 * The model behind Recommendations: a port of desktop's
 * `src/renderer/lib/recommendation-model.js` (parachord#1309).
 *
 * Plugins that declare `capabilities.recommendations` answer
 * `getRecommendations(config)` with `{ sections: [{ id?, title?, description?,
 * nextCursor?, items: [...] }] }`. This file turns those answers into one
 * display model:
 *
 *  - an untitled section feeds the generic buckets, one per type (Artists,
 *    Songs, Albums, Playlists), which come first;
 *  - a titled section stays its own grouping, in provider order after them;
 *  - the generic Artists bucket also gets the artists of the generic songs;
 *  - each section is deduplicated per type, so two providers recommending the
 *    same thing show it once.
 *
 * `RecommendationModelTest` holds desktop's vectors; change a rule there first.
 * Not ported: desktop's Shuffleupagus section and its blocklist filter (the
 * phones show AI suggestions on Home and don't filter this page).
 */

@Serializable
enum class RecommendationType(val wire: String) {
    @SerialName("artist") ARTIST("artist"),
    @SerialName("album") ALBUM("album"),
    @SerialName("song") SONG("song"),
    @SerialName("playlist") PLAYLIST("playlist");

    companion object {
        fun fromWire(value: String?): RecommendationType? = entries.firstOrNull { it.wire == value }
    }
}

/** One recommended thing, as a provider sent it and [normalizeRecommendationItem] kept it. */
@Serializable
data class RecommendationItem(
    val type: RecommendationType,
    /** The provider's id, else a song's [trackSlug], else `<type>:<dedupe key>`. */
    val id: String,
    val name: String,
    val resolverId: String? = null,
    /** Songs and albums only. */
    val artist: String? = null,
    val imageUrl: String? = null,
    /** The item's page on the service. */
    val url: String? = null,
    val reason: String? = null,
    /** Ids the service already knows: a song's recording MBID, an ISRC. */
    val mbid: String? = null,
    val isrc: String? = null,
    /** A song's length in **seconds**, as plugins send it. */
    val durationSeconds: Double? = null,
    /** True for an artist taken from a recommended song, not sent by a provider. */
    val derived: Boolean = false,
)

/** One section of a provider's answer. No [title] means it feeds the generic buckets. */
@Serializable
data class RecommendationSection(
    val items: List<RecommendationItem>,
    val id: String? = null,
    val title: String? = null,
    /** The plugin's `description` (renamed: `description` is taken on the Swift side). */
    val summary: String? = null,
    /** The cursor after a first page the provider already fetched. */
    val nextCursor: String? = null,
)

/** One provider's answer, normalized. */
@Serializable
data class ProviderRecommendations(
    val resolverId: String? = null,
    val resolverName: String? = null,
    val sections: List<RecommendationSection> = emptyList(),
    /** When the provider answered (epoch ms); 0 when unknown. */
    val fetchedAt: Long = 0,
)

/** Items a "load more" already fetched for a titled section, keyed `<resolverId>|<sectionId>`. */
data class RecommendationExpansion(
    val items: List<RecommendationItem> = emptyList(),
    val nextCursor: String? = null,
    val exhausted: Boolean = false,
)

/** A section as a page shows it. A generic bucket has [bucketType] and no [title]. */
data class RecommendationDisplaySection(
    val key: String,
    val items: List<RecommendationItem>,
    val title: String? = null,
    val summary: String? = null,
    val bucketType: RecommendationType? = null,
    val resolverId: String? = null,
    val resolverName: String? = null,
    val sectionId: String? = null,
    val canLoadMore: Boolean = false,
    val nextCursor: String? = null,
)

/** The generic buckets, in display order. */
val GENERIC_BUCKET_ORDER: List<RecommendationType> = listOf(
    RecommendationType.ARTIST,
    RecommendationType.SONG,
    RecommendationType.ALBUM,
    RecommendationType.PLAYLIST,
)

private val COMBINING_MARKS = Regex("\\p{M}")

/** Desktop's `slug` (`track-slug.js`): NFKD, marks off, lowercase, letters, digits and `-` kept. */
fun slug(value: String?): String =
    nfkd(value.orEmpty()).replace(COMBINING_MARKS, "").lowercase().filter(::isSlugChar)

/**
 * Desktop's `[\p{L}\p{N}-]`, by category rather than a regex: Kotlin/Native's
 * regex dropped the `-` from that class ("jay-z" became "jayz"). Characters
 * outside the Basic Multilingual Plane are dropped, where desktop keeps them.
 */
private fun isSlugChar(c: Char): Boolean =
    c == '-' || c.isLetter() || c.category == CharCategory.DECIMAL_DIGIT_NUMBER ||
        c.category == CharCategory.LETTER_NUMBER || c.category == CharCategory.OTHER_NUMBER

/** Desktop's two-argument `trackSlug`: the id of a song no provider gave an id. */
fun trackSlug(artist: String?, title: String?): String = "${slug(artist)}-${slug(title)}"

/** Per-type dedupe key: two providers recommending the same thing is one row. */
fun recommendationItemKey(type: RecommendationType, name: String?, artist: String? = null, url: String? = null): String {
    fun norm(s: String?) = s.orEmpty().trim().lowercase()
    return when (type) {
        RecommendationType.SONG, RecommendationType.ALBUM -> "${norm(artist)}|${norm(name)}"
        RecommendationType.PLAYLIST -> if (!url.isNullOrEmpty()) "url:${norm(url)}" else "name:${norm(name)}"
        RecommendationType.ARTIST -> norm(name)
    }
}

fun recommendationItemKey(item: RecommendationItem): String =
    recommendationItemKey(item.type, item.name, item.artist, item.url)

/** A JSON string's text, trimmed; null for anything else or a blank string (JS `typeof x === 'string' && x.trim()`). */
private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

/**
 * One provider item, normalized; null for an unknown type, a nameless item or
 * anything that isn't an object. Optional fields are kept only as non-blank
 * strings, and `artist` only on songs and albums.
 */
fun normalizeRecommendationItem(raw: JsonElement?, resolverId: String? = null): RecommendationItem? {
    val obj = raw as? JsonObject ?: return null
    val type = RecommendationType.fromWire((obj["type"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content) ?: return null
    val name = obj.text("name") ?: return null
    val artist = obj.text("artist")
    val carriesArtist = type == RecommendationType.SONG || type == RecommendationType.ALBUM
    val id = obj.text("id")
        ?: if (type == RecommendationType.SONG) trackSlug(artist, name)
        else "${type.wire}:${recommendationItemKey(type, name, artist)}"
    // JS: `raw.duration && Number.isFinite(Number(raw.duration))`: a non-zero
    // number, or a string that reads as one.
    val duration = if (type == RecommendationType.SONG) {
        (obj["duration"] as? JsonPrimitive)?.takeIf { it !is JsonNull }
            ?.let { it.doubleOrNull ?: it.content.trim().toDoubleOrNull() }
            ?.takeIf { it.isFinite() && it != 0.0 }
    } else null
    return RecommendationItem(
        type = type,
        id = id,
        name = name,
        resolverId = resolverId?.takeIf { it.isNotEmpty() },
        artist = artist.takeIf { carriesArtist },
        imageUrl = obj.text("imageUrl"),
        url = obj.text("url"),
        reason = obj.text("reason"),
        mbid = obj.text("mbid"),
        isrc = obj.text("isrc"),
        durationSeconds = duration,
    )
}

/**
 * One provider's `getRecommendations` answer, normalized: its sections with
 * normalized items, empty sections dropped. [raw] is the provider's
 * `{ sections }`; a bare array is accepted, anything else is empty.
 */
fun normalizeRecommendationSections(
    raw: JsonElement?,
    resolverId: String? = null,
    resolverName: String? = null,
): ProviderRecommendations {
    val rawSections: List<JsonElement> = when (raw) {
        is JsonArray -> raw
        is JsonObject -> (raw["sections"] as? JsonArray).orEmpty()
        else -> emptyList()
    }
    val sections = rawSections.mapNotNull { rawSection ->
        val obj = rawSection as? JsonObject ?: return@mapNotNull null
        val rawItems = obj["items"] as? JsonArray ?: return@mapNotNull null
        val items = rawItems.mapNotNull { normalizeRecommendationItem(it, resolverId) }
        if (items.isEmpty()) return@mapNotNull null
        RecommendationSection(
            items = items,
            id = obj.text("id"),
            title = obj.text("title"),
            summary = obj.text("description"),
            // Passed through as text; an object or null is no cursor.
            nextCursor = (obj["nextCursor"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() },
        )
    }
    return ProviderRecommendations(
        resolverId = resolverId?.takeIf { it.isNotEmpty() },
        resolverName = resolverName?.takeIf { it.isNotEmpty() },
        sections = sections,
    )
}

/** The artists of the recommended songs: unique names, in first-appearance order. */
fun deriveArtistsFromSongs(items: List<RecommendationItem>): List<RecommendationItem> {
    val seen = mutableSetOf<String>()
    val artists = mutableListOf<RecommendationItem>()
    for (item in items) {
        if (item.type != RecommendationType.SONG) continue
        val artist = item.artist?.takeIf { it.isNotEmpty() } ?: continue
        val key = artist.trim().lowercase()
        if (key.isEmpty() || !seen.add(key)) continue
        artists += RecommendationItem(
            type = RecommendationType.ARTIST,
            id = slug(artist),
            name = artist,
            resolverId = item.resolverId,
            derived = true,
        )
    }
    return artists
}

fun dedupeItems(items: List<RecommendationItem>): List<RecommendationItem> {
    val seen = mutableSetOf<String>()
    return items.filter { seen.add("${it.type.wire}:${recommendationItemKey(it)}") }
}

/**
 * The display model: generic buckets first (Artists, Songs, Albums, Playlists),
 * then the providers' titled sections in provider order.
 *
 * @param providers the providers' answers, in the order the page shows them
 * @param expandedSections items a "load more" already fetched, keyed `<resolverId>|<sectionId or title>`
 * @param expandableResolverIds providers that can page a titled section; only theirs get `canLoadMore`
 */
fun buildRecommendationSections(
    providers: List<ProviderRecommendations>,
    expandedSections: Map<String, RecommendationExpansion> = emptyMap(),
    expandableResolverIds: Set<String> = emptySet(),
): List<RecommendationDisplaySection> {
    val generic = GENERIC_BUCKET_ORDER.associateWith { mutableListOf<RecommendationItem>() }
    val titled = mutableListOf<Pair<ProviderRecommendations, RecommendationSection>>()
    for (provider in providers) {
        for (section in provider.sections) {
            if (section.title != null) titled += provider to section
            else section.items.forEach { generic.getValue(it.type) += it }
        }
    }
    // Only from the generic songs: a titled section names its artists itself.
    generic.getValue(RecommendationType.ARTIST) += deriveArtistsFromSongs(generic.getValue(RecommendationType.SONG))

    val sections = mutableListOf<RecommendationDisplaySection>()
    for (type in GENERIC_BUCKET_ORDER) {
        val items = dedupeItems(generic.getValue(type))
        if (items.isEmpty()) continue
        sections += RecommendationDisplaySection(key = "generic:${type.wire}", items = items, bucketType = type)
    }
    for ((provider, section) in titled) {
        val sectionKey = section.id ?: section.title
        val expansion = expandedSections["${provider.resolverId}|$sectionKey"]
        val merged = dedupeItems(section.items + expansion?.items.orEmpty())
        if (merged.isEmpty()) continue
        sections += RecommendationDisplaySection(
            key = "${provider.resolverId}:$sectionKey",
            items = merged,
            title = section.title,
            summary = section.summary,
            resolverId = provider.resolverId,
            resolverName = provider.resolverName,
            sectionId = section.id,
            canLoadMore = section.id != null && provider.resolverId in expandableResolverIds && expansion?.exhausted != true,
            nextCursor = expansion?.nextCursor ?: section.nextCursor,
        )
    }
    return sections
}
