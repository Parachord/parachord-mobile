package com.parachord.android.car.cal

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.shared.metadata.SearchArtwork
import com.parachord.shared.search.MergedSearchItem
import com.parachord.shared.search.SearchSession
import com.parachord.shared.search.asSearchCatalog
import com.parachord.shared.search.mergeSearchResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The car's search as a CAL `SearchTemplate`: the host renders the field and
 * its own keyboard — which IS the distraction compliance (the host decides when
 * typing is allowed; while driving it swaps to its drive-optimized keyboard or
 * refuses). The results are the shared `SearchSession`'s, merged into one list
 * (`mergeSearchResults`); a tap plays what the row is — a song replaces the
 * queue with that track, an album plays its tracks, an artist plays their top
 * songs.
 */
class CalSearchScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val playback: CalPlaybackBridge by lazy { GlobalContext.get().get() }
    private val repository: LibraryRepository by lazy { GlobalContext.get().get() }
    private val metadataService: MetadataService by lazy { GlobalContext.get().get() }
    private val searchArtwork: SearchArtwork by lazy { GlobalContext.get().get() }
    private val art: CalArt by lazy { GlobalContext.get().get() }

    private val session: SearchSession by lazy {
        SearchSession(
            scope = scope,
            catalog = metadataService.asSearchCatalog(),
            artwork = searchArtwork,
            localTracks = { q -> repository.searchTracks(q) },
            localAlbums = { q -> repository.searchAlbums(q) },
            resolvedTrackArt = { _, _ -> null },
            onRemoteTracksSettled = { _ -> },
        )
    }

    private var query = ""
    private var items: List<MergedSearchItem> = emptyList()
    private var artMap: Map<String, String> = emptyMap()
    private var shownArtworkUrls: Set<String> = emptySet()

    init {
        scope.launch {
            session.state.drop(1).collect { state ->
                artMap = state.art
                items = mergeSearchResults(state)
                invalidate()
            }
        }
        scope.launch {
            art.loadedUrls.collect { url ->
                if (url in shownArtworkUrls) invalidate()
            }
        }
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    scope.cancel()
                }
            },
        )
    }

    /** A row's artwork: its own URL, else one the shared session looked up. */
    private fun artworkFor(item: MergedSearchItem): String? = when (item) {
        is MergedSearchItem.Song ->
            item.track.artworkUrl ?: artMap[SearchSession.trackArtKey(item.track.title, item.track.artist)]
        is MergedSearchItem.RemoteSong ->
            item.result.artworkUrl ?: artMap[SearchSession.trackArtKey(item.result.title, item.result.artist)]
        is MergedSearchItem.LibraryAlbum ->
            item.album.artworkUrl ?: artMap[SearchSession.albumArtKey(item.album.title, item.album.artist)]
        is MergedSearchItem.RemoteAlbum ->
            item.album.artworkUrl ?: artMap[SearchSession.albumArtKey(item.album.title, item.album.artist)]
        is MergedSearchItem.Artist ->
            item.artist.imageUrl ?: artMap[SearchSession.artistArtKey(item.artist.name)]
    }

    override fun onGetTemplate(): Template {
        // Touch lazy session so observation starts before first keystroke.
        session
        val limit = carListLimit(carContext)
        val shownUrls = HashSet<String>()
        val itemList = ItemList.Builder().apply {
            items.take(limit).forEach { item ->
                val (title, line) = when (item) {
                    is MergedSearchItem.Song -> item.track.title to item.track.artist
                    is MergedSearchItem.RemoteSong -> item.result.title to item.result.artist
                    is MergedSearchItem.LibraryAlbum -> item.album.title to item.album.artist
                    is MergedSearchItem.RemoteAlbum -> item.album.title to item.album.artist
                    is MergedSearchItem.Artist -> item.artist.name to "Artist"
                }
                val artUrl = artworkFor(item)
                if (!artUrl.isNullOrBlank()) {
                    shownUrls.add(artUrl)
                    art.load(carContext, artUrl)
                }
                addItem(
                    Row.Builder()
                        .setTitle(title.ifBlank { "Untitled" })
                        .apply { if (line.isNotBlank()) addText(line) }
                        .setImageWhenReady(art.icon(artUrl))
                        .setOnClickListener {
                            when (item) {
                                is MergedSearchItem.Song ->
                                    playback.playSearchTrack(item.track, query)
                                is MergedSearchItem.RemoteSong ->
                                    playback.playSearchRemote(item.result, query, artUrl)
                                is MergedSearchItem.LibraryAlbum ->
                                    playback.playSearchLibraryAlbum(item.album)
                                is MergedSearchItem.RemoteAlbum ->
                                    playback.playSearchRemoteAlbum(item.album.title, item.album.artist)
                                is MergedSearchItem.Artist ->
                                    playback.playSearchArtist(item.artist.name)
                            }
                        }
                        .build(),
                )
            }
            if (items.isEmpty()) {
                setNoItemsMessage(
                    if (query.isBlank()) "Search your music" else "No results",
                )
            }
        }.build()
        shownArtworkUrls = shownUrls
        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) {
                    query = searchText
                    // Let session.state drive invalidate() so we don't reset IME
                    // composition or trigger double invalidations per keystroke.
                    session.query(searchText)
                }

                override fun onSearchSubmitted(searchText: String) {
                    query = searchText
                    session.query(searchText)
                    invalidate()
                }
            },
        )
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search your music")
            .setInitialSearchText(query)
            .setItemList(itemList)
            .build()
    }
}
