package com.parachord.shared.recommendations

import com.parachord.shared.platform.nfkd
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * The items behind Recommendations: a port of desktop's
 * `src/renderer/lib/recommendation-model.js` (items, keys and the merge; the
 * page builder on top of these is in `RecommendationPage.kt`).
 *
 * Plugins that declare `capabilities.recommendations` answer
 * `getRecommendations(config)` with `{ sections: [{ id?, title?, description?,
 * category?, seedArtist?, nextCursor?, items: [...] }] }`. This file normalizes
 * those answers ([normalizeRecommendationSections]), keys items so two
 * providers recommending the same thing are one item
 * ([recommendationItemKey], [providerIdKey]), and merges the providers' lists
 * ([mergeOwned], [interleave]). The category tables are in
 * [RecommendationCategories]; the blocklist check is [blockedItemPick].
 *
 * `RecommendationModelTest` holds desktop's vectors; change a rule there first.
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

/**
 * A ListenBrainz weekly playlist (Weekly Jams / Exploration), recognized by
 * this field, never by provider name. [contextType] is `weekly-jam` or
 * `weekly-exploration`.
 */
@Serializable
data class WeeklyInfo(
    val id: String,
    val contextType: String,
    val label: String,
    val weekLabel: String,
    val date: String? = null,
)

/** One recommended thing, as a provider sent it and [normalizeRecommendationItem] kept it. */
@Serializable
data class RecommendationItem(
    val type: RecommendationType,
    /** The provider's id, else a song's [trackSlug], else `<type>:<dedupe key>`. */
    val id: String,
    val name: String,
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
    val duration: Double? = null,
    /** An album's release date: `YYYY`, `YYYY-MM` or `YYYY-MM-DD`. */
    val releaseDate: String? = null,
    /** Playlists only: a weekly playlist. */
    val weekly: WeeklyInfo? = null,
    /** A category valid for this type ([RecommendationCategories.validFor]). */
    val category: String? = null,
    /** The seed artist of an `artist`-category row the item came from. */
    val seedArtist: String? = null,
    val resolverId: String? = null,
    /** The source's display name, set by [homeRecommendationAlbums] (desktop's `sourceLabel`). */
    val resolverName: String? = null,
    /** Every provider that sent this item, first one first (set by [mergeOwned]). */
    val sourceIds: List<String> = emptyList(),
    /** True for an artist taken from a recommended song, not sent by a provider. */
    val derived: Boolean = false,
    /** The item's position in its row's blend. */
    val blendIndex: Int? = null,
)

/** One section of a provider's answer. No [title] is the generic-bucket shape. */
@Serializable
data class RecommendationSection(
    val items: List<RecommendationItem>,
    val id: String? = null,
    val title: String? = null,
    /** The plugin's `description` (renamed: `description` is taken on the Swift side). */
    val summary: String? = null,
    /** A category valid for some type ([RecommendationCategories.validForAny]). */
    val category: String? = null,
    /** Only on an `artist`-category section. */
    val seedArtist: String? = null,
    /** The cursor after a first page the provider already fetched. */
    val nextCursor: String? = null,
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

private fun norm(s: String?): String = s.orEmpty().trim().lowercase()

/** The key from bare fields, as desktop calls it with `{ type, name, artist }`. */
private fun itemKey(type: RecommendationType, name: String?, artist: String?, url: String?, weeklyId: String?): String =
    when (type) {
        RecommendationType.SONG, RecommendationType.ALBUM -> "${norm(artist)}|${norm(name)}"
        RecommendationType.PLAYLIST -> when {
            !url.isNullOrEmpty() -> "url:${norm(url)}"
            // Weekly playlists have no URL and their names repeat ("This Week"
            // is both Exploration's and Jams' current week): their own id is the key.
            !weeklyId.isNullOrEmpty() -> "weekly:$weeklyId"
            else -> "name:${norm(name)}"
        }
        RecommendationType.ARTIST -> norm(name)
    }

/** Per-type dedupe key: two providers recommending the same thing is one row. */
fun recommendationItemKey(item: RecommendationItem): String =
    itemKey(item.type, item.name, item.artist, item.url, item.weekly?.id)

/** A JSON string's text, trimmed; null for anything else or a blank string (JS `typeof x === 'string' && x.trim()`). */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive).string()?.trim()?.takeIf { it.isNotEmpty() }

/** A JSON string's raw text; null for anything else. */
private fun JsonPrimitive?.string(): String? = this?.takeIf { it.isString }?.content

private val RELEASE_DATE = Regex("^[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?$")
private val WEEKLY_CONTEXTS = setOf("weekly-jam", "weekly-exploration")

/**
 * JS `raw.duration && Number.isFinite(Number(raw.duration))`: a truthy value
 * that reads as a finite number.
 */
private fun jsDuration(value: JsonElement?): Double? {
    val p = value as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    val number = when {
        p.isString -> if (p.content.isEmpty()) return null else p.content.trim().ifEmpty { "0" }.toDoubleOrNull()
        p.booleanOrNull != null -> if (p.booleanOrNull == true) 1.0 else return null
        else -> p.doubleOrNull?.takeIf { it != 0.0 }
    }
    return number?.takeIf { it.isFinite() }
}

private fun weeklyOf(raw: JsonElement?): WeeklyInfo? {
    val w = raw as? JsonObject ?: return null
    val id = (w["id"] as? JsonPrimitive).string()?.takeIf { it.isNotEmpty() } ?: return null
    val contextType = (w["contextType"] as? JsonPrimitive).string()?.takeIf { it in WEEKLY_CONTEXTS } ?: return null
    val label = (w["label"] as? JsonPrimitive).string() ?: return null
    val weekLabel = (w["weekLabel"] as? JsonPrimitive).string() ?: return null
    return WeeklyInfo(id, contextType, label, weekLabel, (w["date"] as? JsonPrimitive).string())
}

/**
 * One provider item, normalized; null for an unknown type, a nameless item or
 * anything that isn't an object. Optional fields are kept only as non-blank
 * strings, and `artist` only on songs and albums.
 */
fun normalizeRecommendationItem(raw: JsonElement?, resolverId: String?): RecommendationItem? {
    val obj = raw as? JsonObject ?: return null
    val type = RecommendationType.fromWire((obj["type"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content) ?: return null
    val name = obj.text("name") ?: return null
    val artist = obj.text("artist")
    val id = obj.text("id")
        ?: if (type == RecommendationType.SONG) trackSlug(artist.orEmpty(), name)
        else "${type.wire}:${itemKey(type, name, artist, null, null)}"
    val releaseDate = if (type == RecommendationType.ALBUM) {
        (obj["releaseDate"] as? JsonPrimitive).string()?.trim()?.take(10)?.takeIf { RELEASE_DATE.matches(it) }
    } else null
    val rawCategory = (obj["category"] as? JsonPrimitive).string()
    return RecommendationItem(
        type = type,
        id = id,
        name = name,
        artist = artist.takeIf { type == RecommendationType.SONG || type == RecommendationType.ALBUM },
        imageUrl = obj.text("imageUrl"),
        url = obj.text("url"),
        reason = obj.text("reason"),
        mbid = obj.text("mbid"),
        isrc = obj.text("isrc"),
        duration = if (type == RecommendationType.SONG) jsDuration(obj["duration"]) else null,
        releaseDate = releaseDate,
        weekly = if (type == RecommendationType.PLAYLIST) weeklyOf(obj["weekly"]) else null,
        category = rawCategory?.takeIf { RecommendationCategories.validFor(type, it) },
        resolverId = resolverId?.takeIf { it.isNotEmpty() },
    )
}

/**
 * One provider's `getRecommendations` answer, normalized: its sections with
 * normalized items, empty sections dropped. [raw] is the provider's
 * `{ sections }`; a bare array is accepted, anything else is empty. Titles are
 * trimmed; a section with no title is the generic-bucket shape. [resolverName]
 * isn't on the sections: the caller keeps it beside them.
 */
@Suppress("UNUSED_PARAMETER")
fun normalizeRecommendationSections(
    raw: JsonElement?,
    resolverId: String? = null,
    resolverName: String? = null,
): List<RecommendationSection> {
    val rawSections: List<JsonElement> = when (raw) {
        is JsonArray -> raw
        is JsonObject -> (raw["sections"] as? JsonArray).orEmpty()
        else -> emptyList()
    }
    return rawSections.mapNotNull { rawSection ->
        val obj = rawSection as? JsonObject ?: return@mapNotNull null
        val rawItems = obj["items"] as? JsonArray ?: return@mapNotNull null
        val items = rawItems.mapNotNull { normalizeRecommendationItem(it, resolverId) }
        if (items.isEmpty()) return@mapNotNull null
        val category = (obj["category"] as? JsonPrimitive).string()?.takeIf { RecommendationCategories.validForAny(it) }
        RecommendationSection(
            items = items,
            id = obj.text("id"),
            title = obj.text("title"),
            summary = obj.text("description"),
            category = category,
            seedArtist = if (category == "artist") obj.text("seedArtist") else null,
            // JS `nextCursor != null && typeof nextCursor !== 'object'` → String(it).
            nextCursor = (obj["nextCursor"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        )
    }
}

/**
 * The artists of the recommended songs: unique names (case-insensitive), in
 * first-appearance order, marked [RecommendationItem.derived].
 */
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
            derived = true,
            resolverId = item.resolverId,
        )
    }
    return artists
}

/** One item per `<type>:<key>`, first kept. */
fun dedupeItems(items: List<RecommendationItem>): List<RecommendationItem> {
    val seen = mutableSetOf<String>()
    return items.filter { seen.add("${it.type.wire}:${recommendationItemKey(it)}") }
}

/**
 * `<type>:<id>` when the item carries a provider's own id; null when its id is
 * the fallback [normalizeRecommendationItem] makes from the name (that one
 * ignores a playlist's url and weekly id, so two playlists the name key keeps
 * apart can share it).
 */
fun providerIdKey(item: RecommendationItem): String? {
    if (item.id.isEmpty()) return null
    val fallback = if (item.type == RecommendationType.SONG) trackSlug(item.artist.orEmpty(), item.name)
    else "${item.type.wire}:${itemKey(item.type, item.name, item.artist, null, null)}"
    return if (item.id == fallback) null else "${item.type.wire}:${item.id}"
}

private const val SHUFFLEUPAGUS = "shuffleupagus"

/**
 * Dedupe several providers' lists: the first provider in priority order owns
 * an item's position and fields; later providers sending the same thing add
 * their id to `sourceIds`. A later Shuffleupagus reason is copied when the
 * kept item has none, so the AI's "why" survives the merge. When both copies
 * of a playlist or album carry a category, the kept one takes whichever comes
 * first in its type's [RecommendationCategories.CATEGORY_RANK] (with that
 * copy's seedArtist). An item matches by `<type>:<key>` or by its provider id
 * ([providerIdKey]); either key of a merged copy finds it from then on.
 *
 * Returns one owned list per input list, for [interleave].
 */
fun mergeOwned(lists: List<List<RecommendationItem>>): List<List<RecommendationItem>> {
    class Kept(var item: RecommendationItem)
    val kept = mutableMapOf<String, Kept>()
    val keptById = mutableMapOf<String, Kept>()
    val owned = lists.map { list ->
        val out = mutableListOf<Kept>()
        for (item in list) {
            val key = "${item.type.wire}:${recommendationItemKey(item)}"
            val idKey = providerIdKey(item)
            val existing = kept[key] ?: idKey?.let { keptById[it] }
            if (existing == null) {
                val copy = Kept(item.copy(sourceIds = listOfNotNull(item.resolverId)))
                kept[key] = copy
                if (idKey != null) keptById[idKey] = copy
                out += copy
                continue
            }
            if (key !in kept) kept[key] = existing
            if (idKey != null && idKey !in keptById) keptById[idKey] = existing
            var merged = existing.item
            if (item.resolverId != null && item.resolverId !in merged.sourceIds) {
                merged = merged.copy(sourceIds = merged.sourceIds + item.resolverId)
            }
            if (merged.reason == null && item.reason != null && item.resolverId == SHUFFLEUPAGUS) {
                merged = merged.copy(reason = item.reason)
            }
            val rank = RecommendationCategories.CATEGORY_RANK[item.type]
            val incomingRank = item.category?.let { rank?.get(it) }
            val keptRank = merged.category?.let { rank?.get(it) }
            if (incomingRank != null && keptRank != null && incomingRank < keptRank) {
                merged = merged.copy(category = item.category, seedArtist = item.seedArtist)
            }
            existing.item = merged
        }
        out
    }
    return owned.map { list -> list.map { it.item } }
}

/** One item per list per round, in list order. */
fun <T> interleave(lists: List<List<T>>): List<T> {
    val out = mutableListOf<T>()
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) for (list in lists) if (i < list.size) out += list[i]
    return out
}

/**
 * True when the blocklist leaves [item] out. A port of desktop's
 * `blockedItemPick` + `recommendation-blocklist.js` `isBlocked`, read over
 * mobile's stored entries ([com.parachord.shared.settings.SettingsStore.getBlockedRecommendations]):
 * `artist:<name>`, `album:<artist>:<title>`, `track:<artist>:<title>`, as the
 * DJ's `block_recommendation` tool writes them.
 *
 * An artist item is its artist; a song or album is its artist (else its name)
 * plus its title, matched against blocked artists, tracks and albums alike (an
 * item never names an album it's on); a playlist is never blocked. Matching is
 * case-insensitive on trimmed text, never fuzzy. A `:` inside an artist or
 * title is fine: every split of the entry is tried.
 */
fun blockedItemPick(item: RecommendationItem, blocked: Set<String>): Boolean {
    if (blocked.isEmpty()) return false
    val (artist, title) = when (item.type) {
        RecommendationType.ARTIST -> norm(item.name) to null
        RecommendationType.SONG, RecommendationType.ALBUM -> norm(item.artist?.takeIf { it.isNotEmpty() } ?: item.name) to norm(item.name)
        RecommendationType.PLAYLIST -> return false
    }
    for (entry in blocked) {
        val colon = entry.indexOf(':')
        if (colon < 0) continue
        val kind = entry.substring(0, colon)
        val rest = entry.substring(colon + 1)
        when (kind) {
            "artist" -> if (artist.isNotEmpty() && norm(rest) == artist) return true
            "album", "track" -> {
                if (title.isNullOrEmpty()) continue
                var at = rest.indexOf(':')
                while (at >= 0) {
                    val a = norm(rest.substring(0, at))
                    val t = norm(rest.substring(at + 1))
                    if ((a.isNotEmpty() || t.isNotEmpty()) && a == artist && t == title) return true
                    at = rest.indexOf(':', at + 1)
                }
            }
        }
    }
    return false
}
