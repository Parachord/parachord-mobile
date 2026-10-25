package com.parachord.shared.metadata

import com.parachord.shared.platform.nfkd
import com.parachord.shared.resolver.LETTER_OR_NUMBER_CLASS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Album track lists from the plugins when MusicBrainz and the other metadata
 * providers can't supply one (#424). Port of desktop
 * `src/renderer/lib/album-catalog.js` (parachord#1129): which resolvers to
 * ask in what order, whether a result is the album that was asked for, and the
 * conversion to the album-page shape.
 *
 * Plugins that declare `capabilities.albumSearch` implement `searchAlbums`
 * (`{ artist, title }` → `[{ id, name, artist, albumArt, releaseDate,
 * trackCount, url }]`) and `lookupAlbum(url)` (→ the album with `tracks`).
 * [AlbumCatalogLoader] calls them; this object is the pure part.
 */
object AlbumCatalog {

    /** One plugin album, from `searchAlbums` (no tracks) or `lookupAlbum`. */
    data class Album(
        val id: String?,
        val name: String,
        val artist: String?,
        val albumArt: String?,
        val releaseDate: String?,
        val trackCount: Int?,
        val url: String,
        val tracks: List<Track> = emptyList(),
    )

    /** A catalog track. [duration] is in SECONDS, as the plugins return it. */
    data class Track(
        val title: String?,
        val duration: Double?,
        val trackNumber: Int?,
        val discNumber: Int?,
    )

    // ── Matching ────────────────────────────────────────────────────────────

    private val MARKS = Regex("\\p{M}")
    private val NOT_LETTER_OR_NUMBER = Regex("[^$LETTER_OR_NUMBER_CLASS]+")

    /** Edition words services add to a title, which MusicBrainz's and the request usually lack. */
    private val EDITION = Regex(
        "\\s*[(\\[]\\s*(?:[^)\\]]*\\b(?:deluxe|expanded|remaster(?:ed)?|anniversary|edition|version|explicit|clean|bonus|reissue|mono|stereo)\\b[^)\\]]*)[)\\]]\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val FORMAT_SUFFIX = Regex("\\s+-\\s+(?:single|ep|lp)\\s*$", RegexOption.IGNORE_CASE)
    private val LEADING_THE = Regex("^the\\s+")
    private val ARTIST_SEPARATORS = Regex(
        "\\s*(?:,|&|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bfeaturing|\\bwith|\\bx\\b|/)\\s*",
        RegexOption.IGNORE_CASE,
    )

    private fun stripMarks(s: String?): String = nfkd(s.orEmpty()).replace(MARKS, "").lowercase()

    fun normalizeAlbumTitle(title: String?): String =
        stripMarks(title).replace(EDITION, " ").replace(FORMAT_SUFFIX, "")
            .replace("&", "and").replace(NOT_LETTER_OR_NUMBER, "")

    fun normalizeArtistName(name: String?): String =
        stripMarks(name).replace(LEADING_THE, "").replace("&", "and").replace(NOT_LETTER_OR_NUMBER, "")

    /** The credited artists of a joined credit ("A, B & C", "A feat. B"). */
    private fun splitArtists(name: String?): List<String> =
        name.orEmpty().split(ARTIST_SEPARATORS).map(::normalizeArtistName).filter { it.isNotEmpty() }

    /** Containment counts only when the shorter side is most of the longer one,
     *  so "Red" never matches "Red Hot Chili Peppers". */
    private fun closeEnough(a: String, b: String, ratio: Double): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return long.contains(short) && short.length >= long.length * ratio
    }

    /**
     * Whether a catalog album is the one asked for: the title matches (edition
     * suffixes aside) and so does the artist (one of a joint credit is enough).
     * 0 = no match; otherwise higher is closer.
     */
    fun albumMatchScore(targetArtist: String, targetTitle: String, album: Album): Int {
        val wantTitle = normalizeAlbumTitle(targetTitle)
        val gotTitle = normalizeAlbumTitle(album.name)
        if (!closeEnough(wantTitle, gotTitle, 0.6)) return 0
        val wantArtist = normalizeArtistName(targetArtist)
        val gotArtist = normalizeArtistName(album.artist)
        val artistOk = closeEnough(wantArtist, gotArtist, 0.6) ||
            wantArtist in splitArtists(album.artist) ||
            gotArtist in splitArtists(targetArtist)
        if (!artistOk) return 0
        // The same title before edition suffixes were stripped beats an edition:
        // "Dulling the Horns" over "Dulling the Horns (Deluxe)".
        fun bare(s: String?) = stripMarks(s).replace(NOT_LETTER_OR_NUMBER, "")
        val exactTitle = if (bare(targetTitle) == bare(album.name)) 1 else 0
        return (if (wantTitle == gotTitle) 2 else 1) + exactTitle + (if (wantArtist == gotArtist) 2 else 1)
    }

    /** Same album by artist and title, edition suffixes aside. */
    fun sameAlbumIdentity(artistA: String?, titleA: String?, artistB: String?, titleB: String?): Boolean {
        val tA = normalizeAlbumTitle(titleA)
        val aA = normalizeArtistName(artistA)
        return tA.isNotEmpty() && aA.isNotEmpty() &&
            tA == normalizeAlbumTitle(titleB) && aA == normalizeArtistName(artistB)
    }

    /** The best match among one resolver's results, or null. */
    fun pickCatalogAlbum(albums: List<Album>?, targetArtist: String, targetTitle: String): Album? {
        var best: Album? = null
        var bestScore = 0
        for (album in albums.orEmpty()) {
            val score = albumMatchScore(targetArtist, targetTitle, album)
            if (score > bestScore) {
                best = album
                bestScore = score
            }
        }
        return best
    }

    /**
     * Which resolvers to ask, in order: [preferId] first, then the user's active
     * resolvers in their priority [order], then every other album-search
     * resolver. A plugin without the credentials it needs returns no albums, so
     * asking it is cheap.
     */
    fun orderCatalogResolvers(
        resolverIds: List<String>,
        activeIds: List<String> = emptyList(),
        order: List<String> = emptyList(),
        preferId: String? = null,
    ): List<String> {
        fun rank(id: String): Int {
            if (preferId != null && id == preferId) return -1
            val pos = order.indexOf(id)
            val slot = if (pos == -1) order.size else pos
            return if (id in activeIds) slot else 1000 + slot
        }
        return resolverIds.sortedBy(::rank)
    }

    // ── Conversion ──────────────────────────────────────────────────────────

    /**
     * A catalog album as the album page's [AlbumDetail].
     *
     * The REQUESTED title and artist win over the catalog's spelling: track
     * identity (the resolver cache key, playlist entries) is built from artist,
     * title and album, so the same album must produce the same tracks whichever
     * source answered. Service fields (ids, sources, stream urls) are dropped so
     * the normal resolution pass finds each track on the user's own services.
     * Tracks are sorted by disc then track number; duration becomes milliseconds.
     */
    fun toAlbumDetail(
        album: Album,
        resolverName: String,
        requestedTitle: String,
        requestedArtist: String,
        requestedArtworkUrl: String? = null,
    ): AlbumDetail {
        val art = requestedArtworkUrl ?: album.albumArt
        val sorted = album.tracks.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: 0 }))
        val tracks = sorted.filter { !it.title.isNullOrBlank() }.map { t ->
            TrackSearchResult(
                title = t.title!!,
                artist = requestedArtist,
                album = requestedTitle,
                duration = t.duration?.takeIf { it > 0 }?.let { kotlin.math.round(it * 1000).toLong() },
                artworkUrl = art,
                provider = "catalog",
            )
        }
        return AlbumDetail(
            title = requestedTitle,
            artist = requestedArtist,
            artworkUrl = art,
            year = album.releaseDate?.take(4)?.toIntOrNull(),
            tracks = tracks,
            provider = "catalog",
            catalogSource = resolverName,
        )
    }

    // ── Plugin JSON ─────────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    /** `searchAlbums` result → albums. Entries without a name or url are skipped. */
    fun parseAlbums(raw: String?): List<Album> {
        val arr = parse(raw) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::albumFrom) }
    }

    /** `lookupAlbumFrom` result (`{ album, resolverId }` or a bare album) → album with tracks. */
    fun parseLookup(raw: String?): Album? {
        val obj = parse(raw) as? JsonObject ?: return null
        val albumObj = obj["album"] as? JsonObject ?: obj
        return albumFrom(albumObj)
    }

    private fun parse(raw: String?): JsonElement? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }

    private fun albumFrom(o: JsonObject): Album? {
        val name = o.str("name") ?: return null
        val url = o.str("url") ?: return null
        val tracks = (o["tracks"] as? JsonArray).orEmpty().mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            Track(
                title = t.str("title"),
                duration = t.num("duration"),
                trackNumber = t.num("trackNumber")?.toInt(),
                discNumber = t.num("discNumber")?.toInt(),
            )
        }
        return Album(
            id = o.str("id"),
            name = name,
            artist = o.str("artist"),
            albumArt = o.str("albumArt"),
            releaseDate = o.str("releaseDate"),
            trackCount = o.num("trackCount")?.toInt(),
            url = url,
            tracks = tracks,
        )
    }

    /** A string field (numbers accepted too), or null. */
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.contentOrNull?.takeIf { it.isNotBlank() }

    /** A numeric field (numeric strings accepted too), or null. */
    private fun JsonObject.num(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
    }
}
