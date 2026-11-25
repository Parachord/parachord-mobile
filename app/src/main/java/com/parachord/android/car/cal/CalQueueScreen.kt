package com.parachord.android.car.cal

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Header
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.Action
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Template
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.shared.playback.PlaybackMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The car's queue as a `SectionedItemTemplate`: the current track, then Up
 * Next; a row tap is `playFromQueue` and that is all the queue does — no
 * reordering, no removal, no clear (the host also enforces list-length caps
 * while driving). The host's list length limits apply automatically.
 */
class CalQueueScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val playback: CalPlaybackBridge by lazy { GlobalContext.get().get() }
    private val stateHolder: PlaybackStateHolder by lazy { GlobalContext.get().get() }
    private val art: CalArt by lazy { GlobalContext.get().get() }

    /** Follows the queue: track art loads and row changes invalidate the template. */
    private var observed = false

    private fun observeState() {
        if (observed) return
        observed = true
        scope.launch {
            // The state flow ticks with the position; the queue's rows only
            // change when the current track, Up Next or the context does.
            stateHolder.state
                .map { state ->
                    listOfNotNull(
                        state.currentTrack?.id,
                        state.playbackContext?.name,
                        state.upNext.joinToString(",") { it.id },
                    )
                }
                .distinctUntilChanged()
                .collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        observeState()
        val state = stateHolder.state.value
        val current = state.currentTrack
        val upNext = state.upNext
        val contextLine = state.playbackContext?.let {
            PlaybackMessages.contextLabel(it.type, it.name)
        }
        val queue = RowSection.Builder()
            .setTitle("Queue")
            .apply {
                current?.let { track ->
                    track.artworkUrl?.let { art.load(carContext, it) { invalidate() } }
                    addItem(
                        Row.Builder()
                            .setTitle("Now Playing: ${track.title}")
                            .addText(track.artist)
                            .setImageWhenReady(art.icon(track.artworkUrl))
                            .build(),
                    )
                }
                upNext.forEachIndexed { index, track ->
                    track.artworkUrl?.let { art.load(carContext, it) { invalidate() } }
                    addItem(
                        Row.Builder()
                            .setTitle(track.title)
                            .addText(track.artist)
                            .setImageWhenReady(art.icon(track.artworkUrl))
                            .setOnClickListener {
                                playback.playFromQueue(index)
                            }
                            .build(),
                    )
                }
            }
            .setNoItemsMessage("Queue is empty")
            .build()
        return SectionedItemTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Queue")
                    .apply {
                        contextLine?.let { setTitle("$contextLine — Queue") }
                    }
                    .setStartHeaderAction(Action.BACK)
                    .build(),
            )
            .setSections(listOf(queue))
            .build()
    }

}
