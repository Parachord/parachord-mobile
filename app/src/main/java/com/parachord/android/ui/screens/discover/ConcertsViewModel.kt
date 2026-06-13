package com.parachord.android.ui.screens.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.BuildConfig
import com.parachord.shared.api.GeoLocation
import com.parachord.shared.api.GeoLocationClient
import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.android.data.db.dao.ArtistDao
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.location.DeviceLocationProvider
import com.parachord.android.data.repository.ConcertArtist
import com.parachord.android.data.repository.ConcertEvent
import com.parachord.android.data.repository.ConcertsRepository
import com.parachord.shared.model.Resource
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
class ConcertsViewModel constructor(
    private val concertsRepository: ConcertsRepository,
    private val settingsStore: SettingsStore,
    private val geoLocationClient: GeoLocationClient,
    private val deviceLocationProvider: DeviceLocationProvider,
    private val artistDao: ArtistDao,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val lastFmClient: LastFmClient,
    private val listenBrainzClient: ListenBrainzClient,
) : ViewModel() {

    companion object {
        private const val TAG = "ConcertsVM"
        private const val MAX_ARTISTS = 40 // Match desktop's max artist count
    }

    private val _events = MutableStateFlow<Resource<List<ConcertEvent>>>(
        concertsRepository.cached?.let { Resource.Success(it) } ?: Resource.Loading,
    )
    val events: StateFlow<Resource<List<ConcertEvent>>> = _events.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _locationCity = MutableStateFlow<String?>(null)
    val locationCity: StateFlow<String?> = _locationCity.asStateFlow()

    private val _radiusMiles = MutableStateFlow(50)
    val radiusMiles: StateFlow<Int> = _radiusMiles.asStateFlow()

    private val _hasLocation = MutableStateFlow(false)
    val hasLocation: StateFlow<Boolean> = _hasLocation.asStateFlow()

    private val _isDetectingLocation = MutableStateFlow(false)
    val isDetectingLocation: StateFlow<Boolean> = _isDetectingLocation.asStateFlow()

    private val _locationSuggestions = MutableStateFlow<List<GeoLocation>>(emptyList())
    val locationSuggestions: StateFlow<List<GeoLocation>> = _locationSuggestions.asStateFlow()

    // One-shot signal: a geoIP fallback produced a CONFIRMABLE suggestion (coarse
    // on cellular — not trustworthy enough to auto-commit, unlike a GPS fix). The
    // screen observes this to open the location picker so the user can tap the
    // suggestion to confirm. Mirrors iOS #199's geoIP-confirm UX.
    private val _showGeoIpConfirm = MutableStateFlow(false)
    val showGeoIpConfirm: StateFlow<Boolean> = _showGeoIpConfirm.asStateFlow()

    // One-shot signal that a location was just COMMITTED (GPS detect, manual pick,
    // or a confirmed geoIP suggestion). The screen observes this to close the
    // location picker — otherwise a GPS-commit-via-Detect updates the bar behind
    // an open modal that never closes.
    private val _locationCommitted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val locationCommitted: SharedFlow<Unit> = _locationCommitted.asSharedFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val loc = settingsStore.getConcertLocation()
            _locationCity.value = loc.city
            _radiusMiles.value = loc.radiusMiles
            if (loc.latitude != null && loc.longitude != null) {
                _hasLocation.value = true
                loadEvents(forceRefresh = false)
            } else if (deviceLocationProvider.hasLocationPermission()) {
                // No saved location. Auto-detect on cold launch ONLY when we can do
                // it silently AND trustworthily — i.e. GPS permission is already
                // granted (→ commit). When it isn't, do NOTHING here: the passive
                // "Detect my location" prompt stands. We never auto-open a dialog or
                // silently commit coarse geoIP on a plain browse (iOS #199 parity —
                // iOS never runs detect from init).
                detectLocation()
            }
        }
    }

    /** True when COARSE location permission has already been granted. */
    fun hasLocationPermission(): Boolean = deviceLocationProvider.hasLocationPermission()

    /**
     * Detect the user's concert location. GPS (FusedLocationProvider) is
     * trustworthy → COMMIT directly. geoIP is coarse on cellular → surface as a
     * CONFIRMABLE suggestion (the user taps it to commit). Mirrors desktop's
     * geoIP fallback chain and the iOS #199 GPS-first flow.
     *
     * The UI should request COARSE permission before calling this; we still
     * gracefully fall back to geoIP if permission isn't granted (GPS returns null).
     *
     * @param userInitiated true when the user explicitly tapped "Detect" — only
     *   then do we surface the coarse geoIP fallback as a confirmable suggestion
     *   (which opens the picker). On the cold-launch auto-detect (false), the
     *   geoIP fallback is suppressed entirely so a plain browse never pops a
     *   dialog or fetches geoIP — only a trustworthy GPS fix can auto-commit.
     */
    fun detectLocation(userInitiated: Boolean = false) {
        viewModelScope.launch {
            _isDetectingLocation.value = true
            try {
                // 1. GPS first (city-level, single fix, ~10s cap). Never throws.
                val gps = deviceLocationProvider.getCurrentLocation()
                if (gps != null) {
                    val (lat, lon) = gps
                    val name = try {
                        geoLocationClient.reverseGeocode(lat, lon)
                    } catch (e: Exception) {
                        Log.w(TAG, "Reverse geocode failed", e)
                        null
                    } ?: "Current location"
                    // GPS is trustworthy — commit immediately, clear suggestions.
                    _locationSuggestions.value = emptyList()
                    setLocation(lat, lon, name)
                    return@launch
                }

                // 2. GPS denied/failed → geoIP fallback, but ONLY when the user
                //    explicitly asked. Coarse on cellular, so never silently commit
                //    — surface as a single confirmable suggestion the user taps in
                //    the picker (parity with iOS #199). Suppressed on cold-launch
                //    auto-detect so a browse doesn't pop the picker or hit geoIP.
                if (!userInitiated) return@launch
                val geo = geoLocationClient.detectLocationByIp()
                if (geo != null) {
                    _locationSuggestions.value = listOf(geo)
                    _showGeoIpConfirm.value = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Location detection failed", e)
            } finally {
                _isDetectingLocation.value = false
            }
        }
    }

    /** Consume the one-shot geoIP-confirm signal after the screen reacts to it. */
    fun consumeGeoIpConfirm() {
        _showGeoIpConfirm.value = false
    }

    /**
     * Search for locations by query using Nominatim (matching desktop).
     */
    fun searchLocation(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _locationSuggestions.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            try {
                val results = geoLocationClient.searchLocations(query)
                _locationSuggestions.value = results
            } catch (e: Exception) {
                Log.w(TAG, "Location search failed", e)
            }
        }
    }

    fun clearLocationSuggestions() {
        _locationSuggestions.value = emptyList()
        _showGeoIpConfirm.value = false
    }

    fun setLocation(lat: Double, lon: Double, city: String) {
        viewModelScope.launch {
            val radius = _radiusMiles.value
            settingsStore.setConcertLocation(lat, lon, city, radius)
            _locationCity.value = city
            _hasLocation.value = true
            _locationCommitted.tryEmit(Unit)   // close the picker on any commit
            loadEvents(forceRefresh = true)
        }
    }

    fun setRadius(radiusMiles: Int) {
        viewModelScope.launch {
            _radiusMiles.value = radiusMiles
            settingsStore.setConcertRadius(radiusMiles)
            loadEvents(forceRefresh = true)
        }
    }

    fun refresh() {
        loadEvents(forceRefresh = true)
    }

    /** Called on screen resume — reloads if cache is stale. */
    fun refreshIfStale() {
        if (_hasLocation.value) {
            loadEvents(forceRefresh = false)
        }
    }

    /**
     * Load personalized events: gather artists from collection + history,
     * then search for their concerts (matching desktop's gatherConcertsArtists + fetchConcerts).
     */
    private fun loadEvents(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Gather artists from collection and listening history
                val artists = gatherArtists()
                Log.d(TAG, "Gathered ${artists.size} artists for concert search")

                if (artists.isEmpty()) {
                    // Fall back to local events by location if no library artists
                    val loc = settingsStore.getConcertLocation()
                    val lat = loc.latitude
                    val lon = loc.longitude
                    if (lat != null && lon != null) {
                        concertsRepository.getLocalEvents(
                            lat, lon, loc.radiusMiles, forceRefresh,
                        ).collect { _events.value = it }
                    } else {
                        _events.value = Resource.Success(emptyList())
                    }
                } else {
                    // Personalized: search for user's artists' concerts
                    val loc = settingsStore.getConcertLocation()
                    concertsRepository.getPersonalizedEvents(
                        artists = artists,
                        lat = loc.latitude,
                        lon = loc.longitude,
                        radiusMiles = loc.radiusMiles,
                        forceRefresh = forceRefresh,
                    ).collect { _events.value = it }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load events", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Gather artists from user's collection and listening history.
     * Matches desktop's gatherConcertsArtists():
     * 1. Collection artists (explicit artist entries)
     * 2. Library artists (from collection tracks & albums)
     * 3. History artists (Last.fm + ListenBrainz top artists, 6-month period)
     * De-duplicated by lowercase name, round-robin interleaved, max 40 artists.
     */
    private suspend fun gatherArtists(): List<ConcertArtist> {
        val collectionArtists = mutableListOf<ConcertArtist>()
        val libraryArtists = mutableListOf<ConcertArtist>()
        val historyArtists = mutableListOf<ConcertArtist>()

        try {
            // 1. Collection artists (explicit artist entries in collection)
            val artists = artistDao.getAll().first()
            for (a in artists) {
                collectionArtists.add(ConcertArtist(a.name, "collection", a.imageUrl))
            }

            // 2. Library artists (from tracks and albums in collection)
            val seenLibrary = collectionArtists.map { it.name.lowercase() }.toMutableSet()
            val tracks = trackDao.getAll().first()
            for (t in tracks) {
                val name = t.artist
                if (name.lowercase() !in seenLibrary) {
                    seenLibrary.add(name.lowercase())
                    libraryArtists.add(ConcertArtist(name, "library"))
                }
            }
            val albums = albumDao.getAll().first()
            for (a in albums) {
                val name = a.artist
                if (name.lowercase() !in seenLibrary) {
                    seenLibrary.add(name.lowercase())
                    libraryArtists.add(ConcertArtist(name, "library"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to gather collection/library artists", e)
        }

        // 3. History artists from Last.fm + ListenBrainz (6-month period, limit 40)
        try {
            val lastfmUsername = settingsStore.getLastFmUsername()
            if (lastfmUsername != null) {
                val response = lastFmClient.getUserTopArtists(
                    user = lastfmUsername,
                    period = "6month",
                    limit = 40,
                    apiKey = BuildConfig.LASTFM_API_KEY,
                )
                response.topartists?.artist?.forEach { a ->
                    historyArtists.add(ConcertArtist(a.name, "history"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Last.fm top artists", e)
        }

        try {
            val lbUsername = settingsStore.getListenBrainzUsername()
            if (lbUsername != null) {
                val lbArtists = listenBrainzClient.getUserTopArtists(
                    username = lbUsername,
                    range = "half_yearly",
                    count = 40,
                )
                for (a in lbArtists) {
                    historyArtists.add(ConcertArtist(a.name, "history"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch ListenBrainz top artists", e)
        }

        // Interleave sources for fair representation (matching desktop's round-robin)
        return interleaveAndDedupe(collectionArtists, libraryArtists, historyArtists)
    }

    /**
     * Round-robin interleave artists from multiple sources, de-duplicate by name,
     * max [MAX_ARTISTS] total (matching desktop behavior).
     */
    private fun interleaveAndDedupe(
        vararg sources: List<ConcertArtist>,
    ): List<ConcertArtist> {
        val result = mutableListOf<ConcertArtist>()
        val seen = mutableSetOf<String>()
        val iterators = sources.map { it.iterator() }.toMutableList()

        while (result.size < MAX_ARTISTS && iterators.any { it.hasNext() }) {
            val toRemove = mutableListOf<Iterator<ConcertArtist>>()
            for (iter in iterators) {
                if (result.size >= MAX_ARTISTS) break
                // Skip to next unseen artist in this source
                while (iter.hasNext()) {
                    val artist = iter.next()
                    val key = artist.name.lowercase()
                    if (key !in seen) {
                        seen.add(key)
                        result.add(artist)
                        break
                    }
                }
                if (!iter.hasNext()) toRemove.add(iter)
            }
            iterators.removeAll(toRemove)
        }
        return result
    }
}
