package com.parachord.shared.metadata

import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The pure parts of the artist-photo chain [MetadataService.getArtistImage]
 * runs. Port of desktop's `src/renderer/lib/artist-image-chain.js`
 * (parachord#1141): which URLs may be cached and shown, reading Deezer's
 * public artist search, and remembering artists no source had a photo of.
 */
object ArtistImageChain {

    /** Each step is bounded: one slow source must not hold every photo behind it. */
    const val STEP_TIMEOUT_MS = 8_000L

    /** How long "no source has a photo of this artist" is remembered. */
    const val MISS_TTL_MS = 6L * 60 * 60 * 1000

    /**
     * Last.fm's grey-star placeholder, which Last.fm and servers relaying its
     * images hand back for artists it has no photo of.
     */
    private const val LASTFM_PLACEHOLDER_HASH = "2a96cbd8b46e442fc41c2b86b821562f"

    // Query parameter names that carry credentials (desktop's
    // discord-presence-logic `looksLikeCredentialBearingUrl`). The image cache
    // is persisted, so a URL that embeds a token (a Subsonic getCoverArt URL
    // carries the user's auth token and salt) must never reach it.
    private val CREDENTIAL_PARAM = Regex(
        "(token|secret|passwd|password|pwd|apikey|api_key|key|signature|sig|auth|cred|session|sid|cert|nonce|jwt)",
        RegexOption.IGNORE_CASE,
    )
    private val CREDENTIAL_PARAM_EXACT = setOf("t", "s", "u", "p")
    private val SCHEME_AND_HOST = Regex("^https?://([^/?#]*)", RegexOption.IGNORE_CASE)

    /**
     * An http(s) URL that can go into the image cache and an image view: no
     * credentials in it and not a known placeholder.
     */
    fun isUsableUrl(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        val authority = SCHEME_AND_HOST.find(u)?.groupValues?.get(1) ?: return false
        if (authority.isEmpty() || '@' in authority) return false
        if (LASTFM_PLACEHOLDER_HASH in u) return false
        val query = u.substringAfter('?', "").substringBefore('#')
        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                val name = pair.substringBefore('=').lowercase()
                if (name in CREDENTIAL_PARAM_EXACT || CREDENTIAL_PARAM.containsMatchIn(name)) return false
            }
        }
        return true
    }

    /**
     * The photo for [name] from a Deezer `/search/artist` response, or null.
     * Only an artist whose name matches ([AlbumCatalog.normalizeArtistName]:
     * case, marks, a leading "The", "&") counts: Deezer's first result for an
     * obscure name is often someone else. An artist without a photo has an
     * empty `md5_image`, and its picture URLs point at Deezer's blank
     * placeholder (".../artist//...").
     */
    fun deezerImageFromSearch(response: JsonObject?, name: String): String? {
        val wanted = AlbumCatalog.normalizeArtistName(name)
        if (wanted.isEmpty()) return null
        val artists = response?.get("data") as? JsonArray ?: return null
        for (el in artists) {
            val a = el as? JsonObject ?: continue
            if (AlbumCatalog.normalizeArtistName(a.str("name")) != wanted) continue
            val url = a.str("picture_xl") ?: a.str("picture_big") ?: a.str("picture_medium")
            if (url == null || a.str("md5_image") == "" || "/artist//" in url) return null
            return url.takeIf { isUsableUrl(it) }
        }
        return null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    /** Artists no source had a photo of, keyed by normalized name. */
    class MissCache(
        private val ttlMs: Long = MISS_TTL_MS,
        private val nowMs: () -> Long = { currentTimeMillis() },
    ) {
        private val mutex = Mutex()
        private val misses = mutableMapOf<String, Long>()

        private fun keyOf(name: String) = AlbumCatalog.normalizeArtistName(name)

        suspend fun has(name: String): Boolean = mutex.withLock {
            val key = keyOf(name)
            val at = misses[key] ?: return@withLock false
            if (nowMs() - at > ttlMs) {
                misses.remove(key)
                false
            } else true
        }

        suspend fun add(name: String) = mutex.withLock {
            val key = keyOf(name)
            if (key.isNotEmpty()) misses[key] = nowMs()
        }

        /** A new sign-in can find photos the earlier misses didn't have. */
        suspend fun clear() = mutex.withLock { misses.clear() }
    }
}
