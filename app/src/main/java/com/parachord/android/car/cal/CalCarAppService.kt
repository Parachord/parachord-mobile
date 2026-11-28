package com.parachord.android.car.cal

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.parachord.android.BuildConfig
import com.parachord.android.playback.PlaybackService
import com.parachord.android.playback.PlaybackStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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

    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Allow only the AAOS media templates host (omitting projected
            // Android Auto's gearhead until Auto's stricter 2-action header
            // and 6-item list caps are adapted).
            HostValidator.Builder(applicationContext)
                .addAllowedHost(AAOS_TEMPLATES_HOST_PACKAGE, AAOS_HOST_CERT_1)
                .addAllowedHost(AAOS_TEMPLATES_HOST_PACKAGE, AAOS_HOST_CERT_2)
                .addAllowedHost(AAOS_TEMPLATES_HOST_PACKAGE, AAOS_HOST_CERT_3)
                .build()
        }

    override fun onCreateSession(): Session = CalMediaSession()

    private companion object {
        const val AAOS_TEMPLATES_HOST_PACKAGE = "com.google.android.apps.automotive.templates.host"
        // Official signing digests from androidx.car.app hosts_allowlist_sample.
        const val AAOS_HOST_CERT_1 = "c241ffbc8e287c4e9a4ad19632ba1b1351ad361d5177b7d7b29859bd2b7fc631"
        const val AAOS_HOST_CERT_2 = "dd66deaf312d8daec7adbe85a218ecc8c64f3b152f9b5998d5b29300c2623f61"
        const val AAOS_HOST_CERT_3 = "50e603d333c6049a37bd751375d08f3bd0abebd33facd30bd17b64b89658b421"
    }
}

/**
 * The [Session] for the car's template UI: it returns the home [Screen]
 * (tabs: Library, For You, Now Playing, Search), links our Media3 session to
 * the host on every session creation and service restart, and surfaces bridge
 * error messages via `CarToast`.
 *
 * Tapping the car's media card opens Now Playing — but only once something has
 * actually played: the host renders `MediaPlaybackTemplate` from the session,
 * and with nothing playing it is an empty dark surface. Re-launching the app
 * from the car's app grid (any other intent) pops back to home.
 */
class CalMediaSession : Session() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bridge: CalPlaybackBridge by lazy { GlobalContext.get().get() }
    private val stateHolder: PlaybackStateHolder by lazy { GlobalContext.get().get() }

    init {
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE -> startSessionObservers()
                    Lifecycle.Event.ON_DESTROY -> scope.cancel()
                    else -> Unit
                }
            },
        )
    }

    private fun startSessionObservers() {
        // Scope token registration to this Session's lifecycle and react to
        // PlaybackService restarts so MediaPlaybackTemplate always has a live
        // token for this CarContext.
        scope.launch {
            try {
                bridge.ensureConnected()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep observing sessionCompatTokenFlow in case the service starts later.
            }
            PlaybackService.sessionCompatTokenFlow
                .filterNotNull()
                .collect { token ->
                    try {
                        carContext.getCarService(MediaPlaybackManager::class.java)
                            .registerMediaPlaybackToken(token)
                    } catch (e: Exception) {
                        com.parachord.shared.platform.Log.w("CalUI", "registerMediaPlaybackToken failed: ${e.message}")
                    }
                }
        }
        scope.launch {
            bridge.messages.collect { message ->
                CarToast.makeText(carContext, message, CarToast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateScreen(intent: Intent): Screen {
        return if (intent.action == ACTION_SHOW_MEDIA_PLAYBACK && hasSomethingToPlay()) {
            // Seed Home beneath Now Playing so Action.BACK always lands on Home.
            carContext.getCarService(ScreenManager::class.java).push(CalHomeScreen(carContext))
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

    internal companion object {
        const val ACTION_SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"
    }
}
