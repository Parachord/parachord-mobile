package com.parachord.android.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.ArtistInfo
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.theme.serviceColor
import com.parachord.shared.metadata.AlbumSearchResult
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.metadata.toTrackEntity
import com.parachord.shared.playback.CatalogQueues
import com.parachord.shared.playback.resolverDisplayName
import com.parachord.shared.recommendations.RecommendationCopy
import com.parachord.shared.metadata.SearchArtwork
import com.parachord.shared.search.SearchSession
import com.parachord.shared.search.SearchState
import com.parachord.shared.search.MergedSearchItem
import com.parachord.shared.search.mergeSearchResults
import com.parachord.shared.search.asSearchCatalog
import com.parachord.shared.ui.icons.AppIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * The car's search: the shared [SearchSession] (the phone search's engine —
 * the library's tracks and albums, remote tracks, albums and artists), with
 * all of its results merged into ONE list ([mergeSearchResults] — a car screen
 * is a list of taps, not a page of sections). A row plays what it is: a song
 * replaces the queue with that track, an album plays its tracks, an artist
 * plays their top songs — the same plays the recommendations page's taps make.
 */
class CarSearchViewModel(
    private val repository: LibraryRepository,
    metadataService: MetadataService,
    searchArtwork: SearchArtwork,
    private val playbackController: PlaybackController,
    trackResolverCache: TrackResolverCache,
) : ViewModel() {

    private val catalogQueues = CatalogQueues(metadataService)

    private val session = SearchSession(
        scope = viewModelScope,
        catalog = metadataService.asSearchCatalog(),
        artwork = searchArtwork,
        localTracks = { q -> repository.searchTracks(q) },
        localAlbums = { q -> repository.searchAlbums(q) },
        resolvedTrackArt = { title, artist ->
            trackResolverCache.trackArtwork.map { it[trackKey(title, artist)] }.first { it != null }
        },
        onRemoteTracksSettled = { tracks ->
            viewModelScope.launch {
                for (track in tracks) {
                    try {
                        trackResolverCache.resolveTrack(track.title, track.artist, spotifyId = track.spotifyId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A resolve failure is not fatal; the row just keeps no badges.
                    }
                }
            }
        },
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The merged rows for the current state (the shared merge). */
    val items: StateFlow<List<MergedSearchItem>> = session.state
        .map(::mergeSearchResults)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val searching: StateFlow<Boolean> = session.state.map { it.isSearching }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** A search finished, so an empty list means no results (not "still going"). */
    val hasSearched: StateFlow<Boolean> = session.state.map { it.hasSearched }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The covers found for rows that came without one. */
    val art: StateFlow<Map<String, String>> = session.state.map { it.art }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val ownMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = ownMessages.asSharedFlow()

    /** Everything the session knows (for row rendering). */
    val state: StateFlow<SearchState> = session.state

    fun onQueryChange(text: String) {
        _query.value = text
        session.query(text)
    }

    /** A row came on screen: look up its cover if it has none (shared, as the phone's rows do). */
    fun trackShown(track: TrackSearchResult) = session.trackShown(track)
    fun albumShown(album: AlbumSearchResult) = session.albumShown(album)
    fun artistShown(artist: ArtistInfo) = session.artistShown(artist)

    private fun searchContext() = PlaybackContext(type = "search", name = _query.value.trim())

    // ── Plays ────────────────────────────────────────────────────────

    fun playSong(track: TrackEntity) {
        playbackController.playTrack(track, searchContext())
    }

    fun playRemoteSong(result: TrackSearchResult) {
        playbackController.playTrack(result.toTrackEntity(), searchContext())
    }

    fun playLibraryAlbum(album: AlbumEntity) {
        viewModelScope.launch {
            val tracks = repository.getAlbumTracks(album.id).first()
            if (tracks.isEmpty()) {
                ownMessages.tryEmit(RecommendationCopy.couldntLoad(album.title))
            } else {
                playbackController.playQueue(tracks, 0, CatalogQueues.albumContext(album.title, album.artist))
            }
        }
    }

    fun playRemoteAlbum(album: AlbumSearchResult) {
        viewModelScope.launch {
            val queue = catalogQueues.album(album.title, album.artist)
            if (queue == null) {
                ownMessages.tryEmit(RecommendationCopy.couldntLoad(album.title))
            } else {
                playbackController.playQueue(queue.tracks, 0, queue.context)
            }
        }
    }

    fun playArtist(artist: ArtistInfo) {
        viewModelScope.launch {
            val queue = catalogQueues.artistTopSongs(artist.name)
            if (queue == null) {
                ownMessages.tryEmit(RecommendationCopy.couldntLoad(artist.name))
            } else {
                playbackController.playQueue(queue.tracks, 0, queue.context)
            }
        }
    }

    override fun onCleared() {
        session.close()
        super.onCleared()
    }
}

/** The car's search screen: a text field on the car's own keyboard, then one merged list of results. */
@Composable
fun CarSearchScreen(onBack: () -> Unit, onPlayed: () -> Unit) {
    val viewModel: CarSearchViewModel = koinViewModel()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val hasSearched by viewModel.hasSearched.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(64.dp)) {
                ParachordIcon(AppIcon.ChevronLeft, contentDescription = "Back", size = 32.dp)
            }
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 68.dp)
                    .padding(end = 24.dp),
                placeholder = {
                    Text("Search your music", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp),
                leadingIcon = { ParachordIcon(AppIcon.Search, contentDescription = null, size = 26.dp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
        if (searching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }

        if (items.isEmpty() && !searching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        hasSearched -> "No results"
                        else -> "Type at least ${SearchSession.MIN_QUERY_LENGTH} characters"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { i, item -> "$i:${item.key()}" }) { _, item ->
                    CarSearchRow(item = item, viewModel = viewModel) {
                        viewModel.play(item)
                        onPlayed()
                    }
                }
            }
        }
    }
}

private fun MergedSearchItem.key(): String = when (this) {
    is MergedSearchItem.Song -> "song:${track.id}:${track.title}:${track.artist}"
    is MergedSearchItem.RemoteSong -> "remote:${result.provider}:${result.title}:${result.artist}:${result.spotifyId ?: ""}"
    is MergedSearchItem.LibraryAlbum -> "album:${album.id}"
    is MergedSearchItem.RemoteAlbum -> "remote-album:${album.title}:${album.artist}:${album.mbid ?: ""}"
    is MergedSearchItem.Artist -> "artist:${artist.name}:${artist.mbid ?: ""}"
}

private fun CarSearchViewModel.play(item: MergedSearchItem) {
    when (item) {
        is MergedSearchItem.Song -> playSong(item.track)
        is MergedSearchItem.RemoteSong -> playRemoteSong(item.result)
        is MergedSearchItem.LibraryAlbum -> playLibraryAlbum(item.album)
        is MergedSearchItem.RemoteAlbum -> playRemoteAlbum(item.album)
        is MergedSearchItem.Artist -> playArtist(item.artist)
    }
}

/** One merged search row: art, title, its line, and what kind of thing it is. */
@Composable
private fun CarSearchRow(item: MergedSearchItem, viewModel: CarSearchViewModel, onTap: () -> Unit) {
    val art by viewModel.art.collectAsStateWithLifecycle()

    val title: String
    val line: String
    val chip: String
    val chipColor: androidx.compose.ui.graphics.Color?
    val artworkUrl: String?
    var remoteTrack: TrackSearchResult? = null
    var remoteAlbum: AlbumSearchResult? = null
    var artist: ArtistInfo? = null
    when (item) {
        is MergedSearchItem.Song -> {
            title = item.track.title
            line = item.track.artist
            chip = "Song · Library"
            chipColor = null
            artworkUrl = item.track.artworkUrl
                ?: art[SearchSession.trackArtKey(item.track.title, item.track.artist)]
        }
        is MergedSearchItem.RemoteSong -> {
            remoteTrack = item.result
            title = item.result.title
            line = item.result.artist
            chip = "Song · ${resolverDisplayName(item.result.provider)}"
            chipColor = item.result.provider.takeIf { it.isNotBlank() }?.let { serviceColor(it) }
            artworkUrl = item.result.artworkUrl ?: art[SearchSession.trackArtKey(item.result.title, item.result.artist)]
        }
        is MergedSearchItem.LibraryAlbum -> {
            title = item.album.title
            line = item.album.artist
            chip = "Album · Library"
            chipColor = null
            artworkUrl = item.album.artworkUrl ?: art[SearchSession.albumArtKey(item.album.title, item.album.artist)]
        }
        is MergedSearchItem.RemoteAlbum -> {
            remoteAlbum = item.album
            title = item.album.title
            line = item.album.artist
            chip = "Album"
            chipColor = null
            artworkUrl = item.album.artworkUrl ?: art[SearchSession.albumArtKey(item.album.title, item.album.artist)]
        }
        is MergedSearchItem.Artist -> {
            artist = item.artist
            title = item.artist.name
            line = ""
            chip = "Artist"
            chipColor = null
            artworkUrl = item.artist.imageUrl ?: art[SearchSession.artistArtKey(item.artist.name)]
        }
    }
    remoteTrack?.let { LaunchedEffect(it.title, it.artist) { viewModel.trackShown(it) } }
    remoteAlbum?.let { LaunchedEffect(it.title, it.artist) { viewModel.albumShown(it) } }
    artist?.let { LaunchedEffect(it.name) { viewModel.artistShown(it) } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(onClick = onTap)
            .padding(horizontal = 28.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtCard(
            artworkUrl = artworkUrl,
            size = 52.dp,
            cornerRadius = if (item is MergedSearchItem.Artist) 26.dp else 4.dp,
            elevation = 0.dp,
            placeholderName = line.ifBlank { title },
            contentDescription = title,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = line,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        if (chipColor != null) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(chipColor),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = chip,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
