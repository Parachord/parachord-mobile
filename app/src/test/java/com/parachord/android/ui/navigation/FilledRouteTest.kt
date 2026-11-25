package com.parachord.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which page is on top, as the route that opened it: a link to it reuses it, any other opens its own. */
class FilledRouteTest {
    private fun filled(pattern: String, vararg args: Pair<String, String>) =
        filledRoute(pattern, args.toMap()::get) { it.replace(" ", "%20") }

    @Test
    fun anAlbumIsItsTitleAndArtist() {
        assertEquals(
            "album/Kid%20A/Radiohead",
            filled(Routes.ALBUM, "albumTitle" to "Kid A", "artistName" to "Radiohead"),
        )
    }

    @Test
    fun queryArgumentsWithNoValueAreLeftOut() {
        assertEquals("artist/Portishead", filled(Routes.ARTIST, "artistName" to "Portishead"))
        assertEquals("artist/Portishead?mbid=x", filled(Routes.ARTIST, "artistName" to "Portishead", "mbid" to "x"))
        assertEquals("friend/f?tab=recent&period=7day", filled(Routes.FRIEND_DETAIL, "friendId" to "f", "tab" to "recent", "period" to "7day"))
        assertEquals("history", filled(Routes.HISTORY_WITH_ARGS))
    }
}
