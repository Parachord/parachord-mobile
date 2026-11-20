package com.parachord.shared.home

import com.parachord.shared.deeplink.ChatPrompt
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.repository.CriticsPickAlbum
import com.parachord.shared.repository.FreshDrop

// The lower half of desktop's Home (`home-sections.js`): the Fresh Drops /
// Critical Darlings / Pop of the Tops halves and the Ask Shuffleupagus box.
// What a card shows and how the Ask field behaves are worked out here; each
// app only draws them.

/**
 * An album card in a Home half. [releaseType] is Fresh Drops' badge label
 * ("Album", "EP", "Single"; [releaseTypeKey] picks its colour,
 * `DesignTokens.releaseTypeColor`); [review] is a Critical Darlings pick's
 * review summary, the critics' reason badge. A card opens its album by
 * [title] and [artist]; [releaseGroupMbid] is Fresh Drops' release group
 * (desktop opens it by that id).
 */
data class HomeHalfCard(
    /** Unique in its half. */
    val key: String,
    val title: String,
    val artist: String,
    val imageUrl: String?,
    val releaseType: String? = null,
    val releaseTypeKey: String? = null,
    val review: String? = null,
    val releaseGroupMbid: String? = null,
)

/** Fresh Drops' badge text (desktop `card-art.js` `releaseBadge`): "EP", else the type capitalised; null with no type. */
fun releaseTypeLabel(type: String?): String? {
    val t = type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return if (t == "ep") "EP" else t.replaceFirstChar { it.uppercaseChar() }
}

/** A pick's critics' badge text: its review summary, or null (no badge) when the feed gave none. */
fun criticsReasonBadge(pick: CriticsPickAlbum): String? = pick.blurb.trim().takeIf { it.isNotEmpty() }

private fun halfKey(index: Int, id: String?, artist: String, title: String): String =
    "$index:" + (id?.takeIf { it.isNotBlank() } ?: "$artist\u0000$title")

fun freshHalfCards(items: List<FreshDrop>): List<HomeHalfCard> =
    items.take(HOME_HALF_ROW_CARDS).mapIndexed { i, d ->
        HomeHalfCard(
            key = halfKey(i, d.mbid, d.artist, d.title),
            title = d.title,
            artist = d.artist,
            imageUrl = d.albumArt,
            releaseType = releaseTypeLabel(d.releaseType),
            releaseTypeKey = d.releaseType.trim().lowercase().takeIf { it.isNotEmpty() },
            releaseGroupMbid = d.mbid,
        )
    }

fun criticalHalfCards(items: List<CriticsPickAlbum>): List<HomeHalfCard> =
    items.take(HOME_HALF_ROW_CARDS).mapIndexed { i, p ->
        HomeHalfCard(
            key = halfKey(i, p.id, p.artist, p.title),
            title = p.title,
            artist = p.artist,
            imageUrl = p.albumArt,
            review = criticsReasonBadge(p),
        )
    }

fun chartHalfCards(items: List<ChartAlbum>): List<HomeHalfCard> =
    items.take(HOME_HALF_ROW_CARDS).mapIndexed { i, a ->
        HomeHalfCard(key = halfKey(i, a.id, a.artist, a.title), title = a.title, artist = a.artist, imageUrl = a.artworkUrl)
    }

/** The key a half card's looked-up cover is kept under ([ShownAlbumArtLookup]): its artist and title, case-folded. */
fun halfCardArtKey(card: HomeHalfCard): String =
    "half:" + card.artist.trim().lowercase() + "\u0000" + card.title.trim().lowercase()

/**
 * The half's cards with the covers [found] for the ones that came without one.
 * A half card's feed fills its covers slowly (the Critical Darlings and Fresh
 * Drops feeds look each album up in turn, behind the MusicBrainz queue), so a
 * card on screen with no cover asks for its own ([ShownAlbumArtLookup], as
 * desktop Home's `requestArt` does for its albums). A card's own cover wins.
 */
fun withHalfCardArt(cards: List<HomeHalfCard>, found: Map<String, String>): List<HomeHalfCard> {
    if (found.isEmpty()) return cards
    return cards.map { c ->
        if (!c.imageUrl.isNullOrBlank()) c else found[halfCardArtKey(c)]?.let { c.copy(imageUrl = it) } ?: c
    }
}

/** Whether a half card on screen should ask for a cover: it has none and names its album and artist. */
fun halfCardNeedsArt(card: HomeHalfCard): Boolean =
    card.imageUrl.isNullOrBlank() && card.title.isNotBlank() && card.artist.isNotBlank()

/**
 * What a half's body shows: its cards, a skeleton (shown but no cards yet),
 * or Pop of the Tops' error (no cards, not loading, the load failed).
 */
enum class HomeHalfBody { CARDS, LOADING, ERROR }

fun homeHalfBody(cards: Int, loading: Boolean, failed: Boolean): HomeHalfBody = when {
    cards > 0 -> HomeHalfBody.CARDS
    !loading && failed -> HomeHalfBody.ERROR
    else -> HomeHalfBody.LOADING
}

/**
 * The Ask Shuffleupagus field (desktop's `AskShuffleupagus`). It starts on the
 * [example]; a new example replaces the text only while the user hasn't
 * edited it ([touched]), their own words always stay. The example reads muted
 * until edited. What's sent is trimmed and capped ([ChatPrompt.cap]); a blank
 * field sends nothing.
 */
data class AskField(val text: String, val example: String, val touched: Boolean = false) {
    /** The example reads as a suggestion (muted) until it's edited. */
    val muted: Boolean get() = !touched && text.isNotEmpty() && text == example

    /** The prompt to send, or null when the field is blank (Ask disabled). */
    val prompt: String? get() = ChatPrompt.cap(text.trim()).takeIf { it.isNotBlank() }

    fun edited(value: String): AskField = copy(text = ChatPrompt.cap(value), touched = true)

    fun withExample(newExample: String): AskField =
        if (newExample == example) this else copy(example = newExample, text = if (touched) text else newExample)

    companion object {
        fun seeded(example: String): AskField = AskField(text = example, example = example)
    }
}
