package com.parachord.shared.recommendations

import com.parachord.shared.api.MbReleaseGroup
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.nfkd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Release dates for the Recommendations page's album cards, so an album that
 * isn't out yet carries a "Coming <date>" badge. A port of desktop's
 * `src/renderer/lib/release-date.js`; change a rule there first.
 *
 * No provider sends a release date, so each album is looked up on MusicBrainz
 * (a release-group search by title and artist) and the date kept for
 * [RELEASE_DATE_TTL_MS].
 */
const val RELEASE_DATE_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

private const val MAX_CACHED = 1500
private const val TAG = "ReleaseDates"

private val LIGATURES = mapOf('æ' to "ae", 'œ' to "oe", 'ø' to "o", 'ß' to "ss", 'đ' to "d", 'ł' to "l")
private val COMBINING = Regex("\\p{M}")
private val NOT_ASCII_ALNUM = Regex("[^a-z0-9]+")

private fun fold(s: String?): String {
    val lower = (s ?: "").lowercase()
    val sb = StringBuilder()
    for (c in lower) sb.append(LIGATURES[c] ?: c.toString())
    return nfkd(sb.toString()).replace(COMBINING, "")
}

private fun norm(s: String?): String = fold(s).replace(NOT_ASCII_ALNUM, "")

private val EDITION_WORDS = Regex(
    "\\b(edition|deluxe|remaster(ed)?|expanded|anniversary|version|reissue|bonus)\\b",
    RegexOption.IGNORE_CASE,
)
private val TRAILING_BRACKET = Regex("\\s*[(\\[][^()\\[\\]]*[)\\]]\\s*$")
private val TRAILING_EP_SINGLE = Regex("\\s+-\\s+(EP|Single)\\s*$", RegexOption.IGNORE_CASE)
private val COLON_SPLIT = Regex("^(.+?):\\s+([^:]+)$")

/**
 * A title without the decorations stores add: trailing "(Expanded Edition)",
 * "[Explicit]", " - EP", " - Single", ": Expanded Edition" / ": Deluxe" /
 * ": Remastered". What the search asks for, and what a match compares.
 */
fun searchTitle(title: String): String {
    var t = title.trim()
    for (i in 0 until 4) {
        var next = t.replace(TRAILING_BRACKET, "").replace(TRAILING_EP_SINGLE, "").trim()
        val colon = COLON_SPLIT.find(next)
        if (colon != null && EDITION_WORDS.containsMatchIn(colon.groupValues[2])) next = colon.groupValues[1].trim()
        if (next == t || next.isEmpty()) break
        t = next
    }
    return t
}

private fun bareTitle(s: String?): String = norm(searchTitle(s ?: ""))

fun releaseDateKey(artist: String?, title: String?): String = "${norm(artist)}|${norm(title)}"

private fun luceneQuote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** The Lucene query for a release-group search: `releasegroup:"T" AND artist:"A"`. */
fun releaseGroupQuery(title: String, artist: String): String =
    "releasegroup:${luceneQuote(searchTitle(title))} AND artist:${luceneQuote(artist)}"

private fun creditNames(g: MbReleaseGroup): List<String> =
    g.artistCredit.map { it.name.ifEmpty { it.artist?.name ?: "" } }.filter { it.isNotEmpty() }

private fun artistMatches(g: MbReleaseGroup, artist: String): Boolean {
    val want = norm(artist)
    if (want.isEmpty()) return false
    val names = creditNames(g).map { norm(it) }.filter { it.isNotEmpty() }
    val joined = names.joinToString("")
    return names.any { it == want } ||
        (joined.isNotEmpty() && (joined == want || joined.contains(want) || want.contains(joined)))
}

private fun typeRank(g: MbReleaseGroup): Int = when (g.primaryType?.lowercase()) {
    "album" -> 0
    "ep" -> 1
    else -> 2
}

/**
 * The matching release group's first release date (`YYYY`, `YYYY-MM` or
 * `YYYY-MM-DD`), or null: the title must match without store decorations and
 * the artist must be in the credit; an album or EP beats another type of the
 * same name, then MusicBrainz's own order (best score first).
 */
fun pickReleaseGroupDate(groups: List<MbReleaseGroup>, title: String, artist: String): String? {
    val wantTitle = bareTitle(title)
    if (wantTitle.isEmpty()) return null
    val best = groups.withIndex()
        .filter { (_, g) -> bareTitle(g.title) == wantTitle && artistMatches(g, artist) }
        .sortedWith(compareBy({ typeRank(it.value) }, { it.index }))
        .firstOrNull() ?: return null
    return best.value.firstReleaseDate?.trim()?.takeIf { it.isNotEmpty() }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val DATE_SHAPE = Regex("^(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?$")

/**
 * "Coming Nov 21" (this year), "Coming Feb 3, 2027", "Coming Dec 2026" (month
 * only) or "Coming 2027" (year only) for a date after [today]; null otherwise.
 * Month names are en-US, hard-coded.
 */
fun upcomingLabel(date: String?, today: LocalDate): String? {
    val m = DATE_SHAPE.matchEntire(date?.trim() ?: return null) ?: return null
    val year = m.groupValues[1].toInt()
    val month = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
    val day = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
    if (month != null && month !in 1..12) return null
    if (day != null) {
        val d = try { LocalDate(year, month!!, day) } catch (e: IllegalArgumentException) { return null }
        if (d <= today) return null
        val name = MONTHS[month - 1]
        return if (year == today.year) "Coming $name $day" else "Coming $name $day, $year"
    }
    if (month != null) {
        if (year * 100 + month <= today.year * 100 + today.monthNumber) return null
        return "Coming ${MONTHS[month - 1]} $year"
    }
    return if (year > today.year) "Coming $year" else null
}

@Serializable
private data class DateEntry(val date: String? = null, val at: Long)

/**
 * Looks recommended albums' release dates up and keeps them for
 * [RELEASE_DATE_TTL_MS] (newest 1500 kept). A failed lookup stores nothing,
 * so the next request asks again; a lookup that found nothing is remembered.
 */
class ReleaseDates(
    private val load: suspend () -> String?,
    private val save: suspend (String) -> Unit,
    private val lookup: suspend (title: String, artist: String) -> String?,
    private val nowMs: () -> Long,
) {
    private val mutex = Mutex()
    private val inFlight = mutableSetOf<String>()
    private var entries: MutableMap<String, DateEntry>? = null
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: Flow<Unit> get() = _changes

    private val serializer = MapSerializer(String.serializer(), DateEntry.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ensureLoaded(): MutableMap<String, DateEntry> {
        entries?.let { return it }
        val loaded = try {
            load()?.let { json.decodeFromString(serializer, it).toMutableMap() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "release date cache unreadable: ${e.message}")
            null
        } ?: mutableMapOf()
        entries = loaded
        return loaded
    }

    private fun fresh(e: DateEntry?): Boolean = e != null && nowMs() - e.at < RELEASE_DATE_TTL_MS

    /** The provider's own date first, else the cached lookup, else null. */
    suspend fun dateFor(item: RecommendationItem): String? {
        item.releaseDate?.let { return it }
        val key = releaseDateKey(item.artist, item.name)
        return mutex.withLock { ensureLoaded()[key]?.takeIf { fresh(it) }?.date }
    }

    /** Looks up each album with no date and no fresh answer, one at a time, in order. */
    suspend fun requestLookups(items: List<RecommendationItem>) {
        for (item in items) {
            if (item.type != RecommendationType.ALBUM || item.releaseDate != null) continue
            val artist = item.artist ?: continue
            val key = releaseDateKey(artist, item.name)
            val go = mutex.withLock {
                if (key in inFlight || fresh(ensureLoaded()[key])) false else { inFlight.add(key); true }
            }
            if (!go) continue
            try {
                val date = try {
                    lookup(item.name, artist)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "release date lookup failed: ${e.message}")
                    continue
                }
                val snapshot = mutex.withLock {
                    val map = ensureLoaded()
                    map[key] = DateEntry(date, nowMs())
                    if (map.size > MAX_CACHED) {
                        val keep = map.entries.sortedByDescending { it.value.at }.take(MAX_CACHED)
                        map.clear()
                        keep.forEach { map[it.key] = it.value }
                    }
                    json.encodeToString(serializer, map.toMap())
                }
                try { save(snapshot) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Log.w(TAG, "release date cache not saved: ${e.message}")
                }
                _changes.tryEmit(Unit)
            } finally {
                mutex.withLock { inFlight.remove(key) }
            }
        }
    }
}

/** The MusicBrainz lookup [ReleaseDates] uses: the first release date of the matching release group. */
suspend fun MusicBrainzClient.releaseDateLookup(title: String, artist: String): String? =
    pickReleaseGroupDate(searchReleaseGroups(releaseGroupQuery(title, artist)), title, artist)
