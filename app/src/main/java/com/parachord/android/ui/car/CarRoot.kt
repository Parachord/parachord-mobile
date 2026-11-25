package com.parachord.android.ui.car

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.R
import com.parachord.android.car.CarDrivingState
import com.parachord.android.deeplink.DeepLinkNavEvent
import com.parachord.android.ui.MainActivity
import com.parachord.android.ui.MainViewModel
import com.parachord.android.ui.ParachordAppContent
import com.parachord.android.ui.components.DeepLinkConfirmationDialog
import com.parachord.android.ui.navigation.Routes
import com.parachord.android.ui.theme.PlayerSurface
import com.parachord.android.ui.theme.PlayerTextSecondary
import com.parachord.android.deeplink.DeepLinkViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** The car's own screens, in-app. */
private enum class CarScreen { HOME, NOW_PLAYING, QUEUE, SEARCH }

/**
 * The app's root on a car's own Android (AAOS): the car home (recommendations,
 * search, a now-playing bar at the bottom), its Now Playing and queue screens,
 * and — parked only, while [CarDrivingState.restricted] is false — the full
 * phone app under a close bar that comes back here.
 *
 * While the car home is up, deep links go through the same [DeepLinkViewModel]
 * the phone app feeds, so play links play; a link that only *navigates* (an
 * artist/album/page link) is dropped — its nav event finds no nav host, since
 * the full app isn't composed. Opening the full UI composes the nav host and
 * its collectors again.
 */
@Composable
fun CarRoot(mainViewModel: MainViewModel) {
    val drivingState = koinInject<CarDrivingState>()
    LaunchedEffect(Unit) { drivingState.start() }
    val restricted by drivingState.restricted.collectAsStateWithLifecycle()

    var fullUi by rememberSaveable { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf(CarScreen.HOME) }

    if (fullUi) {
        // Registered BEFORE the full app composes, so the app's own back handling
        // (its nav stack) takes precedence while it can pop; at the stack's root
        // this one fires and back returns to the car home instead of leaving the app.
        BackHandler { fullUi = false }
        Column(Modifier.fillMaxSize()) {
            CarFullUiBar(onClose = { fullUi = false })
            Box(Modifier.weight(1f)) {
                // topInsetConsumed: the bar took the status band, so the app's
                // Scaffold must not pad for it again.
                ParachordAppContent(mainViewModel, startRoute = Routes.SETTINGS, topInsetConsumed = true)
            }
        }
    } else {
        val context = LocalContext.current
        val playbackState by mainViewModel.playbackState.collectAsStateWithLifecycle()

        // A car has no snackbar corner: toasts carry what the full app shows as
        // toasts, snackbars (playback notices) and the stop reason (playbackError).
        // The same message can arrive as both a notice and the error — dedupe within
        // 2 s — and an error that stays set shows once per track, not again on every return.
        val recentToast = remember { mutableStateOf<Pair<String, Long>?>(null) }
        val shownErrors = remember { mutableStateOf<Pair<String?, String>?>(null) }
        fun say(message: String) {
            val now = System.currentTimeMillis()
            val recent = recentToast.value
            if (recent != null && recent.first == message && now - recent.second < 2_000L) return
            recentToast.value = message to now
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        LaunchedEffect(Unit) {
            mainViewModel.toastEvents.collect { say(it) }
        }
        LaunchedEffect(Unit) {
            mainViewModel.playbackNotices.collect { notice -> say(notice.message) }
        }
        LaunchedEffect(playbackState.currentTrack?.id, playbackState.playbackError) {
            val error = playbackState.playbackError ?: return@LaunchedEffect
            val key = playbackState.currentTrack?.id to error
            if (key != shownErrors.value) {
                shownErrors.value = key
                say(error)
            }
        }

        // Deep links while the car home is up: play links play (the shared
        // DeepLinkViewModel acts on the playback controller); the nav events of
        // page links are dropped (see the class doc). The two events that act
        // rather than navigate still act, and links that ask first still ask.
        val deepLinkViewModel: DeepLinkViewModel = koinViewModel()
        val activity = context as? MainActivity
        LaunchedEffect(Unit) {
            activity?.pendingDeepLink?.collect { uri ->
                if (uri != null) {
                    deepLinkViewModel.handleUri(uri)
                    activity.pendingDeepLink.value = null
                }
            }
        }
        LaunchedEffect(Unit) {
            deepLinkViewModel.navEvents.collect { event ->
                when (event) {
                    is DeepLinkNavEvent.Toast -> Toast.makeText(
                        context,
                        event.message,
                        if (event.longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                    ).show()
                    is DeepLinkNavEvent.StartListenAlong -> mainViewModel.startListenAlong(event.friend)
                    // Navigation events need the full app's nav host, which isn't
                    // composed while the car home is up — dropped.
                    else -> Unit
                }
            }
        }
        val pendingConfirmation by deepLinkViewModel.pendingConfirmation.collectAsStateWithLifecycle()
        pendingConfirmation?.let { confirmation ->
            DeepLinkConfirmationDialog(
                confirmation = confirmation,
                onConfirm = { deepLinkViewModel.confirmPendingAction() },
                onDismiss = { deepLinkViewModel.dismissPendingAction() },
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (screen) {
                CarScreen.HOME -> CarHomeScreen(
                    mainViewModel = mainViewModel,
                    restricted = restricted,
                    onOpenSearch = { screen = CarScreen.SEARCH },
                    onOpenFullUi = { fullUi = true },
                    onOpenNowPlaying = { screen = CarScreen.NOW_PLAYING },
                )
                CarScreen.NOW_PLAYING -> CarNowPlayingScreen(
                    mainViewModel = mainViewModel,
                    onBack = { screen = CarScreen.HOME },
                    onOpenQueue = { screen = CarScreen.QUEUE },
                )
                CarScreen.QUEUE -> CarQueueScreen(
                    mainViewModel = mainViewModel,
                    onBack = { screen = CarScreen.HOME },
                )
                CarScreen.SEARCH -> CarSearchScreen(
                    onBack = { screen = CarScreen.HOME },
                    onPlayed = { screen = CarScreen.NOW_PLAYING },
                )
            }
        }
        BackHandler(enabled = screen != CarScreen.HOME) { screen = CarScreen.HOME }
    }
}

/**
 * The bar over the full app. A bar (not a floating disc) so the close button
 * can't sit on top of the app's own top-right controls — and a labeled button,
 * because a bare X in the corner proved easy to miss on the car screen.
 */
@Composable
private fun CarFullUiBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PlayerSurface)
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${stringResource(R.string.app_name)} · full app",
            color = PlayerTextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = "Close", fontSize = 14.sp)
        }
    }
}
