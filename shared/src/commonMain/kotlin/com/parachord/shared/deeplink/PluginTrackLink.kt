package com.parachord.shared.deeplink

import com.parachord.shared.model.Track
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.resolver.ResolvedSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * A track link shared or opened into the app that no built-in parser knows
 * (#502, desktop parachord#1219): the plugin whose `urlPatterns` claim it runs
 * its `lookupUrl` (resolver-loader `lookupUrl` → `{track, resolverId}`), so a
 * new service needs only its `.axe`. A soundcloud.com track link looks up even
 * without a SoundCloud sign-in (`soundcloud.axe` ≥ 1.3.0 falls back to the
 * public oEmbed endpoint).
 *
 * The track is built as desktop's `createTrackFromMeta` builds it: the link's
 * own source keeps the service's ids and names the track (title and artist,
 * confidence 1.0), so rescoring keeps it above the floor. Without a SoundCloud
 * sign-in it's the only SoundCloud source the track gets, since SoundCloud's
 * `resolve` can't search. The other resolvers then resolve the track as usual.
 */
object PluginTrackLink {

    data class Linked(
        val track: Track,
        /** The link's own source; null when the plugin returned no service ids. */
        val source: ResolvedSource?,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Look [url] up through the plugin registry; null when no plugin claims it or the lookup fails. */
    suspend fun lookup(pluginManager: PluginManager, url: String): Linked? =
        parse(pluginManager.lookupUrl(url), url)

    /** Build the track from a `{track, resolverId}` lookup result; null without a title and artist. */
    fun parse(lookupJson: String?, link: String): Linked? {
        if (lookupJson.isNullOrBlank() || lookupJson == "null") return null
        val root = try {
            json.parseToJsonElement(lookupJson).jsonObject
        } catch (_: Exception) {
            return null
        }
        val resolverId = root.text("resolverId") ?: return null
        val meta = root["track"] as? JsonObject ?: return null
        val title = meta.text("title") ?: return null
        val artist = meta.text("artist") ?: return null
        val album = meta.text("album")

        val spotifyId = meta.text("spotifyId")
        val spotifyUri = meta.text("spotifyUri")
        val appleMusicId = meta.text("appleMusicId")
        // A number in soundcloud.axe's result; kept as a string, as everywhere else.
        val soundcloudId = meta.text("soundcloudId")
        val soundcloudUrl = meta.text("soundcloudUrl")
        val serviceUrl = meta.text("url")
            ?: meta.text(resolverId.replace(Regex("[^A-Za-z0-9]"), "") + "Url")
            ?: soundcloudUrl
            ?: meta.text("appleMusicUrl")
            ?: meta.text("spotifyUrl")
        val hasServiceIds = listOf(spotifyId, spotifyUri, appleMusicId, soundcloudId, soundcloudUrl, serviceUrl)
            .any { it != null } || meta.text("youtubeId") != null || meta.text("bandcampTrackId") != null
        val artworkUrl = meta.text("albumArt")
        // Seconds in the plugin track shape.
        val durationMs = (meta["duration"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.times(1000)

        val sourceUrl = serviceUrl ?: link
        val source = if (hasServiceIds) {
            ResolvedSource(
                url = sourceUrl,
                sourceType = resolverId,
                resolver = resolverId,
                spotifyUri = spotifyUri,
                spotifyId = spotifyId,
                soundcloudId = soundcloudId,
                soundcloudUrl = soundcloudUrl,
                appleMusicId = appleMusicId,
                confidence = 1.0,
                matchedTitle = title,
                matchedArtist = artist,
                matchedDurationMs = durationMs,
                artworkUrl = artworkUrl,
            )
        } else {
            null
        }

        val idKey = "$artist-$title-${album ?: "Single"}".lowercase().replace(Regex("[^a-z0-9]"), "-")
        val track = Track(
            id = "link-$idKey",
            title = title,
            artist = artist,
            album = album,
            duration = durationMs,
            artworkUrl = artworkUrl,
            sourceType = source?.sourceType,
            sourceUrl = source?.url,
            resolver = source?.resolver,
            spotifyUri = spotifyUri,
            soundcloudId = soundcloudId,
            spotifyId = spotifyId,
            appleMusicId = appleMusicId,
        )
        return Linked(track, source)
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
