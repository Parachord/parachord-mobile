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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.theme.serviceColor
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.metadata.toTrackEntity
import com.parachord.shared.playback.resolverDisplayName
import com.parachord.shared.resolver.ResolverSearch
import com.parachord.shared.search.SearchSession
import com.parachord.shared.ui.icons.AppIcon

/**
 * The car's search. The query runs through the shared `ResolverSearch`
 * (`streamingResolverSearch`, the DJ tools' search surface): every active
 * resolver that can search is asked, so every result row is playable — a
 * streaming track or a local file — and the car never shows a metadata-only
 * row it can't start. It searches like the phone's search session: from two
 * characters, 300 ms after the last keystroke, cancelling the superseded
 * search. A result tap replaces the queue with that one track (the car spec:
 * nothing follows it) and opens Now Playing.
 */
class CarSearchViewModel(
    private val resolverSearch: ResolverSearch,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _results = MutableStateFlow<List<TrackSearchResult>>(emptyList())
    val results = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()

    /** A search finished, so an empty list means no results (not "still going"). */
    private val _hasSearched = MutableStateFlow(false)
    val hasSearched = _hasSearched.asStateFlow()

    /** The last search failed (offline, a rate-limited service): "No results" would lie. */
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(text: String) {
        _query.value = text
        searchJob?.cancel()
        val q = text.trim()
        if (q.length < SearchSession.MIN_QUERY_LENGTH) {
            _searching.value = false
            _hasSearched.value = false
            _failed.value = false
            _results.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(SearchSession.DEBOUNCE_MS)
            _searching.value = true
            try {
                _results.value = resolverSearch.search(q)
                _failed.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _results.value = emptyList()
                _failed.value = true
            }
            _hasSearched.value = true
            _searching.value = false
        }
    }

    /** A result tap replaces the queue with that one track, under a "search" context like the phone's search. */
    fun play(result: TrackSearchResult) {
        playbackController.playTrack(
            result.toTrackEntity(),
            PlaybackContext(type = "search", name = _query.value.trim()),
        )
    }
}

/** The car's search screen: a text field on the car's own keyboard, then a plain list of results. */
@Composable
fun CarSearchScreen(onBack: () -> Unit, onPlayed: () -> Unit) {
    val viewModel: CarSearchViewModel = koinViewModel()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val hasSearched by viewModel.hasSearched.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()

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

        if (results.isEmpty() && !searching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        failed -> "Couldn’t search. Try again."
                        hasSearched -> "No results"
                        else -> "Type at least ${SearchSession.MIN_QUERY_LENGTH} characters"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(results, key = { i, r -> "$i:${r.provider}:${r.title}:${r.artist}:${r.spotifyId ?: ""}" }) { _, result ->
                    CarSearchRow(result = result) {
                        viewModel.play(result)
                        onPlayed()
                    }
                }
            }
        }
    }
}

/** One search result: art, title, artist, and the service it came from. */
@Composable
private fun CarSearchRow(result: TrackSearchResult, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(onClick = onTap)
            .padding(horizontal = 28.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtCard(
            artworkUrl = result.artworkUrl,
            size = 52.dp,
            cornerRadius = 4.dp,
            elevation = 0.dp,
            placeholderName = result.artist,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = result.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.artist,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        if (result.provider.isNotBlank()) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(serviceColor(result.provider)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = resolverDisplayName(result.provider),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
