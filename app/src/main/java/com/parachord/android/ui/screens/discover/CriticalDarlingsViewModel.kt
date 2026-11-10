package com.parachord.android.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.repository.CriticalDarlingsRepository
import com.parachord.android.data.repository.CriticsPickAlbum
import com.parachord.shared.model.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
/**
 * ViewModel for the Critical Darlings screen.
 *
 * Matches the desktop's critics picks state management:
 * - Loading state, albums list, sort, and search
 * - Sort options: recent (default), artist
 * - Search by album title or artist name
 */
class CriticalDarlingsViewModel constructor(
    private val repository: CriticalDarlingsRepository,
) : ViewModel() {

    // Initialize from singleton cache immediately — avoids flash of Loading state
    private val _albums = MutableStateFlow<Resource<List<CriticsPickAlbum>>>(
        repository.cached?.let { Resource.Success(it) } ?: Resource.Loading
    )
    val albums: StateFlow<Resource<List<CriticsPickAlbum>>> = _albums

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sortMode = MutableStateFlow("recent")
    val sortMode: StateFlow<String> = _sortMode

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private var loadJob: Job? = null

    init {
        loadAlbums()
    }

    fun refresh() {
        loadAlbums(forceRefresh = true)
    }

    /** Called on screen resume — reloads if cache is stale. */
    fun refreshIfStale() {
        loadAlbums(forceRefresh = false)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortMode(mode: String) {
        _sortMode.value = mode
    }

    /**
     * Get filtered and sorted albums based on current search and sort state.
     * Called from the composable as a derived computation.
     */
    fun filterAndSort(albums: List<CriticsPickAlbum>): List<CriticsPickAlbum> =
        com.parachord.shared.repository.filterAndSortCriticsPicks(albums, _searchQuery.value, _sortMode.value)

    private fun loadAlbums(forceRefresh: Boolean = false) {
        // Cancel any in-flight load to avoid duplicate collections
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Show cached results immediately (stale-while-revalidate)
            repository.cached?.let { cached ->
                _albums.value = Resource.Success(cached)
            }
            _isRefreshing.value = true
            val started = com.parachord.shared.platform.currentTimeMillis()
            try {
                repository.getCriticsPicks(forceRefresh).collect {
                    _albums.value = it
                }
            } finally {
                // Minimum visible-spin duration. The full RSS+enrich
                // round-trip can finish in <300ms when the network is
                // fast and most albums already have art carried over,
                // which is shorter than a single rotation of the
                // SpinningRefreshIcon (1s/rev) — without this guard the
                // icon barely twitches and the refresh tap feels
                // unresponsive. 700ms gives a clear visible rotation
                // even on the fast path.
                val elapsed = com.parachord.shared.platform.currentTimeMillis() - started
                val minDuration = 700L
                if (elapsed < minDuration) delay(minDuration - elapsed)
                _isRefreshing.value = false
            }
        }
    }
}
