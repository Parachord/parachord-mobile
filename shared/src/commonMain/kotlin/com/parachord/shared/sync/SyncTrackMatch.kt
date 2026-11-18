package com.parachord.shared.sync

import com.parachord.shared.model.SyncSource
import com.parachord.shared.model.Track
import com.parachord.shared.resolver.normalizeStr

/**
 * Which collection row an incoming library track is, when the ids disagree
 * (#682, a port of desktop's `sync-engine/track-identity.js`, parachord#1404
 * and #1419).
 *
 * A heart saves a track under whatever id it had where it was tapped (a search
 * result, an album page, a DJ card), while library sync saves under
 * `isrc-<ISRC>` or the provider's own id. Matching by id alone added a second
 * row for a song already in the collection. Tracks are matched in tiers:
 *  1. the exact link: this provider's sync source already names the remote
 *     track's external id, or the row has the remote track's id;
 *  2. the provider's native id (Spotify track id, Apple Music catalog id),
 *     when the albums are compatible;
 *  3. the same artist and title, when the albums are compatible.
 * Every exact match is made first, so a fallback never takes a row another
 * remote track carries the exact link to. A row is matched at most once, and a
 * remote track that repeats an external id, id or native id already placed is
 * a duplicate: neither matched nor added.
 *
 * Albums are compatible when they're the same release or either is a
 * placeholder. Two real, different albums are two editions the user may have
 * saved on purpose. Desktop's tier 3 asks for a placeholder; mobile also takes
 * the same release, because desktop's ids are built from artist, title and
 * album (so its exact tier already covers that) and mobile's aren't.
 *
 * Rows already in the collection for one recording (one native id, or the
 * same artist and title, with compatible albums) are merged into one keeper.
 */
object SyncTrackMatch {

    /** A collection row and its sync sources, keyed by provider id. */
    data class LocalRow(val track: Track, val syncSources: Map<String, SyncSource>)

    data class Pair(val remote: SyncedTrack, val local: LocalRow, val viaExactLink: Boolean)

    data class Merged(val row: LocalRow, val keeperId: String)

    data class Result(
        /** Remote tracks matched to a row; `local` is the keeper after any merge. */
        val pairs: List<Pair>,
        /** Rows folded into a keeper: dropped as a merge, never as a removal. */
        val merged: List<Merged>,
        /** Ids of rows matched or merged. */
        val claimed: Set<String>,
        /** Remote tracks with no row: new rows. */
        val unmatched: List<SyncedTrack>,
        /** Remote tracks that repeat one already placed: neither matched nor added. */
        val duplicates: List<SyncedTrack>,
    )

    private val UNKNOWN_ALBUM = normalizeStr("Unknown Album")
    private val STORE_SUFFIX = Regex("""\s+-\s+(single|ep)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * An album that isn't one: nothing, "Single", "Unknown Album", or the name
     * of the row's own source (a plugin with no album naming its service).
     */
    fun isPlaceholderAlbum(track: Track): Boolean {
        val name = track.album?.trim()?.lowercase().orEmpty()
        if (name.isEmpty() || name == "single") return true
        if (albumKey(track.album) == UNKNOWN_ALBUM) return true
        val compact = name.filter { it in 'a'..'z' || it in '0'..'9' }
        if (compact.isEmpty() || compact != name.filter { it.isLetterOrDigit() }) return false
        return track.resolver?.lowercase() == compact
    }

    /** An album without the store's suffix: Apple Music's "X - Single" / "X - EP" is Spotify's "X". */
    fun albumKey(album: String?): String =
        normalizeStr(album?.trim()?.replace(STORE_SUFFIX, ""))

    fun albumsCompatible(a: Track, b: Track): Boolean {
        if (isPlaceholderAlbum(a) || isPlaceholderAlbum(b)) return true
        val ka = albumKey(a.album)
        return ka.isNotEmpty() && ka == albumKey(b.album)
    }

    private fun nameKey(t: Track): String? {
        val artist = normalizeStr(t.artist)
        val title = normalizeStr(t.title)
        return if (artist.isNotEmpty() && title.isNotEmpty()) "$artist\u0000$title" else null
    }

    /**
     * The keeper of rows for one recording: a row the user saved (no sync
     * source, or one from another provider) over one this provider's sync
     * added; then the earliest.
     */
    fun pickKeeper(rows: List<LocalRow>, providerId: String): LocalRow =
        rows.sortedWith(
            compareBy<LocalRow> { r ->
                if (r.syncSources.isEmpty() || r.syncSources.keys.any { it != providerId }) 0 else 1
            }.thenBy { r -> r.track.addedAt.takeIf { it > 0 } ?: Long.MAX_VALUE },
        ).first()

    /**
     * Matches [remote] tracks to [local] rows for [providerId]. [nativeIdOf]
     * reads the provider's native id off a track (null when it has none).
     */
    fun match(
        remote: List<SyncedTrack>,
        local: List<LocalRow>,
        providerId: String,
        nativeIdOf: (Track) -> String?,
    ): Result {
        val byId = HashMap<String, LocalRow>()
        val byExternal = HashMap<String, LocalRow>()
        val byNative = HashMap<String, MutableList<LocalRow>>()
        val byName = HashMap<String, MutableList<LocalRow>>()
        for (row in local) {
            byId.getOrPut(row.track.id) { row }
            row.syncSources[providerId]?.externalId?.takeIf { it.isNotEmpty() }?.let { byExternal.getOrPut(it) { row } }
            nativeIdOf(row.track)?.takeIf { it.isNotEmpty() }?.let { byNative.getOrPut(it) { mutableListOf() }.add(row) }
            nameKey(row.track)?.let { byName.getOrPut(it) { mutableListOf() }.add(row) }
        }

        val claimed = LinkedHashSet<String>()
        val pairs = mutableListOf<Pair>()
        val pending = mutableListOf<SyncedTrack>()
        val duplicates = mutableListOf<SyncedTrack>()
        val seenExternal = HashSet<String>()
        val seenIds = HashSet<String>()
        val seenNatives = HashSet<String>()

        fun place(r: SyncedTrack) {
            seenExternal += r.spotifyId
            seenIds += r.entity.id
            nativeIdOf(r.entity)?.takeIf { it.isNotEmpty() }?.let { seenNatives += it }
        }
        fun isDuplicate(r: SyncedTrack): Boolean =
            r.spotifyId in seenExternal || r.entity.id in seenIds ||
                (nativeIdOf(r.entity)?.let { it.isNotEmpty() && it in seenNatives } == true)

        // Pass 1: the exact link. A second remote for a claimed row is a duplicate.
        for (r in remote) {
            val row = byExternal[r.spotifyId] ?: byId[r.entity.id]
            when {
                row == null -> pending += r
                row.track.id in claimed -> duplicates += r
                else -> {
                    claimed += row.track.id
                    pairs += Pair(r, row, viaExactLink = true)
                }
            }
        }
        pairs.forEach { place(it.remote) }

        // Pass 2: the native id, then the name.
        val unmatched = mutableListOf<SyncedTrack>()
        for (r in pending) {
            val nid = nativeIdOf(r.entity)?.takeIf { it.isNotEmpty() }
            var row = nid?.let { id ->
                byNative[id]?.firstOrNull { it.track.id !in claimed && albumsCompatible(it.track, r.entity) }
            }
            if (row == null) {
                row = nameKey(r.entity)?.let { key ->
                    byName[key]?.firstOrNull { it.track.id !in claimed && albumsCompatible(it.track, r.entity) }
                }
            }
            when {
                row != null -> {
                    claimed += row.track.id
                    pairs += Pair(r, row, viaExactLink = false)
                    place(r)
                }
                isDuplicate(r) -> duplicates += r
                else -> {
                    unmatched += r
                    place(r)
                }
            }
        }

        // Heal: rows already in the collection for the same recording become one.
        val merged = mutableListOf<Merged>()
        val healed = pairs.map { pair ->
            val siblings = mutableListOf<LocalRow>()
            fun consider(candidates: List<LocalRow>?) {
                for (c in candidates.orEmpty()) {
                    if (c === pair.local || c.track.id in claimed || c in siblings) continue
                    if (nameKey(c.track) != null && nameKey(c.track) == nameKey(pair.local.track) &&
                        albumsCompatible(c.track, pair.local.track)
                    ) siblings += c
                }
            }
            listOfNotNull(nativeIdOf(pair.local.track), nativeIdOf(pair.remote.entity))
                .filter { it.isNotEmpty() }.distinct()
                .forEach { consider(byNative[it]) }
            nameKey(pair.local.track)?.let { consider(byName[it]) }
            if (siblings.isEmpty()) return@map pair
            val group = listOf(pair.local) + siblings
            val keeper = pickKeeper(group, providerId)
            for (o in group) {
                if (o === keeper) continue
                claimed += o.track.id
                merged += Merged(o, keeper.track.id)
            }
            claimed += keeper.track.id
            Pair(pair.remote, keeper, pair.viaExactLink && keeper === pair.local)
        }

        return Result(healed, merged, claimed, unmatched, duplicates)
    }
}
