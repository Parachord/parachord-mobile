package com.parachord.android.ui.components

/**
 * The covers album cards have actually displayed this session, by artist and
 * album. The album page opens with the one the user just tapped instead of
 * waiting for a provider to name a cover (desktop passes the card's cover the
 * same way). The image is already in Coil's cache, so it shows at once.
 *
 * Filled by [AlbumArtCard] and [AlbumArtCardFill] when a cover with a known
 * artist and album loads. Most recent wins; bounded, in memory only.
 */
object ShownAlbumCovers {
    private const val MAX_ENTRIES = 500
    private val covers = LinkedHashMap<String, String>()

    private fun key(artist: String, album: String) = "${artist.trim().lowercase()}|${album.trim().lowercase()}"

    fun remember(artist: String?, album: String?, url: String?) {
        if (artist.isNullOrBlank() || album.isNullOrBlank() || url.isNullOrBlank()) return
        synchronized(covers) {
            val k = key(artist, album)
            covers.remove(k)
            covers[k] = url
            if (covers.size > MAX_ENTRIES) covers.remove(covers.keys.first())
        }
    }

    fun get(artist: String, album: String): String? = synchronized(covers) { covers[key(artist, album)] }
}
