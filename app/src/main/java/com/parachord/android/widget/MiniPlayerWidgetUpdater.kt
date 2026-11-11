package com.parachord.android.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.parachord.shared.platform.Log
import android.util.LruCache
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.playback.effectiveArtwork
import com.parachord.android.playback.effectiveTrack
import com.parachord.android.resolver.TrackResolverCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Observes [PlaybackStateHolder] and pushes updates to the mini player widget.
 *
 * Also registers a broadcast receiver for widget button presses and forwards
 * them to [PlaybackController]. This lives as a Koin singleton so it can
 * access injected dependencies that the plain [AppWidgetProvider] cannot.
 */
class MiniPlayerWidgetUpdater constructor(
    private val context: Context,
    private val stateHolder: PlaybackStateHolder,
    private val playbackControllerLazy: Lazy<PlaybackController>,
    private val trackResolverCache: TrackResolverCache,
) {
    companion object {
        private const val TAG = "WidgetUpdater"

        // Cached last-known state so the widget provider can read it
        // during onUpdate() without needing DI.
        @Volatile var lastTitle: String? = null
        @Volatile var lastArtist: String? = null
        @Volatile var lastIsPlaying: Boolean = false
        @Volatile var lastProgress: Float = 0f
        @Volatile var lastArtworkBitmap: Bitmap? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null
    private var artworkFetchJob: Job? = null

    /** Simple in-memory cache for decoded artwork bitmaps keyed by URL. */
    private val artworkCache = LruCache<String, Bitmap>(5)
    /** The cover URL and placeholder name the shown artwork is for. */
    private var lastArtworkKey: Pair<String?, String>? = null

    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getStringExtra("action")) {
                "play_pause" -> playbackControllerLazy.value.togglePlayPause()
                "skip_next" -> playbackControllerLazy.value.skipNext()
                "skip_previous" -> playbackControllerLazy.value.skipPrevious()
            }
        }
    }

    /** Start observing playback state and pushing widget updates. */
    fun startObserving() {
        if (observeJob != null) return

        // Register for widget media action broadcasts
        val filter = IntentFilter("com.parachord.android.WIDGET_MEDIA_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(mediaActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(mediaActionReceiver, filter)
        }

        observeJob = scope.launch {
            combine(stateHolder.state, trackResolverCache.trackArtwork) { state, trackArtwork ->
                state to trackArtwork
            }.collectLatest { (state, trackArtwork) ->
                // Use effectiveTrack (currentTrack overlaid with streaming
                // metadata from Spotify Connect / Apple Music MusicKit) so
                // the widget shows the same title/artist/artwork that the
                // Now Playing screen and MediaSession notification show.
                // Reading state.currentTrack directly meant the widget got
                // whatever the resolver guessed at queue-time — possibly
                // null artwork (local file with no embedded art waiting on
                // ImageEnrichmentService) or a wrong-track placeholder
                // when the streaming source corrected the metadata.
                val track = state.effectiveTrack
                val title = track?.title
                val artist = track?.artist
                val isPlaying = state.isPlaying
                val progress = if (state.duration > 0) {
                    state.position.toFloat() / state.duration.toFloat()
                } else {
                    0f
                }

                // Update cached state
                lastTitle = title
                lastArtist = artist
                lastIsPlaying = isPlaying
                lastProgress = progress

                // The cover by the mini player's chain (effectiveArtwork: the
                // streaming service's, the track's own, then its resolved
                // sources'). Until it loads, or when there's none, the app's
                // placeholder: a gradient with the artist's initials, as the
                // mini player shows. Fetched in a separate job so collectLatest
                // doesn't cancel the request when the next position update arrives.
                val artworkUrl = state.effectiveArtwork(trackArtwork)
                val placeholderName = artist?.takeIf { it.isNotBlank() } ?: title.orEmpty()
                val key = if (track == null) null else artworkUrl to placeholderName
                if (key != lastArtworkKey) {
                    lastArtworkKey = key
                    artworkFetchJob?.cancel()
                    lastArtworkBitmap = if (track == null) null else placeholder(placeholderName)
                    if (key != null && artworkUrl != null) {
                        artworkFetchJob = scope.launch {
                            val bitmap = fetchArtwork(artworkUrl) ?: return@launch
                            // Only apply if this is still the current artwork
                            if (lastArtworkKey == key) {
                                lastArtworkBitmap = bitmap
                                pushUpdate()
                            }
                        }
                    }
                }

                pushUpdate()
            }
        }

        // Also run a periodic position update for the progress bar
        // (state only emits on changes, but position updates may be batched)
        scope.launch {
            while (true) {
                delay(2000)
                val state = stateHolder.state.value
                if (state.isPlaying && state.duration > 0) {
                    lastProgress = state.position.toFloat() / state.duration.toFloat()
                    pushUpdate()
                }
            }
        }
    }

    private fun pushUpdate() {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, MiniPlayerWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return

        val views = MiniPlayerWidgetProvider.buildRemoteViews(
            context,
            title = lastTitle,
            artist = lastArtist,
            isPlaying = lastIsPlaying,
            progress = lastProgress,
            artworkBitmap = lastArtworkBitmap,
        )

        for (id in ids) {
            manager.updateAppWidget(id, views)
        }
    }

    /** The app's placeholder for [name] at the widget's art size (48dp). */
    private fun placeholder(name: String): Bitmap {
        val cacheKey = "placeholder:$name"
        artworkCache.get(cacheKey)?.let { return it }
        val density = context.resources.displayMetrics.density
        // The mini player's initials are 35% of the art size, in sp.
        val fontPx = 48f * 0.35f * context.resources.displayMetrics.scaledDensity
        return widgetArtPlaceholder(name, (48 * density).toInt(), fontPx)
            .also { artworkCache.put(cacheKey, it) }
    }

    private suspend fun fetchArtwork(url: String): Bitmap? = withContext(Dispatchers.IO) {
        // Check cache first
        artworkCache.get(url)?.let { return@withContext it }

        try {
            // Local files' covers are content:// (MediaStore album art) or
            // file:// (embedded art) addresses, which URL can't open.
            val uri = android.net.Uri.parse(url)
            val stream = when (uri.scheme?.lowercase()) {
                "content", "file", "android.resource" -> context.contentResolver.openInputStream(uri)
                else -> URL(url).openConnection().apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }.getInputStream()
            } ?: return@withContext null
            val original = stream.use { BitmapFactory.decodeStream(it) }

            // Scale down for widget display (48dp ~ 144px at xxxhdpi)
            val scaled = if (original != null && (original.width > 200 || original.height > 200)) {
                Bitmap.createScaledBitmap(original, 144, 144, true).also {
                    if (it !== original) original.recycle()
                }
            } else {
                original
            }

            scaled?.let { artworkCache.put(url, it) }
            scaled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch widget artwork: $url", e)
            null
        }
    }
}
