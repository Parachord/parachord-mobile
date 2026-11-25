package com.parachord.android.car.cal

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Template
import androidx.car.app.media.model.MediaPlaybackTemplate
import com.parachord.android.R
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.shared.car.CarNowPlayingButton
import com.parachord.shared.car.carNowPlayingButtons
import com.parachord.shared.playback.shuffleAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The car's Now Playing as a `MediaPlaybackTemplate`: the host renders the
 * playback controls and metadata from our registered Media3 session — no
 * playback UI of ours to build, and the host keeps it distraction-compliant by
 * construction. The header carries the extra Now Playing controls the host's
 * playback view has no room for: Shuffle, Spinoff (the shared
 * `carNowPlayingButtons` rule — the fork in a disc while it runs, hidden when
 * nothing similar exists, and a tap while the pool builds does nothing), and
 * the Queue button.
 */
class CalNowPlayingScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val playback: CalPlaybackBridge by lazy { GlobalContext.get().get() }
    private val stateHolder: PlaybackStateHolder by lazy { GlobalContext.get().get() }

    /** Follows the button state: a shuffle/spinoff change re-renders the header. */
    private var observed = false

    private fun observeState() {
        if (observed) return
        observed = true
        scope.launch {
            stateHolder.state
                .map { state ->
                    listOf(
                        state.shuffleEnabled,
                        state.spinoffMode,
                        state.spinoffAvailable,
                        state.spinoffLoading,
                        state.playbackContext?.type,
                    )
                }
                .distinctUntilChanged()
                .collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        observeState()
        val state = stateHolder.state.value
        val header = Header.Builder()
            .setTitle("Now Playing")
        // Spinoff: the shared rule that decides the Android Auto buttons — while
        // the pool builds it already reads as "end", and when nothing similar
        // exists the button is hidden entirely.
        val spinoffButton = carNowPlayingButtons(
            loved = false,
            spinoffMode = state.spinoffMode,
            spinoffAvailable = state.spinoffAvailable,
            spinoffLoading = state.spinoffLoading,
        ).firstOrNull { it == CarNowPlayingButton.SPINOFF || it == CarNowPlayingButton.END_SPINOFF }
        if (spinoffButton != null) {
            val active = spinoffButton == CarNowPlayingButton.END_SPINOFF
            header.addEndHeaderAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            androidx.core.graphics.drawable.IconCompat.createWithResource(
                                carContext,
                                if (active) R.drawable.ic_auto_spinoff_active else R.drawable.ic_auto_spinoff,
                            ),
                        ).build(),
                    )
                    .setTitle(if (active) "End Spinoff" else "Spinoff")
                    .setOnClickListener { playback.toggleSpinoff() }
                    .build(),
            )
        }
        // Shuffle: disabled (never hidden) during a spinoff or Listen Along, the
        // shared rule — the phone's button is disabled there too.
        val shuffleAllowed = shuffleAllowed(state.spinoffMode, state.playbackContext?.type)
        header.addEndHeaderAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        androidx.core.graphics.drawable.IconCompat.createWithResource(
                            carContext,
                            if (state.shuffleEnabled) R.drawable.ic_auto_shuffle_active else R.drawable.ic_auto_shuffle,
                        ),
                    ).build(),
                )
                .setTitle("Shuffle")
                .setEnabled(shuffleAllowed)
                .setOnClickListener { playback.toggleShuffle() }
                .build(),
        )
        header.addEndHeaderAction(
            Action.Builder()
                .setTitle("Queue")
                .setOnClickListener {
                    screenManager.push(CalQueueScreen(carContext))
                }
                .build(),
        )
        return MediaPlaybackTemplate.Builder()
            .setHeader(header.build())
            .build()
    }

    init {
        lifecycle.addObserver(object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(
                source: androidx.lifecycle.LifecycleOwner,
                event: androidx.lifecycle.Lifecycle.Event,
            ) {
                if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                    scope.cancel()
                }
            }
        })
    }
}
