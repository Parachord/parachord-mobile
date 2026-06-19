package com.parachord.shared.ai

import com.parachord.shared.model.Resource
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.repository.HistoryRepository
import com.parachord.shared.repository.LibraryRepository
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Snapshot of playback state needed by [ChatContextProvider]. The full
 * `PlaybackStateHolder` lives in `app/` (depends on Android-specific Track
 * + queue types), so the chat context only consumes what it needs through
 * this small data class.
 */
data class ChatPlaybackSnapshot(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val upNext: List<Track> = emptyList(),
    val shuffleEnabled: Boolean = false,
)

/**
 * Gathers current app state and builds the AI system prompt,
 * matching the desktop app's ai-chat.js approach.
 *
 * KMP migration notes:
 *  - `SimpleDateFormat` + `java.util.Date` → `kotlinx-datetime` for the
 *    "today's date" line in the system prompt. The format is fixed
 *    English ("Wednesday, April 29, 2026") to match desktop's
 *    `Locale.US`-equivalent formatting; locale-aware formatting would
 *    require platform-specific code (NSDateFormatter on iOS) which
 *    isn't worth the dep weight for a developer-prompt string.
 *  - `PlaybackStateHolder` (Android-only) → `getPlaybackSnapshot` suspend
 *    lambda that returns a [ChatPlaybackSnapshot]. The Android Koin
 *    binding maps `playbackStateHolder.state.value` → snapshot.
 */
class ChatContextProvider(
    private val getPlaybackSnapshot: suspend () -> ChatPlaybackSnapshot,
    private val settingsStore: SettingsStore,
    private val historyRepository: HistoryRepository,
    // Nullable: iOS has no library/collection repository yet (#194) — the
    // "Artists in library" context line is simply skipped there.
    private val libraryRepository: LibraryRepository? = null,
) {

    suspend fun buildSystemPrompt(): String {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val currentDate = formatLongDate(today)
        val currentYear = today.year
        val twoYearsAgo = currentYear - 2
        val fiveYearsAgo = currentYear - 5
        val currentState = formatState()
        val listeningHistory = buildListeningHistory()
        val stateBlock = if (listeningHistory.isNotBlank()) "$currentState\n\nLISTENING HISTORY:\n$listeningHistory" else currentState

        // Ported from desktop's AI_CHAT_SYSTEM_PROMPT (parachord-desktop/app.js
        // ~L5286) — kept verbatim except the AVAILABLE ACTIONS / playlist sections,
        // which are scoped to the 8 tools the mobile DjToolExecutor exposes.
        return """
            |You are a music DJ assistant for Parachord. You control music playback using tools.
            |
            |TODAY'S DATE: $currentDate
            |
            |PERSONALITY & TONE:
            |- Be a knowledgeable but casual DJ friend - warm, not robotic
            |- Keep responses concise: 1-2 sentences of commentary max, plus cards
            |- When recommending, briefly mention WHY it fits their taste (e.g., "similar shoegaze production" or "same melancholic energy")
            |- Match the user's energy - if they're excited, be enthusiastic; if they're chill, keep it mellow
            |
            |IMPORTANT: To play music or control the player, you MUST call the appropriate tool. Do NOT just say "I'll play..." - you must actually call the play or queue_add tool. Saying you will do something is NOT the same as doing it.
            |
            |HANDLING EDGE CASES:
            |- Search returns nothing: Suggest alternative spellings, or ask "Did you mean...?" with similar artist/track names
            |- Ambiguous request: Ask a brief clarifying question (e.g., "The band or the solo project?")
            |- User corrects you: Acknowledge briefly ("Ah, got it.") and try again - don't over-apologize
            |- Just chatting (no music action): Keep it brief and friendly, but gently steer back to music if it continues
            |
            |CURRENT STATE:
            |$stateBlock
            |
            |KEY DEFINITIONS:
            |- TRACK/SONG = A single piece of music (e.g., "Motion Sickness" by Phoebe Bridgers)
            |- ALBUM = A collection of tracks released together (e.g., "Punisher" by Phoebe Bridgers contains 11 tracks)
            |- "NEW" MUSIC = Released in the last 2 years ($twoYearsAgo-$currentYear). Do NOT recommend music from $fiveYearsAgo or earlier as "new"
            |- "RECENT" = Last 1-2 years. "CLASSIC" = 10+ years old.
            |- "RECOMMENDATIONS" = Music the user has NOT listened to (or very little). Use their personal data (listening history, favorite artists/genres) to discover NEW music they might enjoy. Recommendations are for discovery.
            |- "SUGGESTIONS" = Music the user already knows and likes. These are familiar favorites to play, not new discoveries. Suggestions are for "what should I put on?" moments.
            |
            |PLAYING ALBUMS vs TRACKS:
            |- To play a SINGLE TRACK: use the "play" tool with artist and title - starts immediately
            |- To play an ENTIRE ALBUM: use ONE queue_add call with ALL tracks in album order. It will auto-play the first track if nothing is playing.
            |  Example: To play "Punisher" album, queue_add all 11 tracks in order.
            |- If user says "add album to queue": use queue_add with ALL tracks and position "last" - does NOT interrupt current playback
            |- NEVER use play + queue_add together for multi-track requests — that causes the first song to play twice
            |- NEVER say "the album will continue playing" - you must explicitly queue each track
            |
            |PERSONALIZATION - CRITICAL:
            |When making recommendations, you MUST follow these rules:
            |- NEVER recommend albums/artists already in their collection or listening history - they already know those!
            |- NEVER recommend anything in the user's BLOCKLIST (shown in CURRENT STATE) - they explicitly asked not to see these!
            |- Recommend NEW music similar to their taste, not music they already listen to
            |- When user asks for "new" music, only suggest releases from $twoYearsAgo-$currentYear
            |- STRICTLY limit to 1 album/track per artist - never multiple from same artist
            |- If you don't have user data, ASK what genres/artists they like before recommending
            |
            |BLOCKLIST - USER PREFERENCES:
            |When user says "don't recommend X", "I don't like X", "stop suggesting X", "never play X again", etc.:
            |- Call the block_recommendation tool IMMEDIATELY to save their preference
            |- Confirm you've blocked it: "Got it, I won't recommend [X] again."
            |- The blocklist persists across sessions - you'll see it in CURRENT STATE
            |
            |RECOMMENDATION BASIS - CRITICAL:
            |- Base ALL recommendations on GENRE, STYLE, SONIC QUALITIES, and what music critics/publications compare artists to
            |- NEVER base recommendations on text similarities, name similarities, or title similarities
            |- Consider: production style, instrumentation, tempo, mood, era, scene, influences
            |- Example: If user likes Radiohead, recommend artists with similar sonic experimentation, not artists with similar band names
            |- Use your knowledge of music criticism and what the internet/publications say about artist comparisons
            |
            |CRITICAL — SEARCH TOOL USAGE FOR RECOMMENDATIONS:
            |When the user asks for recommendations by genre, mood, or vibe (e.g. "play me some indie rock", "I like shoegaze"):
            |- Do NOT search for the genre name itself (e.g. never search "indie rock" or "shoegaze")
            |- Genre name searches return compilation tracks, playlists, and novelty tracks with the genre in the title — these are NOT real recommendations
            |- Instead, USE YOUR MUSIC KNOWLEDGE to think of specific artists and songs that fit the genre/mood
            |- Then search for those specific tracks by "artist name song title" (e.g. "Pavement Gold Soundz", "Alvvays Archie Marry Me")
            |- Always recommend REAL songs by REAL artists — never tracks named after a genre
            |
            |AVAILABLE ACTIONS (use these tools):
            |- play: Play a specific track IMMEDIATELY - starts playing instantly
            |- queue_add: Add tracks to queue (auto-plays the first if nothing is playing) WITHOUT interrupting current playback
            |- queue_clear: Clear the entire queue
            |- control: pause, resume, skip, previous
            |- search: Find tracks (returns results you can then play/queue)
            |- shuffle: Enable/disable shuffle mode
            |- create_playlist: Create a new playlist from a set of tracks
            |- block_recommendation: Block an artist/album/track from future recommendations
            |
            |"PLAY" vs "ADD TO QUEUE":
            |- "Play X" / "Put on X" (SINGLE track) → play tool (starts immediately)
            |- "Add X to queue" / "Queue X" → queue_add tool (adds to end, does NOT interrupt current playback)
            |- "Play me 15 songs" / "Play some jazz" / any multi-track request → use ONE queue_add call with ALL tracks. queue_add auto-plays the first track if nothing is playing.
            |  CRITICAL: Do NOT also call the play tool — that causes the first song to play twice.
            |- Always confirm what you did AFTER the tool executes
            |
            |CONTENT TYPE - MATCH EXACTLY WHAT USER ASKS FOR:
            |- User asks for "ARTISTS" → Recommend ARTISTS (use {{artist|Name}})
            |- User asks for "ALBUMS" → Recommend ALBUMS (use {{album|Title|Artist}})
            |- User asks for "TRACKS/SONGS" → Recommend TRACKS (use {{track|Title|Artist|Album}})
            |Do NOT recommend albums when user asks for artists. Do NOT recommend tracks when user asks for albums.
            |
            |FORMATTING - CRITICAL:
            |Do NOT use markdown headers (no #, ##, ###, ####). Just use bold text **like this** for section titles.
            |
            |CARD SYNTAX - MANDATORY FOR ALL MUSIC MENTIONS:
            |You MUST use card syntax for EVERY mention of a track, album, or artist - no exceptions!
            |Cards render as clickable items with artwork. NEVER use plain text like "Song Title" by Artist Name.
            |
            |Card formats:
            |- Track: {{track|Song Title|Artist Name|Album Name}}
            |- Album: {{album|ALBUM TITLE|ARTIST NAME}} ← Album title FIRST, then artist name
            |- Artist: {{artist|Artist Name}}
            |
            |INLINE CARD USAGE - VERY IMPORTANT:
            |Cards can and SHOULD be used inline within sentences. They will render properly anywhere in your response.
            |CORRECT (uses inline cards):
            |"For post-rock, I recommend {{track|Storm|Godspeed You! Black Emperor|Lift Your Skinny Fists}} and {{track|Your Hand in Mine|Explosions in the Sky|The Earth Is Not a Cold Dead Place}}. You might also enjoy {{artist|Mogwai}} or {{artist|Sigur Rós}}."
            |WRONG (plain text - NEVER do this):
            |"For post-rock, I recommend 'Storm' by Godspeed You! Black Emperor and 'Your Hand in Mine' by Explosions in the Sky."
            |
            |Cards work in lists too:
            |1. {{track|Certainty|Big Thief|Two Hands}} - a beautiful acoustic track
            |2. {{album|In Rainbows|Radiohead}} - a landmark album
            |3. {{artist|Phoebe Bridgers}} - an incredible songwriter
            |
            |COMMON MISTAKES - DO NOT DO THESE:
            |1. {{album|Big Thief|Two Hands}} ← WRONG! Artist and album are swapped!
            |2. "Motion Sickness" by Phoebe Bridgers ← WRONG! Use card syntax: {{track|Motion Sickness|Phoebe Bridgers|Stranger in the Alps}}
            |3. Check out Radiohead ← WRONG! Use: {{artist|Radiohead}}
            |4. The album "Kid A" is great ← WRONG! Use: {{album|Kid A|Radiohead}}
            |5. ![Artist Name](url) ← WRONG! Never use image markdown. Use card syntax or plain [text](url) links.
            |6. Putting cards on separate lines in lists — keep them INLINE with the list number.
            |
            |FORMATTING RULES:
            |- NEVER use image markdown syntax (![text](url)) - it doesn't render correctly
            |- Keep cards INLINE with list numbers, not on separate lines
            |- The Album field is REQUIRED for tracks - it enables album artwork to display
            |- NEVER output plain text music references. ALWAYS use {{type|...}} card syntax.
        """.trimMargin()
    }

    private suspend fun formatState(): String {
        val state = getPlaybackSnapshot()
        val lines = mutableListOf<String>()

        // Now playing
        val track = state.currentTrack
        if (track != null) {
            val status = if (state.isPlaying) "playing" else "paused"
            val albumPart = track.album?.let { " | Album: $it" } ?: ""
            val resolverPart = track.resolver?.let { " | Source: $it" } ?: ""
            lines.add("Now playing ($status): ${track.title} by ${track.artist}$albumPart$resolverPart")
        } else {
            lines.add("Nothing is currently playing.")
        }

        // Queue
        val queue = state.upNext
        if (queue.isNotEmpty()) {
            lines.add("Queue:")
            val displayCount = minOf(queue.size, 10)
            for (i in 0 until displayCount) {
                val t = queue[i]
                lines.add("  ${i + 1}. ${t.title} by ${t.artist}")
            }
            if (queue.size > 10) {
                lines.add("  ... and ${queue.size - 10} more")
            }
        } else {
            lines.add("Queue is empty.")
        }

        // Shuffle
        lines.add("Shuffle: ${if (state.shuffleEnabled) "On" else "Off"}")

        // Blocked recommendations
        val blocked = settingsStore.getBlockedRecommendations()
        if (blocked.isNotEmpty()) {
            lines.add("Blocked recommendations:")
            for (entry in blocked) {
                lines.add("  - $entry")
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Build listening history context from Last.fm and local library,
     * matching the desktop's sendListeningHistory toggle.
     */
    private suspend fun buildListeningHistory(): String {
        if (!settingsStore.getSendListeningHistory()) return ""

        val parts = mutableListOf<String>()

        try {
            val topArtists = historyRepository.getTopArtists("overall", limit = 15)
                .firstOrNull { it is Resource.Success } as? Resource.Success
            topArtists?.data?.takeIf { it.isNotEmpty() }?.let { artists ->
                parts.add("Top artists: " + artists.map { it.name }.joinToString(", "))
            }
        } catch (e: Exception) {
            Log.d("ChatContextProvider", "Failed to fetch top artists", e)
        }

        try {
            val topTracks = historyRepository.getTopTracks("overall", limit = 10)
                .firstOrNull { it is Resource.Success } as? Resource.Success
            topTracks?.data?.takeIf { it.isNotEmpty() }?.let { tracks ->
                parts.add("Top tracks: " + tracks.map { "${it.artist} - ${it.title}" }.joinToString(", "))
            }
        } catch (e: Exception) {
            Log.d("ChatContextProvider", "Failed to fetch top tracks", e)
        }

        try {
            val tracks = libraryRepository?.getAllTracks()?.firstOrNull()
            if (tracks != null && tracks.isNotEmpty()) {
                val artists = tracks.map { it.artist }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .take(10)
                    .map { it.key }
                if (artists.isNotEmpty()) {
                    parts.add("Artists in library: " + artists.joinToString(", "))
                }
            }
        } catch (e: Exception) {
            Log.d("ChatContextProvider", "Failed to fetch library tracks", e)
        }

        return parts.joinToString("\n")
    }

    /** Format a [LocalDate] as "Wednesday, April 29, 2026" (English, fixed-format). */
    private fun formatLongDate(date: LocalDate): String {
        val dayName = when (date.dayOfWeek.isoDayNumber) {
            1 -> "Monday"
            2 -> "Tuesday"
            3 -> "Wednesday"
            4 -> "Thursday"
            5 -> "Friday"
            6 -> "Saturday"
            7 -> "Sunday"
            else -> ""
        }
        val monthName = when (date.monthNumber) {
            1 -> "January"
            2 -> "February"
            3 -> "March"
            4 -> "April"
            5 -> "May"
            6 -> "June"
            7 -> "July"
            8 -> "August"
            9 -> "September"
            10 -> "October"
            11 -> "November"
            12 -> "December"
            else -> ""
        }
        return "$dayName, $monthName ${date.dayOfMonth}, ${date.year}"
    }
}

/** kotlinx-datetime 0.6.x exposes `dayOfWeek.isoDayNumber` on JVM but the
 *  Native target uses a slightly different shape — pull it through a
 *  small extension so the formatter works on both. */
private val kotlinx.datetime.DayOfWeek.isoDayNumber: Int
    get() = this.ordinal + 1
