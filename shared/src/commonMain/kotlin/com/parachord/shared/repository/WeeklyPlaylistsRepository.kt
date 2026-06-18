package com.parachord.shared.repository

import com.parachord.shared.api.LbCreatedForPlaylist
import com.parachord.shared.api.LbPlaylistTrack
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.settings.SettingsStore
import kotlin.concurrent.Volatile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Repository for ListenBrainz Weekly Jams and Weekly Exploration playlists.
 * Matches the desktop's `loadWeeklyPlaylists` + `loadWeeklyJamTracks` pattern.
 *
 * Desktop fetches `/1/user/{username}/playlists/createdfor?count=100`, filters by title
 * containing "weekly jams" or "weekly exploration", sorts by date descending, and takes
 * the most recent 4 of each type. Tracks are loaded lazily per playlist.
 *
 * Cache is in-memory with a 1h TTL. Singleton-scoped — survives ViewModel
 * lifecycles, so callers should pass `forceRefresh = true` from `init`
 * when they want fresh data on screen entry.
 */
class WeeklyPlaylistsRepository(
    private val listenBrainzClient: ListenBrainzClient,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "WeeklyPlaylistsRepo"
        private const val MAX_WEEKS = 4
        private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour
    }

    @Volatile
    private var cachedResult: WeeklyPlaylistsResult? = null
    @Volatile
    private var cacheTimestamp: Long = 0L

    /**
     * Load Weekly Jams and Weekly Exploration playlists from ListenBrainz.
     * Returns null if ListenBrainz username is not configured.
     */
    suspend fun loadWeeklyPlaylists(forceRefresh: Boolean = false): WeeklyPlaylistsResult? {
        val cacheAge = currentTimeMillis() - cacheTimestamp
        if (!forceRefresh && cacheAge < CACHE_TTL_MS) cachedResult?.let { return it }

        val username = settingsStore.getListenBrainzUsername() ?: return null

        return try {
            val allPlaylists = listenBrainzClient.getCreatedForPlaylists(username)

            val jamsPlaylists = allPlaylists
                .filter { it.title.contains("weekly jams", ignoreCase = true) }
                .sortedByDescending { it.date }
                .take(MAX_WEEKS)

            val explorationPlaylists = allPlaylists
                .filter { it.title.contains("weekly exploration", ignoreCase = true) }
                .sortedByDescending { it.date }
                .take(MAX_WEEKS)

            val weekLabels = listOf("This Week", "Last Week", "2 Weeks Ago", "3 Weeks Ago")

            fun buildEntries(playlists: List<LbCreatedForPlaylist>, defaultDesc: String) =
                playlists.mapIndexed { i, p ->
                    WeeklyPlaylistEntry(
                        id = p.id,
                        title = p.title,
                        weekLabel = weekLabels.getOrElse(i) { "${i} Weeks Ago" },
                        description = stripHtml(p.annotation).ifBlank { defaultDesc },
                        date = p.date,
                        dateLabel = formatWeeklyPlaylistDate(p.date),
                    )
                }

            val jams = buildEntries(jamsPlaylists,
                "Your favorite tracks from the past week, curated by ListenBrainz")
            val exploration = buildEntries(explorationPlaylists,
                "New discoveries based on your listening habits, curated by ListenBrainz")

            val result = WeeklyPlaylistsResult(
                jams = jams.ifEmpty { null },
                exploration = exploration.ifEmpty { null },
            )
            cachedResult = result
            cacheTimestamp = currentTimeMillis()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load weekly playlists", e)
            null
        }
    }

    /**
     * Load tracks for a specific weekly playlist and extract cover art URLs.
     */
    suspend fun loadPlaylistTracks(playlistId: String): List<LbPlaylistTrack> {
        return try {
            listenBrainzClient.getPlaylistTracksRich(playlistId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tracks for playlist $playlistId", e)
            emptyList()
        }
    }

    /**
     * Load cover art URLs for a playlist (up to 4, for the 2x2 mosaic).
     */
    suspend fun loadPlaylistCovers(playlistId: String): List<String> {
        val tracks = loadPlaylistTracks(playlistId)
        return tracks.mapNotNull { it.albumArt }.distinct().take(4)
    }

    /**
     * Load covers for all entries in parallel.
     */
    suspend fun loadAllCovers(
        entries: List<WeeklyPlaylistEntry>,
    ): Map<String, List<String>> = coroutineScope {
        val deferred = entries.map { entry ->
            async {
                entry.id to loadPlaylistCovers(entry.id)
            }
        }
        deferred.associate { it.await() }
    }

    fun clearCache() {
        cachedResult = null
        cacheTimestamp = 0L
    }
}

/** Combined result of Weekly Jams + Weekly Exploration. */
data class WeeklyPlaylistsResult(
    val jams: List<WeeklyPlaylistEntry>?,
    val exploration: List<WeeklyPlaylistEntry>?,
) {
    val isEmpty: Boolean get() = jams.isNullOrEmpty() && exploration.isNullOrEmpty()
}

/** A single week's playlist entry (e.g. "This Week's Weekly Jams"). */
data class WeeklyPlaylistEntry(
    val id: String,             // Playlist MBID
    val title: String,
    val weekLabel: String,      // "This Week", "Last Week", etc. (used on carousel cards)
    val description: String,
    val date: String,           // raw LB createdfor date (ISO)
    /** [date] formatted "Jun 15, 2026" — shown on the playlist detail header
     *  (matches desktop). Empty when [date] is missing/unparseable. */
    val dateLabel: String = "",
)

/**
 * Strip HTML tags + decode the few entities that show up in LB playlist
 * annotations (`<p>…</p>`, `<a>…</a>`, `&amp;`, …) so the description reads
 * clean on both platforms. Done in the shared repo so neither client renders
 * raw markup.
 */
internal fun stripHtml(raw: String): String =
    raw.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

/** Format an LB playlist date (ISO, optionally with a time suffix) as "Jun 15, 2026". */
internal fun formatWeeklyPlaylistDate(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val d = kotlinx.datetime.LocalDate.parse(raw.take(10))
        val m = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        "${m[d.monthNumber - 1]} ${d.dayOfMonth}, ${d.year}"
    } catch (e: Exception) {
        ""
    }
}
