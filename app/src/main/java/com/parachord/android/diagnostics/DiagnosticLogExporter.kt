package com.parachord.android.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.parachord.android.BuildConfig
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collects the app's own diagnostic logs for a bug report and hands them off to
 * a prefilled GitHub issue (Settings → About → "Report a bug with logs").
 *
 * On Android an app can read its OWN process logs via `logcat -d` with no special
 * permission (JELLY_BEAN restricts `logcat` to the calling UID), so this captures
 * everything the shared [com.parachord.shared.platform.Log] wrote (ResolverManager,
 * Spotify/Apple Music handlers, sync, scrobbler) PLUS native Media3/WebView logs —
 * exactly the context for a "no Spotify badge / Apple Music login screen" report
 * (#327).
 *
 * The logs can be 100s of KB, which won't fit in a GitHub new-issue URL (~8 KB
 * before a 414). So the flow is: copy the FULL text to the clipboard, then open a
 * prefilled issue whose body tells the user to paste. No tokens are ever
 * included — logcat lines run through [redact] as defence-in-depth.
 */
class DiagnosticLogExporter(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    data class Report(val issueUrl: String, val clipboardChars: Int)

    /**
     * Collect diagnostics, copy the full text to the clipboard, and return a
     * prefilled GitHub new-issue URL to open. Returns null on failure. Runs the
     * blocking `logcat` exec off-main.
     */
    suspend fun prepareBugReport(): Report? = withContext(Dispatchers.IO) {
        try {
            val header = buildHeader()
            val full = header + "\n\n" + redact(captureLogcat())
            copyToClipboard(full)
            Report(issueUrl = buildIssueUrl(header), clipboardChars = full.length)
        } catch (e: Exception) {
            com.parachord.shared.platform.Log.e(TAG, "diagnostics prepare failed: ${e.message}", e)
            null
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Parachord diagnostics", text))
    }

    /**
     * Prefilled issue: title + a template body. The header is small enough to
     * embed directly (so the issue always carries version/device even if the
     * user forgets to paste); the full log rides the clipboard.
     */
    private fun buildIssueUrl(header: String): String {
        val title = "Bug report — Parachord ${BuildConfig.VERSION_NAME} (${Build.MODEL})"
        val body = buildString {
            appendLine("## What happened?")
            appendLine("<!-- Describe the problem: what you tapped, what you expected, what happened. -->")
            appendLine()
            appendLine("## Diagnostics")
            appendLine("<!-- Your full logs are on the clipboard — long-press below and Paste. -->")
            appendLine()
            appendLine("<details><summary>Environment</summary>")
            appendLine()
            appendLine("```")
            append(header)
            appendLine()
            appendLine("```")
            append("</details>")
        }
        return "https://github.com/Parachord/parachord-mobile/issues/new" +
            "?labels=bug&title=" + encode(title) + "&body=" + encode(body)
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private suspend fun buildHeader(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date())
        val spotify = if (settingsStore.getSpotifyAccessToken().isNullOrBlank()) "not connected" else "connected"
        val appleMusic = if (settingsStore.getAppleMusicUserToken().isNullOrBlank()) "not connected" else "connected"
        return buildString {
            appendLine("Parachord diagnostics")
            appendLine("generated:   $ts")
            appendLine("app:         ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}")
            appendLine("device:      ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android:     ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("locale:      ${Locale.getDefault()}")
            appendLine("spotify:     $spotify")
            append("apple music: $appleMusic")
        }
    }

    /** Dump this process's recent logcat buffer (time-formatted), capped. */
    private fun captureLogcat(): String {
        return try {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "threadtime", "-t", MAX_LINES.toString(),
            ).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (text.length > MAX_CHARS) "…(truncated)…\n" + text.takeLast(MAX_CHARS) else text
        } catch (e: Exception) {
            "logcat capture failed: ${e.message}"
        }
    }

    /**
     * Defence-in-depth redaction — strip anything token/secret-shaped from the
     * log body even though the app doesn't log secrets. Conservative: only masks
     * obvious key=value / bearer patterns, never ordinary text.
     */
    private fun redact(raw: String): String {
        var out = raw
        for (re in REDACTIONS) out = re.replace(out) { m ->
            val g = m.groupValues
            if (g.size >= 2 && g[1].isNotEmpty()) "${g[1]}$MASK" else MASK
        }
        return out
    }

    companion object {
        private const val TAG = "DiagnosticLogExporter"
        private const val MAX_LINES = 5000
        private const val MAX_CHARS = 512 * 1024
        private const val MASK = "«redacted»"

        private val REDACTIONS = listOf(
            Regex("""(?i)(authorization[:=]\s*bearer\s+)[A-Za-z0-9._\-]+"""),
            Regex("""(?i)((?:access|refresh|user|api|auth)[_-]?token["']?\s*[:=]\s*["']?)[A-Za-z0-9._\-]{8,}"""),
            Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-]{12,}"""),
        )
    }
}
