package com.parachord.android.car.cal

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Template
import org.koin.core.context.GlobalContext

/**
 * The car's Now Playing as a `MediaPlaybackTemplate`: the host renders the
 * playback controls and metadata from our registered Media3 session — no
 * playback UI of ours to build, and the host keeps it distraction-compliant by
 * construction. The header carries one custom button: the queue.
 */
class CalNowPlayingScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        MediaPlaybackTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Now Playing")
                    .addEndHeaderAction(
                        Action.Builder()
                            .setTitle("Queue")
                            .setOnClickListener {
                                screenManager.push(CalQueueScreen(carContext))
                            }
                            .build(),
                    )
                    .build(),
            )
            .build()
}
