package com.parachord.shared.home

import com.parachord.shared.metadata.AlbumCatalog
import com.parachord.shared.model.Playlist as PlaylistModel
import com.parachord.shared.model.Track
import com.parachord.shared.playback.PlaybackContext

/** Port of desktop's `home-model.js`: jump back in, sections, the Ask prompt and the recommendations throttle. */

/** What is playing and what is queued, by playback context. */
data class ActiveContexts(val playing: PlaybackContext? = null, val queued: PlaybackContext? = null)

sealed class JumpCandidate {
    data class Playlist(val id: String?, val title: String?) : JumpCandidate()
    data class Mix(val id: String?) : JumpCandidate()
    object Loves : JumpCandidate()
    data class Album(val album: String?, val artist: String?, val mbid: String? = null) : JumpCandidate()
}

/**
 * Is this candidate what's playing or queued? Compared by playback context only.
 * Album contexts on mobile are `type = "album"`, `name` = title, `id` = artist
 * (the form `CatalogQueues.album` uses) and carry no mbid, so an album matches
 * by normalized name + artist.
 */
fun isActiveContext(c: JumpCandidate, active: ActiveContexts): Boolean =
    listOfNotNull(active.playing, active.queued).any { ctx ->
        when (c) {
            is JumpCandidate.Playlist -> {
                if (ctx.type != "playlist") false
                else if (ctx.id != null) c.id != null && ctx.id == c.id
                else !c.title.isNullOrEmpty() && ctx.name == c.title
            }
            is JumpCandidate.Mix -> ctx.type == "mix" && c.id != null && ctx.id == c.id
            JumpCandidate.Loves -> ctx.type == "loves"
            is JumpCandidate.Album -> {
                if (ctx.type != "album") false
                else {
                    val album = AlbumCatalog.normalizeAlbumTitle(c.album)
                    val artist = AlbumCatalog.normalizeArtistName(c.artist)
                    album.isNotEmpty() &&
                        AlbumCatalog.normalizeAlbumTitle(ctx.name) == album &&
                        AlbumCatalog.normalizeArtistName(ctx.id) == artist
                }
            }
        }
    }

data class LoveAlbum(val album: String, val artist: String, val trackTitle: String, val mbid: String?)

/** The album behind the newest love that isn't playing or queued; loves without an album are skipped. */
fun latestLoveAlbum(loves: List<Track>, active: ActiveContexts): LoveAlbum? {
    for (t in loves) {
        val album = t.album
        if (album.isNullOrEmpty()) continue
        val mbid = t.releaseMbid?.takeIf { it.isNotEmpty() }
        if (isActiveContext(JumpCandidate.Album(album, t.artist, mbid), active)) continue
        return LoveAlbum(album, t.artist, t.title, mbid)
    }
    return null
}

sealed class JumpTile {
    abstract val subtitle: String
    data class Playlist(val playlist: PlaylistModel, override val subtitle: String) : JumpTile()
    data class Loves(val tracks: List<Track>, override val subtitle: String = HomeCopy.LOVES_TILE_SUBTITLE) : JumpTile()
    data class Album(val album: LoveAlbum) : JumpTile() {
        override val subtitle: String get() = HomeCopy.albumBehindLove(album.trackTitle)
    }
    data class Mix(val mix: HistoryMix, override val subtitle: String = HomeCopy.MIXES_TITLE) : JumpTile()
}

/** Up to four tiles in the order playlist, loves, album, mix; each kind takes its first candidate that isn't active. */
fun jumpBackInTiles(
    playlists: List<PlaylistModel>,
    loves: List<Track>,
    mixes: List<HistoryMix>,
    active: ActiveContexts,
    nowMs: Long,
): List<JumpTile> {
    val tiles = mutableListOf<JumpTile>()
    val playlist = freshestPlaylists(playlists, Int.MAX_VALUE)
        .firstOrNull { !isActiveContext(JumpCandidate.Playlist(it.id, it.name), active) }
    if (playlist != null) {
        val subtitle = if (changeBadge(playlist, nowMs) != null)
            "Playlist · ${playlist.lastChangeAdded} new ${whenPhraseShort(playlist.tracksChangedAt!!, nowMs)}"
        else "Playlist"
        tiles += JumpTile.Playlist(playlist, subtitle)
    }
    if (loves.isNotEmpty() && !isActiveContext(JumpCandidate.Loves, active)) tiles += JumpTile.Loves(loves)
    latestLoveAlbum(loves, active)?.let { tiles += JumpTile.Album(it) }
    mixes.firstOrNull { !isActiveContext(JumpCandidate.Mix(it.id), active) }?.let { tiles += JumpTile.Mix(it) }
    return tiles.take(4)
}

data class HomeSections(
    val banner: Boolean,
    val jump: Boolean,
    val importCard: Boolean,
    val mixes: Boolean,
    val playlists: Boolean,
    val albums: Boolean,
    val fresh: Boolean,
    val critical: Boolean,
    val concerts: Boolean,
    val ask: Boolean,
)

/** Which sections Home shows. The import card is for a new user only: no collection and no playlists. */
fun homeSections(
    bannerItems: List<*>? = null,
    tiles: List<*>? = null,
    mixes: List<*>? = null,
    loves: List<*>? = null,
    playlists: List<*>? = null,
    albums: List<*>? = null,
    fresh: List<*>? = null,
    critical: List<*>? = null,
    concerts: List<*>? = null,
    concertsLocation: String? = null,
    hasCollection: Boolean = false,
): HomeSections {
    fun has(l: List<*>?) = !l.isNullOrEmpty()
    return HomeSections(
        banner = has(bannerItems),
        jump = has(tiles),
        importCard = !hasCollection && !has(playlists),
        mixes = has(mixes) || has(loves),
        playlists = has(playlists),
        albums = has(albums),
        fresh = has(fresh),
        critical = has(critical),
        concerts = has(concerts) && !concertsLocation.isNullOrEmpty(),
        ask = true,
    )
}

// ── Ask ─────────────────────────────────────────────────────────────────────

private val PHRASINGS: List<(String) -> String> = listOf(
    { a -> "A rainy Sunday playlist built around $a" },
    { a -> "A late night mix with $a" },
    { a -> "Songs that sound like $a" },
    { a -> "A road trip playlist starting with $a" },
)

private const val NAME_CAP = 40

/** Desktop's UNSAFE_CHARS, by code point range (no regex Unicode classes: Kotlin/Native rejects some). */
private fun isUnsafeChar(c: Char): Boolean {
    val n = c.code
    return n in 0x00..0x1F || n in 0x7F..0x9F || n == 0x061C || n in 0x200B..0x200F ||
        n in 0x202A..0x202E || n in 0x2066..0x2069
}

/** JavaScript's regex `\s` set, which differs from Kotlin's `isWhitespace` (U+FEFF in, U+001C-001F out). */
private fun isJsWhitespace(c: Char): Boolean {
    val n = c.code
    return n in 0x09..0x0D || n == 0x20 || n == 0xA0 || n == 0x1680 || n in 0x2000..0x200A ||
        n == 0x2028 || n == 0x2029 || n == 0x202F || n == 0x205F || n == 0x3000 || n == 0xFEFF
}

/**
 * An artist name as it may appear in the prompt: control, bidi and zero-width
 * characters removed, whitespace collapsed, capped at 40 code points (never
 * half a surrogate pair). Ask skips its confirmation, so a name can't reorder
 * or hide text.
 */
fun promptName(name: String?): String {
    if (name == null) return ""
    val sb = StringBuilder()
    var lastSpace = false
    for (c in name) {
        if (isJsWhitespace(c)) {
            if (!lastSpace) sb.append(' ')
            lastSpace = true
            continue
        }
        if (isUnsafeChar(c)) continue
        sb.append(c)
        lastSpace = false
    }
    val clean = sb.toString().trim()
    var count = 0
    var end = 0
    while (end < clean.length && count < NAME_CAP) {
        end += if (clean[end].isHighSurrogate() && end + 1 < clean.length && clean[end + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return clean.substring(0, end).trim()
}

private fun joinNames(names: List<String>): String =
    if (names.size <= 1) names.firstOrNull().orEmpty()
    else "${names.dropLast(1).joinToString(", ")} and ${names.last()}"

fun exampleShufflePrompt(artists: List<String>, seed: Int): String {
    val names = artists.map(::promptName).filter { it.isNotEmpty() }.take(3)
    if (names.isEmpty()) return "Make me a playlist for right now"
    val i = ((seed % 4) + 4) % 4
    return PHRASINGS[i](joinNames(names))
}

/** The most frequent artists among the loves, ties by recency; blank or over-80-character names dropped. */
fun homeTopArtists(loves: List<Track>, limit: Int = 3): List<String> {
    val counts = LinkedHashMap<String, IntArray>() // name -> [count, firstIndex]
    loves.forEachIndexed { i, t ->
        val name = t.artist.trim()
        if (name.isEmpty() || name.length > 80) return@forEachIndexed
        val c = counts[name]
        if (c != null) c[0] += 1 else counts[name] = intArrayOf(1, i)
    }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, IntArray>> { it.value[0] }.thenBy { it.value[1] })
        .take(limit)
        .map { it.key }
}

// ── Recommendation items ────────────────────────────────────────────────────

fun <T> filterBySource(
    items: List<T>,
    sourceId: String,
    resolverId: (T) -> String?,
    sourceIds: (T) -> List<String>,
): List<T> =
    if (sourceId == "All") items
    else items.filter { resolverId(it) == sourceId || sourceId in sourceIds(it) }

/**
 * Albums Home asks a cover for: the first [perSource] of the whole list and of
 * each source's own list, so picking a chip never shows cards without covers.
 * Order kept, no repeats.
 */
fun <T> albumsNeedingArt(
    albums: List<T>,
    perSource: Int = 6,
    resolverId: (T) -> String?,
    sourceIds: (T) -> List<String>,
): List<T> {
    val ids = mutableListOf<String>()
    for (a in albums) {
        for (id in listOf(resolverId(a)) + sourceIds(a)) {
            if (!id.isNullOrEmpty() && id !in ids) ids += id
        }
    }
    val want = HashSet<Int>() // indices, so equal items stay distinct
    albums.indices.take(perSource).forEach { want += it }
    for (id in ids) {
        albums.indices
            .filter { resolverId(albums[it]) == id || id in sourceIds(albums[it]) }
            .take(perSource)
            .forEach { want += it }
    }
    return albums.filterIndexed { i, _ -> i in want }
}

// ── Recommendations load throttle ───────────────────────────────────────────

/** A full load that finished within this long ago keeps Home from asking again. */
const val HOME_RECOMMENDATIONS_THROTTLE_MS = 600_000L

fun shouldLoadHomeRecommendations(loading: Boolean, loadStarted: Boolean, lastFullLoadAt: Long?, nowMs: Long): Boolean {
    if (loading && loadStarted) return false
    if (lastFullLoadAt != null && lastFullLoadAt > 0 && nowMs - lastFullLoadAt < HOME_RECOMMENDATIONS_THROTTLE_MS) return false
    return true
}

/** Whether a finished full load counts for the throttle: not when every provider asked failed, nor with no section to show. */
fun shouldStampHomeRecommendationsLoad(fetched: Int, failed: Int, sectionCount: Int): Boolean {
    if (failed > 0 && failed == fetched) return false
    return sectionCount > 0
}

// ── Recent Loves mosaic ─────────────────────────────────────────────────────

data class LovesMosaicAlbum(val artist: String, val album: String, val albumArt: String?)

/** The newest distinct albums among the loves (artist and album), with the track's own cover when it has one. */
fun lovesMosaicAlbums(loves: List<Track>, n: Int = 4): List<LovesMosaicAlbum> {
    val out = mutableListOf<LovesMosaicAlbum>()
    val seen = HashSet<String>()
    for (t in loves) {
        val album = t.album
        if (album.isNullOrEmpty() || t.artist.isEmpty()) continue
        if (!seen.add("${t.artist}\u0000$album".lowercase())) continue
        out += LovesMosaicAlbum(t.artist, album, t.artworkUrl?.takeIf { it.isNotEmpty() })
        if (out.size >= n) break
    }
    return out
}

// ── Jump back in art ────────────────────────────────────────────────────────

/** A Jump back in tile's art: one cover, or a 2x2 of covers ([mosaic] holds four). */
data class JumpTileArt(val imageUrl: String? = null, val mosaic: List<String> = emptyList()) {
    val isEmpty: Boolean get() = imageUrl == null && mosaic.isEmpty()
}

/** A stable key per tile: the same playlist, album or mix keeps it across rebuilds. */
fun jumpTileKey(tile: JumpTile): String = when (tile) {
    is JumpTile.Playlist -> "playlist:${tile.playlist.id}"
    is JumpTile.Loves -> "loves"
    is JumpTile.Album -> "album:${tile.album.artist.trim().lowercase()}\u0000${tile.album.album.trim().lowercase()}"
    is JumpTile.Mix -> "mix:${tile.mix.id}"
}

/**
 * The art a tile shows from what Home already holds, with no lookup (desktop's
 * `JumpTile` art): a playlist's own cover; Recent Loves' 2x2 of the newest
 * four loved albums when all four have covers ([lovesMosaicAlbums]), else the
 * newest love's cover; the album behind the latest love from a love on that
 * album ([loves] are the newest first); a mix's image, else its mosaic. An
 * empty answer for an album means the platform looks the cover up; otherwise
 * it draws its placeholder.
 */
fun jumpTileArt(tile: JumpTile, loves: List<Track>): JumpTileArt {
    fun clean(u: String?) = u?.trim()?.takeIf { it.isNotEmpty() }
    return when (tile) {
        is JumpTile.Playlist -> JumpTileArt(imageUrl = clean(tile.playlist.artworkUrl))
        is JumpTile.Loves -> {
            val covers = lovesMosaicAlbums(tile.tracks).mapNotNull { clean(it.albumArt) }
            if (covers.size >= 4) JumpTileArt(mosaic = covers.take(4))
            else JumpTileArt(imageUrl = tile.tracks.firstNotNullOfOrNull { clean(it.artworkUrl) })
        }
        is JumpTile.Album -> {
            val album = tile.album.album.trim().lowercase()
            val artist = tile.album.artist.trim().lowercase()
            val cover = loves.firstNotNullOfOrNull { t ->
                if (t.album?.trim()?.lowercase() == album && t.artist.trim().lowercase() == artist) clean(t.artworkUrl) else null
            }
            JumpTileArt(imageUrl = cover)
        }
        is JumpTile.Mix -> {
            val image = clean(tile.mix.imageUrl)
            if (image != null) JumpTileArt(imageUrl = image)
            else JumpTileArt(mosaic = tile.mix.mosaic.mapNotNull { clean(it) }.take(4))
        }
    }
}
