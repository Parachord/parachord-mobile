package com.parachord.android.websession

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.parachord.android.app.CurrentActivityHolder
import com.parachord.shared.websession.SignInWindow
import java.util.concurrent.ConcurrentHashMap

/**
 * The visible sign-in screen of a web session: a full-screen WebView on the
 * app's shared `CookieManager` (so the session's cookies land where the hidden
 * page reads them), with the host config's title and a close button. No
 * capture script and no JS bridge run in it, so a sign-in form's body (the
 * password included) never reaches a capture. It follows the service's
 * identity hops; its start URL is scoped by the bridge.
 *
 * [open] starts it and returns the [SignInWindow] the shared host ticks over.
 */
class WebSessionSignInActivity : ComponentActivity() {

    private var signInHandle: Handle? = null
    private var web: WebView? = null

    /** The [SignInWindow] side: alive across the start, read by the host on the main thread. */
    class Handle internal constructor(val token: String) : SignInWindow {
        @Volatile internal var url: String? = null
        @Volatile private var closed = false
        @Volatile internal var activity: WebSessionSignInActivity? = null
        @Volatile internal var closeRequested = false

        override fun currentUrl(): String? = url
        override val isClosed: Boolean get() = closed

        override fun close() {
            closeRequested = true
            val a = activity
            if (a != null) a.runOnUiThread { a.finish() } else markClosed()
        }

        internal fun markClosed() {
            closed = true
            activity = null
            handles.remove(token)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val handle = token?.let { handles[it] }
        val startUrl = intent.getStringExtra(EXTRA_URL)
        if (handle == null || startUrl == null || handle.closeRequested) {
            handle?.markClosed()
            finish()
            return
        }
        signInHandle = handle
        handle.activity = this

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            intent.getStringExtra(EXTRA_USER_AGENT)?.let { settings.userAgentString = it }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Identity hops are followed; only http(s) loads here.
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "https" && scheme != "http"
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    handle.url = url
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    handle.url = url
                    CookieManager.getInstance().flush()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    handle.url = url
                }
            }
        }
        web = webView
        setContentView(layout(intent.getStringExtra(EXTRA_TITLE).orEmpty(), webView))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        handle.url = startUrl
        webView.loadUrl(startUrl)
    }

    private fun layout(title: String, webView: WebView): View {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#161616"))
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }
        bar.addView(
            TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#F3F4F6"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(
            TextView(this).apply {
                text = "✕"
                contentDescription = "Close"
                setTextColor(Color.parseColor("#F3F4F6"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161616"))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return FrameLayout(this).apply { addView(root) }
    }

    override fun onDestroy() {
        web?.let {
            it.stopLoading()
            it.destroy()
        }
        web = null
        // configChanges keeps rotation from recreating this screen, so a
        // destroy is the screen going away.
        signInHandle?.markClosed()
        signInHandle = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_USER_AGENT = "userAgent"
        private val handles = ConcurrentHashMap<String, Handle>()

        /** Start the screen from the activity in front (else the app) and return its handle. */
        fun open(context: Context, url: String, title: String, userAgent: String?): SignInWindow {
            val token = java.util.UUID.randomUUID().toString()
            val handle = Handle(token)
            handles[token] = handle
            val from: Context = CurrentActivityHolder.current ?: context
            val intent = Intent(from, WebSessionSignInActivity::class.java)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            userAgent?.let { intent.putExtra(EXTRA_USER_AGENT, it) }
            try {
                from.startActivity(intent)
            } catch (e: Exception) {
                handle.markClosed()
                throw IllegalStateException("the sign-in screen could not open: ${e.message}")
            }
            return handle
        }
    }
}
