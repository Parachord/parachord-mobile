package com.parachord.shared.recommendations

import com.parachord.shared.deeplink.ResolvedProtocolPlay
import com.parachord.shared.deeplink.isValidMbid
import com.parachord.shared.model.Track
import com.parachord.shared.playback.CatalogQueue
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.playlist.protocolPlayTrack
import com.parachord.shared.resolver.validateIsrc
import io.ktor.http.encodeURLParameter
import kotlin.coroutines.cancellation.CancellationException

/* What a tap on a Recommendations card plays. Both apps call these. */

/** A plugin's seconds as the player's milliseconds; null when unknown or not positive. */
internal fun RecommendationItem.durationMs(): Long? =
    duration?.let { (it * 1000).toLong() }?.takeIf { it > 0 }

/** The recording MBID when well-formed, lowercased. */
internal fun RecommendationItem.validRecordingMbid(): String? =
    mbid?.lowercase()?.takeIf { isValidMbid(it) }

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
    val tracks = rowItems.mapNotNull { recommendedSongTrack(it) }
    val tapped = rowItems.getOrNull(index)?.let { recommendedSongTrack(it) } ?: return null
    val start = rowItems.take(index).count { recommendedSongTrack(it) != null }
    check(tracks.getOrNull(start)?.id == tapped.id)
    return CatalogQueue(
        tracks.drop(start),
        PlaybackContext("recommendations", row.title ?: "Recommendations", row.key),
    )
}

fun weeklyContext(weekly: WeeklyInfo): PlaybackContext =
    PlaybackContext(weekly.contextType, "${weekly.weekLabel}'s ${weekly.label}", weekly.id)

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
    val provider = play.providerId ?: "link"
    val tracks = play.tracks.mapIndexed { i, t -> protocolPlayTrack(t, provider, i) }
    return CatalogQueue(tracks, PlaybackContext("playlist", item.name, url))
}

sealed class ListenerPlay {
    data class Play(val queue: CatalogQueue) : ListenerPlay()
    data class Message(val text: String) : ListenerPlay()
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

/** A listener's page on Achordion; the name is escaped like desktop's `encodeURIComponent`. */
fun achordionUserUrl(username: String): String =
    "https://achordion.xyz/user/" + username.encodeURLParameter()
