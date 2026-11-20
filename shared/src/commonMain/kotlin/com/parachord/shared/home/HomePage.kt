package com.parachord.shared.home

import com.parachord.shared.repository.ConcertEvent
import kotlinx.datetime.LocalDate

/** Cards in a full-width Home row (desktop `HOME_ROW_CARDS`). */
const val HOME_ROW_CARDS = 6

/** Cards in each half of the Fresh Drops / Critical Darlings row (`HOME_HALF_ROW_CARDS`). */
const val HOME_HALF_ROW_CARDS = 3

/** Concert cards on Home (`HOME_CONCERT_CARDS`). */
const val HOME_CONCERT_CARDS = 4

/** Tiles in Jump back in. */
const val HOME_JUMP_TILES = 4

/** Greeting for the local [hour] (0-23): 5-11 morning, 12-17 afternoon, else evening. */
fun homeGreeting(hour: Int): String = when (hour) {
    in 5..11 -> HomeCopy.GREETING_MORNING
    in 12..17 -> HomeCopy.GREETING_AFTERNOON
    else -> HomeCopy.GREETING_EVENING
}

data class HomeHalves(val fresh: Boolean, val critical: Boolean, val charts: Boolean)

/** Which halves of the Fresh/Critical row show (desktop `FreshCriticalRow`). Pop of the Tops takes Fresh Drops' half only while Fresh has nothing. */
fun homeHalves(
    freshCount: Int,
    freshLoading: Boolean,
    criticalCount: Int,
    criticalLoading: Boolean,
    chartsCount: Int,
    chartsLoading: Boolean,
    chartsFailed: Boolean,
): HomeHalves {
    val fresh = freshCount > 0 || freshLoading
    val critical = criticalCount > 0 || criticalLoading
    val charts = !fresh && (chartsCount > 0 || chartsLoading || chartsFailed)
    return HomeHalves(fresh, critical, charts)
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Nov 14" from a `yyyy-MM-dd` local date, or null when it isn't one. */
fun concertDateBadge(date: String?): String? {
    val d = date?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parsed = try { LocalDate.parse(d) } catch (e: Exception) { return null }
    return "${MONTHS[parsed.monthNumber - 1]} ${parsed.dayOfMonth}"
}

/** "city, state, country" with blanks left out. */
fun concertPlaceLine(e: ConcertEvent): String =
    listOf(e.city, e.state, e.country).mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }.joinToString(", ")

fun concertVenue(e: ConcertEvent): String =
    e.venueName?.trim()?.takeIf { it.isNotEmpty() } ?: HomeCopy.VENUE_TBA

/** The concert card's artist: the event's artist, else its name. */
fun concertArtist(e: ConcertEvent): String = e.artistName?.trim()?.takeIf { it.isNotEmpty() } ?: e.name

/**
 * The artist page's On Tour tab, which lists all of an artist's shows. A
 * concert card opens the artist there (both apps; iOS's `ArtistTab.onTour`
 * has this raw value), as does Now Playing's on-tour dot.
 */
const val ARTIST_ON_TOUR_TAB = "On Tour"

/** The key [HomeState.concertArtistImages] uses for an artist: trimmed and lower-cased (desktop's `artistImages` key). */
fun concertArtistKey(artist: String): String = artist.trim().lowercase()

/**
 * The concert card's photo: the artist's photo from [artistImages] (keyed by
 * [concertArtistKey] of [concertArtist]) when it's an http(s) URL, else the
 * event's own image when that is, else null (the card's placeholder).
 *
 * Desktop shows only the artist's photo. Falling back to the event's image
 * is a deliberate mobile difference: Ticketmaster and SeatGeek send one for
 * some shows, and a card that has it shouldn't lose it while the artist's
 * photo is missing or still being looked up.
 */
fun concertPhotoUrl(e: ConcertEvent, artistImages: Map<String, String> = emptyMap()): String? =
    artistImages[concertArtistKey(concertArtist(e))]?.trim()?.takeIf { isHttpUrl(it) }
        ?: e.imageUrl?.trim()?.takeIf { isHttpUrl(it) }

/** Where a concert photo is cropped vertically: a quarter of the way down, desktop's `center 25%`, so faces stay in. */
const val CONCERT_PHOTO_FOCUS_Y = 0.25f

data class TicketLink(val label: String, val url: String, val source: String)

internal fun isHttpUrl(u: String): Boolean =
    u.startsWith("https://", ignoreCase = true) || u.startsWith("http://", ignoreCase = true)

/** The event's ticket links: http(s) only, one per URL. Falls back to `ticketUrl` as "Tickets". */
fun concertTickets(e: ConcertEvent): List<TicketLink> {
    val out = mutableListOf<TicketLink>()
    val seen = HashSet<String>()
    for (s in e.ticketSources) {
        val url = s.ticketUrl.trim()
        if (!isHttpUrl(url) || !seen.add(url)) continue
        val label = s.label.takeIf { it.isNotBlank() } ?: s.source.takeIf { it.isNotBlank() } ?: HomeCopy.TICKETS
        out += TicketLink(label, url, s.source)
    }
    if (out.isEmpty()) {
        val url = e.ticketUrl?.trim().orEmpty()
        if (isHttpUrl(url)) out += TicketLink(HomeCopy.TICKETS, url, e.source)
    }
    return out
}

/** Home's strings, copied from desktop's home components. */
object HomeCopy {
    const val GREETING_MORNING = "Good morning"
    const val GREETING_AFTERNOON = "Good afternoon"
    const val GREETING_EVENING = "Good evening"

    const val JUMP_BACK_IN = "Jump back in"
    const val MIXES_TITLE = "Made from your listening"
    const val MIXES_SUBTITLE = "From the services you've connected"
    const val PLAYLISTS_TITLE = "Your playlists, freshest first"
    const val PLAYLISTS_SUBTITLE = "By last change, so a playlist that refreshes itself stays up front however old it is"
    const val ALBUMS_TITLE = "Albums you might love"
    const val CONCERTS_TITLE = "Concerts near you"
    const val CONCERTS_SUBTITLE = "Artists from your collection playing soon"
    const val ASK_TITLE = "Ask Shuffleupagus"
    /** The source chips' first chip. */
    const val ALL = "All"
    /** A Shuffleupagus pick's reason badge when it gave no reason. */
    const val PICKED_BY_SHUFFLEUPAGUS = "Picked by Shuffleupagus"
    /** The reason badge's accessibility label. */
    const val WHY_SHUFFLEUPAGUS = "Why Shuffleupagus picked this"

    const val IMPORT_TITLE = "Build your collection"
    const val IMPORT_BODY = "Import your playlists and loved tracks from the services you use."
    const val IMPORT_BUTTON = "Import"

    const val PLAY_COLLECTION_RADIO = "Play Collection Radio"
    const val RECENT_LOVES = "Recent Loves"
    const val TICKETS = "Tickets"
    const val VENUE_TBA = "Venue TBA"
    const val SET_UP_SHUFFLEUPAGUS = "Set up Shuffleupagus"

    const val FRESH_DROPS = "Fresh Drops"
    const val CRITICAL_DARLINGS = "Critical Darlings"
    const val POP_OF_THE_TOPS = "Pop of the Tops"
    const val CHARTS_ERROR_TITLE = "Couldn’t load Pop of the Tops"
    const val CHARTS_ERROR_BODY = "Check your connection, then try again."
    const val TRY_AGAIN = "Try again"
    /** A half's link (desktop `homeHalf`); its accessibility label is [seeAllIn]. */
    const val SEE_ALL = "See all"
    /** The critics' reason badge's accessibility label. */
    const val WHAT_THE_CRITICS_SAID = "What the critics said"

    const val ASK_EXPLANATION =
        "Shuffleupagus is Parachord’s AI DJ. Ask for a playlist, a mood or a question about music, and it answers with songs you can play, using the AI plug-in you choose."
    const val ASK_CAPTION = "An example built from what you’ve been playing. Edit it, or ask for anything."
    const val ASK_FIELD_LABEL = "Prompt for Shuffleupagus"
    const val ASK_BUTTON = "Ask"

    const val SETUP_DIALOG = "Finish setting up"
    /** An open setup item's button (desktop `home-setup.js`). */
    const val SET_UP = "Set up"
    /** The getting-started layout's title (desktop `home-getting-started.js`). */
    const val WELCOME = "Welcome to Parachord"
    const val WELCOME_INTRO = "All your music, from every service, in one player. Three steps:"
    const val SKIP_SETUP = "Skip setup for now"

    const val STEP_CONNECT = "Connect where you listen"
    const val STEP_MUSIC = "Bring in your music"
    const val STEP_LISTEN = "Start listening"
    const val STEP_DONE = "Done"
    const val STEP_NEXT = "Next"
    const val STEP_NOT_YET = "Not yet"

    // Step 1.
    const val CONNECT_LINE =
        "Sign in to the services you use, and your own music server if you have one. Parachord finds each song wherever you have it and plays it here."
    const val GROUP_SERVICES = "Services"
    const val GROUP_NO_ACCOUNT = "On already, no account needed"
    const val CONNECTED = "Connected"
    const val CONNECT = "Connect"
    const val MORE_PLUGINS = "More plug-ins"
    const val BROWSE = "Browse"
    const val MUSIC_FOLDER = "Your music folder"
    const val ADD_A_FOLDER = "Add a folder"
    const val ADD_A_MUSIC_FOLDER = "Add a music folder"
    const val ON = "On"
    const val OFF = "Off"
    /**
     * Desktop's order note without its sound-quality clause: the phones have no
     * sound-quality setting, so neither the clause nor its button is here.
     */
    const val ORDER_NOTE =
        "With more than one source, Parachord plays each song from the first one in your order that has it. Drag plug-ins to change the order."
    const val SET_THE_ORDER = "Set the order"

    // Step 2.
    const val MUSIC_LINE =
        "Bring in your playlists and liked songs, and your listening history for recommendations and friends."
    const val GROUP_PLAYLISTS = "Playlists and liked songs"
    const val IMPORT_A_PLAYLIST = "Import a playlist"
    const val IMPORT_A_PLAYLIST_SUB = "Paste a link from any service"
    const val SYNC_YOUR_LIBRARY = "Sync your library"
    const val SYNC_YOUR_LIBRARY_SUB = "From a service you connected"

    // Step 3.
    const val LISTEN_LINE_WITH_MUSIC = "Play your collection, or open a playlist you brought in."
    const val LISTEN_LINE_EMPTY = "Play an album from Explore below, or search for a song."
    const val SEARCH = "Search"

    const val EXPLORE_TITLE = "Explore while you set up"
    const val EXPLORE_SUBTITLE =
        "Albums critics love, and the most played right now. Playing needs a connected service; Bandcamp plays some of these already."

    fun lovesSubtitle(n: Int): String = if (n == 1) "Your latest love" else "Your $n latest loves"

    fun setupPill(done: Int, total: Int): String = "Finish setting up · $done of $total"

    /** Accessibility labels, as desktop's `aria-label`s. */
    fun setUpLabel(item: String): String = "Set up $item"
    fun dismissLabel(item: String): String = "Dismiss $item"
    fun playLabel(title: String): String = "Play $title"
    fun openLabel(title: String): String = "Open $title"
    fun seeAllIn(title: String): String = "See all in $title"
    fun ticketsFor(artist: String): String = "Tickets for $artist"
    /** A concert card's accessibility label: it opens the artist's On Tour tab. */
    fun concertsOf(artist: String): String = "$artist concerts"
}
