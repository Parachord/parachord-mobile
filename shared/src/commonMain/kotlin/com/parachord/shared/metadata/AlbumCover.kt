package com.parachord.shared.metadata

/**
 * Whether a search result's cover is an album's own (#851, a port of desktop's
 * `resolver-cover.js`, parachord#1666).
 *
 * [AlbumCatalog.isSameAlbum] is for finding an album to open, and lets close
 * titles through ("Greatest Hits II" for "Greatest Hits"). A cover has to be
 * the album's own, so it asks for equal titles, edition words and "(feat. …)"
 * aside, by the same credit ([AlbumCatalog.creditMatches]). A result's title
 * stands in for its album only when it names no album of its own: "Thriller"
 * on "Greatest Hits" is that album's cover, not Thriller's.
 *
 * Desktop's compilation rule isn't ported: mobile also searches albums, and a
 * service credits a compilation itself to Various Artists.
 */
object AlbumCover {

    /** "Honey (feat. X)" is the album "Honey". */
    private val FEATURING = Regex(
        """\s*[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    private fun titleOf(title: String?): String =
        AlbumCatalog.normalizeAlbumTitle(title.orEmpty().replace(FEATURING, ""))

    /**
     * An album name that names nothing: blank, "Single", or the name of the
     * result's own source (a plugin with no album naming its service). A
     * service name with no such source could be a real title, so it stays.
     */
    fun isPlaceholderAlbum(album: String?, sources: Collection<String?> = emptyList()): Boolean {
        val name = album?.trim()?.lowercase().orEmpty()
        if (name.isEmpty() || name == "single") return true
        val compact = name.filter { it in 'a'..'z' || it in '0'..'9' }
        if (compact.isEmpty() || compact != name.filter { it.isLetterOrDigit() }) return false
        return sources.any { it?.lowercase() == compact }
    }

    /** The release a track result answers to: its album, or its own title when it names none (a single). */
    fun releaseNameOf(album: String?, title: String, source: String?): String =
        if (isPlaceholderAlbum(album, listOf(source))) title else album.orEmpty()

    /**
     * Whether a result named [releaseName] by [resultArtist] from [source]
     * carries the cover of [album] by [artist]: the same title, edition words
     * aside ("Dummy (Deluxe Edition)" serves "Dummy"), by the same credit.
     * Equal titles, not close ones: "Greatest Hits II" is not "Greatest Hits".
     */
    fun isCoverOf(artist: String, album: String, resultArtist: String?, releaseName: String?, source: String? = null): Boolean {
        if (artist.isBlank() || album.isBlank()) return false
        if (isPlaceholderAlbum(album, listOf(source))) return false
        val want = titleOf(album)
        return want.isNotEmpty() && want == titleOf(releaseName) && AlbumCatalog.creditMatches(artist, resultArtist)
    }

    /**
     * The covers among [results] (in their order) that are [album]'s own, the
     * album as asked for before an edition of it.
     */
    fun <T> covers(
        results: List<T>,
        artist: String,
        album: String,
        releaseName: (T) -> String?,
        resultArtist: (T) -> String?,
        source: (T) -> String?,
        art: (T) -> String?,
    ): List<String> {
        val matches = results.filter {
            !art(it).isNullOrBlank() && isCoverOf(artist, album, resultArtist(it), releaseName(it), source(it))
        }
        fun plain(s: String?) = s.orEmpty().trim().lowercase()
        val (exact, editions) = matches.partition { plain(releaseName(it)) == plain(album) }
        return (exact + editions).mapNotNull(art).distinct()
    }

    /** [covers] for album search results. */
    fun albumCovers(results: List<AlbumSearchResult>, artist: String, album: String): List<String> =
        covers(results, artist, album, { it.title }, { it.artist }, { it.provider }, { it.artworkUrl })

    /** [covers] for track search results: a track's album, or the single it is. */
    fun trackCovers(results: List<TrackSearchResult>, artist: String, album: String): List<String> =
        covers(results, artist, album, { releaseNameOf(it.album, it.title, it.provider) }, { it.artist }, { it.provider }, { it.artworkUrl })
}
