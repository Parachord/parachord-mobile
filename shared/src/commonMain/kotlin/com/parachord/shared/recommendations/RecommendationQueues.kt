package com.parachord.shared.recommendations

import com.parachord.shared.api.LbPlaylistTrack
import com.parachord.shared.deeplink.ResolvedProtocolPlay
import com.parachord.shared.deeplink.isValidMbid
import com.parachord.shared.metadata.PlaylistMosaicPaths
import com.parachord.shared.model.Track
import com.parachord.shared.playback.CatalogQueue
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.playback.SERVICE_PLAYLIST_CONTEXT
import com.parachord.shared.playlist.normalizePlaylistUrl
import com.parachord.shared.playlist.protocolPlayTrack
import com.parachord.shared.resolver.validateIsrc
import io.ktor.http.encodeURLParameter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/* What a tap on a Recommendations card plays. Both apps call these. */

/** A plugin's seconds as the player's milliseconds; null when unknown or not positive. */
internal fun RecommendationItem.durationMs(): Long? =
    duration?.let { (it * 1000).toLong() }?.takeIf { it > 0 }

/** The recording MBID when well-formed, lowercased. */
internal fun RecommendationItem.validRecordingMbid(): String? =
    mbid?.lowercase()?.takeIf { isValidMbid(it) }

/** The playback context type of a queue started from a Recommendations song row (its id is the row's key). */
const val RECOMMENDATIONS_CONTEXT = "recommendations"

/** A recommended song as a metadata-only track; null unless it's a song with an artist. */
fun recommendedSongTrack(item: RecommendationItem): Track? {
    if (item.type != RecommendationType.SONG) return null
    val artist = item.artist?.takeIf { it.isNotBlank() } ?: return null
    return Track(
        id = "rec-" + trackSlug(artist, item.name),
        title = item.name,
        artist = artist,
        duration = item.durationMs(),
        artworkUrl = item.imageUrl,
        recordingMbid = item.validRecordingMbid(),
        isrc = validateIsrc(item.isrc),
    )
}

/** The tapped song and every song after it in [rowItems] (songs with no artist are left out). */
fun songQueue(rowItems: List<RecommendationItem>, index: Int, row: RecommendationRow): CatalogQueue? {
    if (rowItems.getOrNull(index)?.let { recommendedSongTrack(it) } == null) return null
    val tracks = rowItems.drop(index).mapNotNull { recommendedSongTrack(it) }
    return CatalogQueue(
        tracks,
        PlaybackContext(RECOMMENDATIONS_CONTEXT, row.title ?: RecommendationCopy.TITLE, row.key),
    )
}

fun weeklyContext(weekly: WeeklyInfo): PlaybackContext =
    PlaybackContext(weekly.contextType, "${weekly.weekLabel}'s ${weekly.label}", weekly.id)

/** A weekly playlist's tracks as metadata-only tracks, in order; ids are `weekly-<playlist>-<track>`. */
fun weeklyTracks(weekly: WeeklyInfo, tracks: List<LbPlaylistTrack>): List<Track> = tracks.map {
    Track(
        id = "weekly-${weekly.id}-${it.id}",
        title = it.title,
        artist = it.artist,
        album = it.album,
        duration = it.durationMs,
        artworkUrl = it.albumArt,
        recordingMbid = it.mbid,
    )
}

/** A playlist's mosaic: its first 4 distinct covers, blanks skipped (the saved mosaic's rule, [PlaylistMosaicPaths.covers]). */
fun playlistCovers(artworks: List<String?>): List<String> = PlaylistMosaicPaths.covers(artworks)

/**
 * Weekly playlist cards' mosaics, loaded once per playlist id through [loadArtwork]
 * (its tracks' covers) on [scope]. A load that found no covers or failed isn't kept,
 * so the next [covers] call for that id asks again.
 */
class WeeklyCovers(
    private val scope: CoroutineScope,
    private val loadArtwork: suspend (playlistId: String) -> List<String?>,
) {
    private val mutex = Mutex()
    private val flows = mutableMapOf<String, MutableStateFlow<List<String>>>()
    /** Ids loading or loaded with covers. */
    private val started = mutableSetOf<String>()

    /** The playlist's covers (empty until they load); starts a load unless one ran and found covers. */
    fun covers(playlistId: String): StateFlow<List<String>> {
        val flow = flows.getOrPut(playlistId) { MutableStateFlow(emptyList()) }
        scope.launch {
            val go = mutex.withLock { started.add(playlistId) }
            if (!go) return@launch
            val found = try {
                playlistCovers(loadArtwork(playlistId))
            } catch (e: CancellationException) {
                mutex.withLock { started.remove(playlistId) }
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (found.isEmpty()) mutex.withLock { started.remove(playlistId) }
            flow.value = found
        }
        return flow
    }
}

/**
 * What a tap on a (non-weekly) recommended playlist card does, as desktop: a card
 * with a link opens the playlist view for it (`openServicePlaylist`); one without a
 * link has no view to open, so the tap plays it ([servicePlaylistQueue]).
 */
fun playlistTapOpensView(item: RecommendationItem): Boolean =
    item.type == RecommendationType.PLAYLIST && item.weekly == null && !item.url.isNullOrBlank()

/**
 * The key a recommended service playlist is saved under ([com.parachord.shared.playlist.PlaylistUrlImporter]'s
 * `savedKey`), so saving it again, on a later visit or from Now Playing's "Playing from",
 * opens the first copy. Desktop keys its view by `<resolverId>-rec-<itemId>`; the link is
 * used here because the view can also be opened from a queue's context, which carries
 * only the link. A fingerprint of the link (FNV-1a, the same on every platform).
 */
fun recommendedPlaylistKey(url: String): String =
    "rec-" + PlaylistMosaicPaths.coverSignature(listOf(normalizePlaylistUrl(url)))

/** The context of a queue played from an unsaved service playlist: its id is the link. */
fun servicePlaylistContext(name: String, url: String): PlaybackContext =
    PlaybackContext(SERVICE_PLAYLIST_CONTEXT, name, url)

/** A resolved service playlist's tracks, in order; the playlist view and [servicePlaylistQueue] both use it. */
fun servicePlaylistTracks(play: ResolvedProtocolPlay): List<Track> {
    val provider = play.providerId ?: "link"
    return play.tracks.mapIndexed { i, t -> protocolPlayTrack(t, provider, i) }
}

/** A service playlist's tracks through [resolve] (the registry's link lookup); null when it can't be played. */
suspend fun servicePlaylistQueue(
    item: RecommendationItem,
    resolve: suspend (String) -> ResolvedProtocolPlay?,
): CatalogQueue? {
    val url = item.url ?: return null
    val play = try {
        resolve(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } ?: return null
    if (play.tracks.isEmpty()) return null
    return CatalogQueue(servicePlaylistTracks(play), servicePlaylistContext(item.name, url))
}

sealed class ListenerPlay {
    data class Play(val queue: CatalogQueue) : ListenerPlay()
    data class Message(val text: String) : ListenerPlay()
}

/** What playing any [RecommendationItem] resolves to (shared by phone and car). */
sealed class RecommendationPlay {
    data class Play(val queue: CatalogQueue) : RecommendationPlay()
    data class Message(val text: String) : RecommendationPlay()
}

/**
 * Resolves a play action on any [RecommendationItem] (`SONG`, `ALBUM`, `ARTIST`,
 * weekly `PLAYLIST`, or service `PLAYLIST`) into a playable [CatalogQueue] or a
 * user-facing message. Shared by the phone Recommendations screen and the car
 * For You tab so both follow the exact same per-type play rules.
 */
suspend fun recommendationItemPlay(
    row: RecommendationRow,
    item: RecommendationItem,
    rowItems: List<RecommendationItem>,
    albumQueue: suspend (title: String, artist: String) -> CatalogQueue?,
    artistTopSongs: suspend (artist: String) -> CatalogQueue?,
    weeklyTracksLoader: suspend (playlistId: String) -> List<LbPlaylistTrack>,
    servicePlaylistResolver: suspend (url: String) -> ResolvedProtocolPlay?,
): RecommendationPlay = when (item.type) {
    RecommendationType.SONG -> {
        val index = rowItems.indexOf(item)
        val queue = if (index >= 0) songQueue(rowItems, index, row) else songQueue(listOf(item), 0, row)
        queue?.let { RecommendationPlay.Play(it) }
            ?: RecommendationPlay.Message(RecommendationCopy.couldntLoad(item.name))
    }
    RecommendationType.ALBUM -> {
        val queue = try {
            albumQueue(item.name, item.artist.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        queue?.let { RecommendationPlay.Play(it) }
            ?: RecommendationPlay.Message(RecommendationCopy.couldntLoad(item.name))
    }
    RecommendationType.ARTIST -> {
        val queue = try {
            artistTopSongs(item.name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        queue?.let { RecommendationPlay.Play(it) }
            ?: RecommendationPlay.Message(RecommendationCopy.couldntLoad(item.name))
    }
    RecommendationType.PLAYLIST -> {
        val weekly = item.weekly
        if (weekly != null) {
            val tracks = try {
                weeklyTracks(weekly, weeklyTracksLoader(weekly.id))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (tracks.isEmpty()) {
                RecommendationPlay.Message(RecommendationCopy.couldntLoad(item.name))
            } else {
                RecommendationPlay.Play(CatalogQueue(tracks, weeklyContext(weekly)))
            }
        } else {
            val queue = servicePlaylistQueue(item, servicePlaylistResolver)
            queue?.let { RecommendationPlay.Play(it) }
                ?: RecommendationPlay.Message(RecommendationCopy.couldntLoad(item.name))
        }
    }
}

/** A listener's top tracks as a queue, or the message to show when there are none or they couldn't load. */
suspend fun listenerPlay(
    service: String,
    username: String,
    name: String,
    topTracks: suspend (String, String) -> List<Track>,
): ListenerPlay {
    val tracks = try {
        topTracks(service, username)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ListenerPlay.Message(RecommendationCopy.listenerTracksFailed(name))
    }
    if (tracks.isEmpty()) return ListenerPlay.Message(RecommendationCopy.listenerNoTracks(name))
    return ListenerPlay.Play(CatalogQueue(tracks, PlaybackContext("friend", name, "$service:$username")))
}

/** Achordion has user pages for ListenBrainz accounts only: the Listener menu's "View on Achordion". */
fun showsAchordionLink(service: String): Boolean = service == "listenbrainz"

/** A listener's page on Achordion; the name is escaped like desktop's `encodeURIComponent`. */
fun achordionUserUrl(username: String): String =
    "https://achordion.xyz/user/" + username.encodeURLParameter()
