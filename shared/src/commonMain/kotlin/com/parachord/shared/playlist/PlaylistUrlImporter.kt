package com.parachord.shared.playlist

import com.parachord.shared.deeplink.PlaylistUrlKind
import com.parachord.shared.deeplink.ProtocolTrack
import com.parachord.shared.deeplink.ProviderPlaylistResolver
import com.parachord.shared.deeplink.ResolvedProtocolPlay
import com.parachord.shared.deeplink.classifyPlaylistUrl
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.randomUUID

/**
 * Imports a playlist from a link, on both apps (the import field and
 * `parachord://import?url=`). It goes through the same resolver as a
 * `parachord://play/playlist` link ([ProviderPlaylistResolver]): the native
 * Spotify and Apple Music lookups, then whichever plugin claims the URL and
 * implements `lookupPlaylist` (SoundCloud sets, any future service), so a new
 * service needs only its `.axe`. A link no service claims is a tracklist
 * document and imports as a hosted XSPF, which is polled for changes.
 *
 * Before this, Android's `PlaylistImportManager` and iOS's
 * `IosContainer.importPlaylistFromUrl` each chose by URL shape
 * (`open.spotify.com` / `music.apple.com`, else XSPF), so a SoundCloud set was
 * fetched as XSPF and failed, and Achordion links and Spotify's editorial
 * playlists, which play from a link, didn't import.
 *
 * A service playlist is saved with `source = "<providerId>-import"`
 * (`spotify-import`, `applemusic-import`, `soundcloud-import`), the id prefix
 * the old importers used, and no `sourceUrl`, so nothing polls it.
 *
 * A save that must happen once (a recommended playlist's Save, which a later
 * visit can tap again) passes a `savedKey`: the playlist is then saved as
 * `<source>-<savedKey>`, and when a playlist with that id already exists
 * ([findPlaylist]) it is returned as it is and nothing new is saved, as
 * desktop's stable `<resolverId>-rec-<itemId>` id does. A link that falls back
 * to a hosted XSPF ignores the key (the XSPF service names its own rows).
 */
class PlaylistUrlImporter(
    private val providerPlaylists: ProviderPlaylistResolver,
    /** Imports an XSPF URL as a hosted playlist ([HostedXspfService.importHostedXspf]). */
    private val importHostedXspf: suspend (url: String) -> XspfImportResult,
    /** Saves a new playlist with its tracks (`LibraryRepository.createPlaylistWithTracks`). */
    private val createPlaylist: suspend (Playlist, List<Track>) -> Unit,
    /** A saved playlist by id (`PlaylistDao.getById`); checked only for a `savedKey` import. */
    private val findPlaylist: suspend (id: String) -> Playlist?,
    /**
     * The name of the plugin that claims a playlist link and can look it up
     * (`PluginManager.playlistLookupPluginName`), else null. Such a link whose
     * lookup fails is an error, never an XSPF document (desktop's "Could not
     * load playlist"; #778: Amazon answers a plain fetch of its page with 400).
     */
    private val playlistLookupPluginName: suspend (url: String) -> String? = { null },
) {
    suspend fun importFromUrl(rawUrl: String, savedKey: String? = null): XspfImportResult {
        val url = normalizePlaylistUrl(rawUrl)
        Log.d(TAG, "Importing playlist from $url")
        // An Achordion playlist is an XSPF document at its own endpoint: import
        // that, so it's a hosted playlist that follows Achordion's changes.
        (classifyPlaylistUrl(url) as? PlaylistUrlKind.Achordion)?.let { return importHostedXspf(it.xspfUrl) }
        val play = providerPlaylists.resolve(url)
        if (play == null) {
            playlistLookupPluginName(url)?.let { name ->
                Log.w(TAG, "$name claims $url but couldn't load it")
                throw PlaylistLookupFailedException(name)
            }
            return importHostedXspf(url)
        }
        return save(play, savedKey?.takeIf { it.isNotBlank() })
    }

    private suspend fun save(play: ResolvedProtocolPlay, savedKey: String?): XspfImportResult {
        val provider = play.providerId ?: "link"
        val source = "$provider-import"
        val id = "$source-${savedKey ?: randomUUID()}"
        if (savedKey != null) {
            findPlaylist(id)?.let { existing ->
                Log.d(TAG, "'${existing.name}' is already saved (id=$id)")
                return XspfImportResult(existing.id, existing.name, existing.trackCount)
            }
        }
        val tracks = play.tracks.mapIndexed { i, t -> protocolPlayTrack(t, provider, i) }
        createPlaylist(
            Playlist(
                id = id,
                name = play.displayName,
                artworkUrl = play.albumArt ?: tracks.firstOrNull()?.artworkUrl,
                trackCount = tracks.size,
            ),
            tracks,
        )
        Log.d(TAG, "Imported '${play.displayName}' (${tracks.size} tracks, id=$id)")
        return XspfImportResult(id, play.displayName, tracks.size)
    }

    private companion object {
        const val TAG = "PlaylistUrlImporter"
    }
}

/** A plugin claimed the playlist link and couldn't load it (#778). */
class PlaylistLookupFailedException(val serviceName: String) :
    Exception("Couldn’t load that playlist from $serviceName. Try again.")

/** `spotify:playlist:<id>` becomes its web link; anything else is trimmed. */
fun normalizePlaylistUrl(rawUrl: String): String {
    val url = rawUrl.trim()
    if (url.startsWith("spotify:playlist:")) {
        return "https://open.spotify.com/playlist/" + url.removePrefix("spotify:playlist:").substringBefore("?")
    }
    return url
}

/**
 * A playlist track as the old importers saved it: a Spotify or Apple Music id
 * becomes the track's id and resolver, so it plays without a search; a track
 * with neither (a plugin's playlist) is metadata only and resolves when it plays.
 */
fun protocolPlayTrack(t: ProtocolTrack, provider: String, index: Int): Track = when {
    t.spotifyId != null -> Track(
        id = "spotify:${t.spotifyId}", title = t.title, artist = t.artist, album = t.album,
        albumId = t.albumId, duration = t.durationMs, artworkUrl = t.artworkUrl, resolver = "spotify",
        spotifyId = t.spotifyId, spotifyUri = "spotify:track:${t.spotifyId}",
    )
    t.appleMusicId != null -> Track(
        id = "applemusic:${t.appleMusicId}", title = t.title, artist = t.artist, album = t.album,
        duration = t.durationMs, artworkUrl = t.artworkUrl, resolver = "applemusic", appleMusicId = t.appleMusicId,
    )
    else -> Track(
        id = "$provider-import:${t.artist}:${t.title}:$index", title = t.title, artist = t.artist,
        album = t.album, duration = t.durationMs, artworkUrl = t.artworkUrl,
    )
}
