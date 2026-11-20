package com.parachord.shared.home

import com.parachord.shared.deeplink.ResolvedProtocolPlay
import com.parachord.shared.recommendations.servicePlaylistTracks
import com.parachord.shared.repository.RecommendationProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One provider's history mix as a Home card: a playlist with an id, a name and
 * the URL that opens and plays it. [title] is what the card and the Jump back
 * in tile print; [resolverId] and [resolverName] name the service. [mosaic] is
 * the 2x2 of its tracks' covers ([historyMixMosaicCovers]), for a mix that came without [imageUrl].
 */
@Serializable
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

/** One provider's mixes, as Home shows them (in provider priority order). */
data class HistoryMixEntry(
    val resolverId: String,
    val resolverName: String,
    val items: List<HistoryMix>,
)

/**
 * A provider's answer as cached (`history_mixes_cache.json`): its mixes, when
 * they were fetched, and the account they belong to (a meta-service's
 * username; `""` for a provider whose account the app can't see).
 */
@Serializable
data class HistoryMixCacheEntry(
    val resolverId: String,
    val resolverName: String,
    val items: List<HistoryMix>,
    val fetchedAt: Long,
    val account: String = "",
)

/** A failed mosaic lookup is retried [HOME_MIX_COVER_RETRY_MS] later, this many tries in all. */
const val HOME_MIX_COVER_ATTEMPTS = 3
const val HOME_MIX_COVER_RETRY_MS = 60_000L

private val mixJson = Json { ignoreUnknownKeys = true }

private fun JsonElement?.trimmedString(): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

/**
 * One provider's `getHistoryMixes` answer (a JSON array) as Home's mix cards:
 * playlists with an id, a name and a URL. Anything else (another type, a
 * missing field, a non-array answer) is dropped. A provider with no name is
 * named by its id. Desktop's `normalizeHistoryMixes`.
 */
fun normalizeHistoryMixes(itemsJson: String, providerId: String, providerName: String): List<HistoryMix> {
    val array = try {
        mixJson.parseToJsonElement(itemsJson) as? JsonArray
    } catch (e: Exception) {
        null
    } ?: return emptyList()
    val resolverName = providerName.ifBlank { providerId }
    val out = mutableListOf<HistoryMix>()
    for (raw in array) {
        val obj = raw as? JsonObject ?: continue
        val type = obj["type"]
        if (type != null && type !is JsonNull && (type as? JsonPrimitive)?.content != "playlist") continue
        val id = obj["id"].trimmedString()
        val name = obj["name"].trimmedString()
        val url = obj["url"].trimmedString()
        if (id.isEmpty() || name.isEmpty() || url.isEmpty()) continue
        out += HistoryMix(
            id = id,
            name = name,
            title = name,
            description = obj["description"].trimmedString().ifEmpty { null },
            imageUrl = obj["imageUrl"].trimmedString().ifEmpty { null },
            url = url,
            resolverId = providerId,
            resolverName = resolverName,
        )
    }
    return out
}

/**
 * Every provider's mixes as one list: round-robin in provider order (the first
 * of each, then the second of each, …), so one service with many mixes doesn't
 * fill the row first. An id seen once is kept where it first appeared.
 */
fun flattenHistoryMixes(entries: List<HistoryMixEntry>): List<HistoryMix> {
    val lists = entries.map { it.items }
    val longest = lists.maxOfOrNull { it.size } ?: 0
    val out = mutableListOf<HistoryMix>()
    val seen = mutableSetOf<String>()
    for (i in 0 until longest) {
        for (list in lists) {
            val mix = list.getOrNull(i) ?: continue
            if (!seen.add(mix.id)) continue
            out += mix
        }
    }
    return out
}

/** A cached entry, or null when it was saved for another account (a changed ListenBrainz user never sees the old one's mixes). */
fun historyMixCacheEntry(entry: HistoryMixCacheEntry?, account: String?): HistoryMixCacheEntry? {
    if (entry == null) return null
    return if (entry.account == account.orEmpty()) entry else null
}

/** The entries Home shows, in provider priority order: one per provider whose answer has mixes. */
fun historyMixEntriesFor(
    providers: List<RecommendationProvider>,
    entryFor: (RecommendationProvider) -> HistoryMixCacheEntry?,
): List<HistoryMixEntry> = providers.mapNotNull { p ->
    val entry = entryFor(p) ?: return@mapNotNull null
    if (entry.items.isEmpty()) return@mapNotNull null
    HistoryMixEntry(p.id, p.name.ifBlank { p.id }, entry.items)
}

/**
 * The mixes still needing a mosaic: no cover of their own (ListenBrainz sends
 * none), none built yet, and not [tried] this session.
 */
fun mixesNeedingMosaic(mixes: List<HistoryMix>, tried: Set<String>): List<HistoryMix> =
    mixes.filter { it.id.isNotEmpty() && it.url.isNotEmpty() && it.imageUrl.isNullOrEmpty() && it.mosaic.isEmpty() && it.id !in tried }

/** Attaches the [covers] found (first four) as each mix's mosaic; a mix with none is unchanged, and no covers returns [mixes] itself. */
fun withMixMosaics(mixes: List<HistoryMix>, covers: Map<String, List<String>>): List<HistoryMix> {
    if (covers.isEmpty()) return mixes
    return mixes.map { m ->
        val c = covers[m.id]
        if (c.isNullOrEmpty()) m else m.copy(mosaic = c.take(4))
    }
}

/** One track as [lookupPlaylistCovers] reads it. */
data class CoverTrack(val artist: String?, val album: String?, val artworkUrl: String?)

/**
 * Up to four covers for a playlist's 2x2, a port of desktop's
 * `getPlaylistCovers` (`app.js`), without its cache. Covers are distinct by
 * album and by URL. A track's own cover is used; one without looks up its
 * album's cover ([albumArt], once per album), and a track with no album cover
 * falls back to its artist's photo ([artistImage], once per artist). A second
 * pass adds artist photos while fewer than four were found. A lookup that
 * fails counts as none (desktop's `getAlbumArt` answers null).
 *
 * One difference: desktop only reads a track's own cover when the track names
 * its album; here a cover the track already carries is always used, which saves
 * an artist lookup.
 */
suspend fun lookupPlaylistCovers(
    tracks: List<CoverTrack>,
    albumArt: suspend (album: String, artist: String) -> String?,
    artistImage: suspend (artist: String) -> String?,
): List<String> {
    val seenAlbums = mutableSetOf<String>()
    val seenUrls = mutableSetOf<String>()
    val seenArtists = mutableSetOf<String>()
    val triedArtists = mutableSetOf<String>()
    val covers = mutableListOf<String>()

    suspend fun safely(block: suspend () -> String?): String? = try {
        block()?.takeIf { it.isNotBlank() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    fun add(url: String, artist: String?): Boolean {
        if (url in seenUrls) return false
        seenUrls += url
        covers += url
        artist?.takeIf { it.isNotBlank() }?.let { seenArtists += it.lowercase() }
        return true
    }

    suspend fun tryArtist(artist: String?) {
        val name = artist?.trim().orEmpty()
        if (name.isEmpty()) return
        val key = name.lowercase()
        if (key in seenArtists || key in triedArtists) return
        triedArtists += key
        safely { artistImage(name) }?.let { add(it, name) }
    }

    for (t in tracks) {
        if (covers.size >= 4) break
        val artist = t.artist?.trim().orEmpty()
        val album = t.album?.trim().orEmpty()
        val own = t.artworkUrl?.takeIf { it.isNotBlank() }
        var found = false
        if (album.isNotEmpty() && artist.isNotEmpty()) {
            val albumKey = "$artist-$album".lowercase()
            if (seenAlbums.add(albumKey)) {
                val url = own ?: safely { albumArt(album, artist) }
                if (url != null) found = add(url, artist)
            }
        } else if (own != null) {
            found = add(own, artist)
        }
        if (!found) tryArtist(artist)
    }
    if (covers.size < 4) {
        for (t in tracks) {
            if (covers.size >= 4) break
            tryArtist(t.artist)
        }
    }
    return covers.take(4)
}

/**
 * A mix's mosaic: up to four covers for the tracks [resolve] (the registry's
 * playlist-link lookup, the one Play uses) finds at its URL, through
 * [lookupPlaylistCovers] (desktop's `getPlaylistCovers(mix.id, tracks)`):
 * ListenBrainz's mixes carry no covers, so [albumArt] and [artistImage] fill
 * them. Both apps' `mosaicCovers`. A lookup that finds nothing throws (the link
 * lookup answers null when it couldn't be reached, often in the first seconds
 * after launch), so the repository retries it.
 */
suspend fun historyMixMosaicCovers(
    mix: HistoryMix,
    albumArt: suspend (album: String, artist: String) -> String? = { _, _ -> null },
    artistImage: suspend (artist: String) -> String? = { null },
    resolve: suspend (String) -> ResolvedProtocolPlay?,
): List<String> {
    val play = resolve(mix.url) ?: throw IllegalStateException("No playlist found for history mix ${mix.id}")
    val tracks = servicePlaylistTracks(play).map { CoverTrack(it.artist, it.album, it.artworkUrl) }
    return lookupPlaylistCovers(tracks, albumArt, artistImage)
}
