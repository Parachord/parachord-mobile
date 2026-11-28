package com.parachord.android.car.cal

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The car's Now Playing as a `MediaPlaybackTemplate`: the host renders the
 * playback controls (including the Heart/Love custom action published by
 * `PlaybackService`'s Media3 session) and metadata from our registered session.
 * The header carries the extra Now Playing controls the host's playback bar has
 * no room for: Spinoff (the shared `carNowPlayingButtons` rule), Shuffle (the
 * shared `shuffleAllowed` rule), and Queue.
 */
class CalNowPlayingScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val playback: CalPlaybackBridge by lazy { GlobalContext.get().get() }
    private val stateHolder: PlaybackStateHolder by lazy { GlobalContext.get().get() }

    init {
        scope.launch {
            stateHolder.state
                .map { state ->
                    listOf(
                        state.currentTrack != null,
                        state.shuffleEnabled,
                        state.spinoffMode,
                        state.spinoffAvailable,
                        state.spinoffLoading,
                        state.playbackContext?.type,
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (stateHolder.state.value.currentTrack == null) {
                        // Playback stopped and cleared — remove this screen
                        // specifically (even if CalQueueScreen is above it) so
                        // the user is never stranded on an empty dark surface.
                        screenManager.remove(this@CalNowPlayingScreen)
                    } else {
                        invalidate()
                    }
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
        val header = Header.Builder()
            .setTitle("Now Playing")
            .setStartHeaderAction(Action.BACK)
        // Heart/Love is already rendered on the playback bar from our Media3
        // session's custom action, so only Spinoff is read from the shared rule
        // for the header (passing loved = false ignores the heart slot).
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
                            IconCompat.createWithResource(
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
                        IconCompat.createWithResource(
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
}
