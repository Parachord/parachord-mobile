package com.parachord.shared.playlist

import com.parachord.shared.model.Track

/** Parsed XSPF playlist: a title, optional creator, and the tracks. */
data class XspfPlaylist(
    val title: String,
    val creator: String?,
    val tracks: List<Track>,
)

/**
 * Pure-Kotlin (commonMain) XSPF parser — the KMP-shared analogue of Android's
 * `XmlPullParser`-based `XspfParser`. XSPF is a flat, well-known schema
 * (`playlist > title|creator > trackList > track > title|creator|album|duration|location`),
 * so a small hand-rolled scanner avoids pulling a multiplatform XML dependency
 * and gives byte-identical output on Android + iOS.
 *
 * Security (H10): we never resolve external/custom entities (only the five
 * predefined ones + numeric character refs), and we reject any DOCTYPE/ENTITY
 * declaration outright — so billion-laughs / XXE can't apply — plus a 10 MiB
 * input cap. Mirrors the Android parser's hardening.
 *
 * Desktop equivalent: `app.js parseXSPF()`.
 */
object XspfParser {
    private const val MAX_BYTES = 10 * 1024 * 1024  // ~100x a realistic XSPF

    fun parse(content: String): XspfPlaylist {
        require(content.length <= MAX_BYTES) { "XSPF content too large (${content.length} bytes)" }
        if (content.contains("<!DOCTYPE", ignoreCase = true) ||
            content.contains("<!ENTITY", ignoreCase = true)
        ) {
            throw IllegalArgumentException("XSPF must not contain a DOCTYPE/ENTITY declaration")
        }

        // Playlist-level title/creator live BEFORE the first <track>.
        val firstTrack = indexOfElement(content, "track", 0)
        val header = if (firstTrack >= 0) content.substring(0, firstTrack) else content
        val playlistTitle = tagText(header, "title") ?: "Imported Playlist"
        val playlistCreator = tagText(header, "creator")

        val tracks = mutableListOf<Track>()
        var i = firstTrack
        while (i >= 0) {
            val openEnd = content.indexOf('>', i)
            if (openEnd < 0) break
            val closeStart = indexOfClose(content, "track", openEnd)
            if (closeStart < 0) break
            val block = content.substring(openEnd + 1, closeStart)

            val title = tagText(block, "title") ?: "Unknown"
            val artist = tagText(block, "creator") ?: "Unknown"
            val album = tagText(block, "album")
            val duration = tagText(block, "duration")?.toLongOrNull()
            val location = tagText(block, "location")
            tracks.add(
                Track(
                    id = "xspf:$artist:$title:${tracks.size}",
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    sourceUrl = location,
                    sourceType = if (location != null) "stream" else null,
                ),
            )
            i = indexOfElement(content, "track", closeStart + 1)
        }
        return XspfPlaylist(playlistTitle, playlistCreator, tracks)
    }

    /** Index of the next `<name>`/`<name ...>`/`<name/>` open tag at or after [from],
     *  rejecting longer names (`<trackList>` must not match `track`). -1 if none. */
    private fun indexOfElement(s: String, name: String, from: Int): Int {
        var idx = s.indexOf("<$name", from, ignoreCase = true)
        while (idx >= 0) {
            val after = idx + 1 + name.length
            val c = if (after < s.length) s[after] else ' '
            if (c == '>' || c == '/' || c.isWhitespace()) return idx
            idx = s.indexOf("<$name", idx + 1, ignoreCase = true)
        }
        return -1
    }

    /** Index of the matching `</name>` close tag at or after [from], or -1. */
    private fun indexOfClose(s: String, name: String, from: Int): Int =
        s.indexOf("</$name>", from, ignoreCase = true)

    /** First `<tag>…</tag>` (or `<tag …>…</tag>`) text in [region], entity-decoded
     *  and trimmed, or null when absent/empty. */
    private fun tagText(region: String, tag: String): String? {
        val open = indexOfElement(region, tag, 0)
        if (open < 0) return null
        val openEnd = region.indexOf('>', open)
        if (openEnd < 0 || region.getOrNull(openEnd - 1) == '/') return null  // self-closed → no text
        val close = indexOfClose(region, tag, openEnd)
        if (close < 0) return null
        val raw = region.substring(openEnd + 1, close)
        val decoded = decodeEntities(raw).trim()
        return decoded.ifEmpty { null }
    }

    /** Decode the five predefined XML entities + numeric character references.
     *  `&amp;` is decoded LAST so `&amp;lt;` round-trips to the literal `&lt;`. */
    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        var r = s
        if ("&#" in r) {
            r = Regex("&#(x?)([0-9A-Fa-f]+);").replace(r) { m ->
                val code = if (m.groupValues[1].isEmpty()) m.groupValues[2].toIntOrNull()
                           else m.groupValues[2].toIntOrNull(16)
                if (code != null && code in 1..0x10FFFF) {
                    try { code.toChar().toString() } catch (_: Exception) { m.value }
                } else m.value
            }
        }
        return r.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
