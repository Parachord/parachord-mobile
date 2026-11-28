package com.parachord.android.car.cal

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.playback.carArtworkFor
import com.parachord.android.playback.effectiveArtwork
import com.parachord.android.playback.effectiveTrack
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.shared.playback.PlaybackMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The car's queue as a `SectionedItemTemplate`: the current track, then Up
 * Next (capped to the host's list limit); a row tap is `playFromQueue` and that
 * is all the queue does — no reordering, no removal, no clear.
 */
class CalQueueScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val playback: CalPlaybackBridge by lazy { GlobalContext.get().get() }
    private val stateHolder: PlaybackStateHolder by lazy { GlobalContext.get().get() }
    private val trackResolverCache: TrackResolverCache by lazy { GlobalContext.get().get() }
    private val art: CalArt by lazy { GlobalContext.get().get() }

    private var shownArtworkUrls: Set<String> = emptySet()

    init {
        scope.launch {
            // The state flow ticks with the position; the queue's rows only
            // change when the current track, Up Next, context, or resolved art does.
            combine(stateHolder.state, trackResolverCache.trackArtwork) { state, trackArt ->
                val cap = carListLimit(carContext)
                val currentArt = state.effectiveArtwork(trackArt)
                val upNextKeys = state.upNext.take(cap).map { t ->
                    "${t.id}:${carArtworkFor(t, trackArt).orEmpty()}"
                }
                listOf(state.currentTrack?.id, currentArt, state.playbackContext?.name, upNextKeys)
            }
                .distinctUntilChanged()
                .drop(1)
                .collect { invalidate() }
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

    override fun onGetTemplate(): Template {
        val state = stateHolder.state.value
        val trackArt = trackResolverCache.trackArtwork.value
        val current = state.effectiveTrack
        val upNext = state.upNext
        val limit = carListLimit(carContext)
        val shownUrls = HashSet<String>()
        val contextLine = state.playbackContext?.let {
            PlaybackMessages.contextLabel(it.type, it.name)
        }
        val queue = RowSection.Builder()
            .setTitle("Queue")
            .apply {
                var remaining = limit
                current?.let { track ->
                    val artUrl = state.effectiveArtwork(trackArt)
                    if (!artUrl.isNullOrBlank()) {
                        shownUrls.add(artUrl)
                        art.load(carContext, artUrl)
                    }
                    addItem(
                        Row.Builder()
                            .setTitle("Now Playing: ${track.title.ifBlank { "Untitled" }}")
                            .apply { if (track.artist.isNotBlank()) addText(track.artist) }
                            .setImageWhenReady(art.icon(artUrl))
                            .build(),
                    )
                    remaining--
                }
                upNext.take(remaining.coerceAtLeast(0)).forEachIndexed { index, track ->
                    val artUrl = carArtworkFor(track, trackArt)
                    if (!artUrl.isNullOrBlank()) {
                        shownUrls.add(artUrl)
                        art.load(carContext, artUrl)
                    }
                    addItem(
                        Row.Builder()
                            .setTitle(track.title.ifBlank { "Untitled" })
                            .apply { if (track.artist.isNotBlank()) addText(track.artist) }
                            .setImageWhenReady(art.icon(artUrl))
                            .setOnClickListener {
                                playback.playFromQueue(index)
                            }
                            .build(),
                    )
                }
            }
            .setNoItemsMessage("Queue is empty")
            .build()
        shownArtworkUrls = shownUrls
        return SectionedItemTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(contextLine?.let { "$it — Queue" } ?: "Queue")
                    .setStartHeaderAction(Action.BACK)
                    .build(),
            )
            .setSections(listOf(queue))
            .build()
    }
}
