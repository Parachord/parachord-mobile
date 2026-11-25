package com.parachord.android.car.cal

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.parachord.android.playback.PlaybackStateHolder
import org.koin.core.context.GlobalContext

/**
 * The car drive UI as Android for Cars **media templates**: the car's template
 * host renders every screen and enforces driver-distraction compliance, while
 * our Media3 `MediaLibraryService` (already shared with Android Auto) feeds the
 * playback state, the Assistant and the system media card. On AAOS without the
 * media templates host (Android 10–13) the car's own Media Center renders our
 * shared browse tree — the single adaptive APK declares the host feature
 * `required="false"` and the car picks what it has.
 */
class CalCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CalMediaSession()
}

/**
 * The one [Session] for the car's template UI: it returns the home [Screen]
 * (tabs: Library, For You, Search) and links our Media3 session to the host,
 * so the host's `MediaPlaybackTemplate`, the media card on the car's home and
 * its widgets stay in sync with our playback state.
 *
 * Tapping the car's media card opens Now Playing — but only once something has
 * actually played: the host renders `MediaPlaybackTemplate` from the session,
 * and with nothing playing it is an empty dark surface. Re-launching the app
 * from the car's app grid (any other intent) pops back to home.
 */
class CalMediaSession : Session() {

    private val bridge: CalPlaybackBridge by lazy { GlobalContext.get().get() }
    private val stateHolder: PlaybackStateHolder by lazy { GlobalContext.get().get() }

    override fun onCreateScreen(intent: Intent): Screen {
        bridge.register(carContext)
        return if (intent.action == ACTION_SHOW_MEDIA_PLAYBACK && hasSomethingToPlay()) {
            CalNowPlayingScreen(carContext)
        } else {
            CalHomeScreen(carContext)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val screenManager = carContext.getCarService(ScreenManager::class.java)
        if (intent.action == ACTION_SHOW_MEDIA_PLAYBACK && hasSomethingToPlay()) {
            if (screenManager.getTop() !is CalNowPlayingScreen) {
                screenManager.push(CalNowPlayingScreen(carContext))
            }
        } else {
            // The app icon (or any other re-launch) always comes back to home.
            screenManager.popToRoot()
        }
    }

    private fun hasSomethingToPlay(): Boolean =
        stateHolder.state.value.currentTrack != null

    private companion object {
        const val ACTION_SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"
    }
}
