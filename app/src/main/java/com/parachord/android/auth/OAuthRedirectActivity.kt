package com.parachord.android.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.parachord.shared.platform.Log
import com.parachord.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Dedicated receiver for OAuth callback redirects on the parachord auth scheme.
 *
 * Why this exists: Chrome Custom Tabs don't auto-close when the redirect
 * targets a custom-scheme deep link on a regular Activity — the tab stays
 * open behind the app after OAuth success. This activity is Theme.NoDisplay
 * and finishes immediately after dispatching, which causes Chrome to close
 * the Custom Tab and return focus to whatever was below it in the task stack.
 *
 * It calls OAuthManager.handleRedirect directly (rather than forwarding the
 * intent to MainActivity) so the flow is a single "redirect → handle → finish"
 * step with no intermediate activity starts that could bring Chrome back to
 * the foreground.
 *
 * security: H5 (OAuth state + CSRF) and UX fix for Chrome Custom Tab
 */
class OAuthRedirectActivity : Activity() {

    companion object {
        private const val TAG = "OAuthRedirectActivity"
    }

    private val oAuthManager: OAuthManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null) {
            Log.w(TAG, "Received OAuth redirect with null data — finishing")
            finish()
            return
        }

        // Host and path only: the query carries the auth code and state.
        Log.d(TAG, "Handling OAuth redirect: ${uri.scheme}://${uri.host}${uri.path.orEmpty()}")

        // Handle the token exchange in a fire-and-forget coroutine.
        // We don't wait for it to complete before finishing — the UI refresh
        // happens via the SettingsStore token flows in whatever screen is
        // currently observing them.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                oAuthManager.handleRedirect(uri)
            } catch (e: Exception) {
                Log.e(TAG, "OAuth handleRedirect failed: ${e.message}", e)
            }
        }

        // Bring Parachord (MainActivity's task) to the front so Chrome's
        // Custom Tab task drops behind us. We explicitly start MainActivity
        // with SINGLE_TOP + CLEAR_TOP so the existing instance stays and
        // Android routes the focus transition through our task, not Chrome's.
        val bringToFront = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(bringToFront)
        // Suppress the transition animation so the user doesn't see a flash
        // between OAuthRedirectActivity (Theme.NoDisplay) and MainActivity.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
    }
}
