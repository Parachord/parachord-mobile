package com.parachord.android.car.cal

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
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
 */
class CalMediaSession : Session() {

    val bridge: CalPlaybackBridge by lazy { GlobalContext.get().get() }

    override fun onCreateScreen(intent: Intent): Screen {
        bridge.register(carContext)
        return CalHomeScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tapping the car home's media card opens Now Playing.
        if (intent.action == ACTION_SHOW_MEDIA_PLAYBACK) {
            carContext.getCarService(androidx.car.app.ScreenManager::class.java)
                .push(CalNowPlayingScreen(carContext))
        }
    }

    private companion object {
        const val ACTION_SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"
    }
}
