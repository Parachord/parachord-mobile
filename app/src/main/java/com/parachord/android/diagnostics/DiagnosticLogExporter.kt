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
 * before a 414). So the issue body itself carries what fits: recent app exits
 * (with the main-thread stack of the last ANR, the evidence a freeze report
 * needs) and the most recent log lines, filled up to the URL budget. The FULL
 * text still goes to the clipboard for anyone who wants to paste more. Reports
 * used to rely on that paste alone, and in practice it never happened, so
 * issues arrived with no logs at all. No tokens are ever included: logcat lines
 * run through [redact] as defence-in-depth.
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
            val exits = recentExits()
            val log = redact(captureLogcat())
            val full = buildString {
                append(header)
                if (exits != null) append("\n\n").append(exits.full)
                append("\n\n").append(log)
            }
            copyToClipboard(full)
            val title = "Bug report — Parachord ${BuildConfig.VERSION_NAME} (${Build.MODEL})"
            Report(
                issueUrl = buildIssueUrl(title, header, exits?.brief, log),
                clipboardChars = full.length,
            )
        } catch (e: Exception) {
            com.parachord.shared.platform.Log.e(TAG, "diagnostics prepare failed: ${e.message}", e)
            null
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Parachord diagnostics", text))
    }

    /** Recent app exits: [brief] for the issue body, [full] for the clipboard. */
    data class Exits(val brief: String, val full: String)

    /**
     * The app's recent exits from [android.app.ApplicationExitInfo] (API 30+):
     * when and why each process died, and for the newest ANR within
     * [EXIT_WINDOW_MS] the main thread's stack from its trace. A freeze kills
     * the process, so by the time the user files the report the evidence is
     * here, not in logcat. Null when there's nothing recent or the API is
     * unavailable.
     */
    private fun recentExits(): Exits? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val cutoff = System.currentTimeMillis() - EXIT_WINDOW_MS
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
                .filter { it.timestamp >= cutoff }
            if (exits.isEmpty()) return null
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val lines = exits.map { e ->
                "${fmt.format(Date(e.timestamp))}  ${exitReasonName(e.reason)}" +
                    (e.description?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
            }
            val anrStack = exits.firstOrNull { it.reason == android.app.ApplicationExitInfo.REASON_ANR }
                ?.traceInputStream?.bufferedReader()?.use { it.readText() }
                ?.let { mainThreadStack(it) }
            fun render(stackLines: Int) = buildString {
                appendLine("Recent exits (newest first):")
                lines.forEach { appendLine(it) }
                if (anrStack != null) {
                    appendLine()
                    appendLine("Last ANR, main thread:")
                    anrStack.lineSequence().take(stackLines).forEach { appendLine(it) }
                }
            }.trimEnd()
            Exits(brief = render(BRIEF_STACK_LINES), full = render(Int.MAX_VALUE))
        } catch (e: Exception) {
            com.parachord.shared.platform.Log.w(TAG, "exit-info read failed: ${e.message}")
            null
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR (app froze)"
        android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by user"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        else -> "other ($reason)"
    }

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
            if (text.length > MAX_CHARS) "…(older log lines truncated to fit)…\n" + text.takeLast(MAX_CHARS) else text
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

        private const val ISSUE_BASE = "https://github.com/Parachord/parachord-mobile/issues/new"
        // GitHub answers 414 a little past 8 KB of URL; stay well under it.
        internal const val MAX_URL_CHARS = 7_600
        private const val EXIT_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
        private const val MAX_EXITS = 5
        private const val BRIEF_STACK_LINES = 30
        private const val MAX_EXITS_CHARS = 2_500

        private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        /**
         * The prefilled new-issue URL: the template, the environment [header],
         * the [exits] summary, and as many of the most RECENT [log] lines as fit
         * in [maxUrlChars] (a just-happened bug's evidence is at the tail). The
         * header and exits always go in; only the log is trimmed.
         */
        internal fun buildIssueUrl(
            title: String,
            header: String,
            exits: String?,
            log: String,
            maxUrlChars: Int = MAX_URL_CHARS,
        ): String {
            // A long ANR stack must not crowd out the log or overflow the URL.
            val exits = exits?.let { if (it.length > MAX_EXITS_CHARS) it.take(MAX_EXITS_CHARS) + "\n…" else it }
            fun body(logTail: String?) = buildString {
                appendLine("## What happened?")
                appendLine("<!-- Describe the problem: what you tapped, what you expected, what happened. -->")
                appendLine()
                appendLine("## Diagnostics")
                appendLine("<!-- The full log is on your clipboard if you want to paste more below. -->")
                appendLine()
                appendLine("<details><summary>Environment</summary>")
                appendLine()
                appendLine("```")
                appendLine(header)
                appendLine("```")
                appendLine("</details>")
                if (exits != null) {
                    appendLine()
                    appendLine("<details><summary>Recent app exits</summary>")
                    appendLine()
                    appendLine("```")
                    appendLine(exits)
                    appendLine("```")
                    appendLine("</details>")
                }
                if (logTail != null) {
                    appendLine()
                    appendLine("<details><summary>Recent log</summary>")
                    appendLine()
                    appendLine("```")
                    appendLine(logTail)
                    appendLine("```")
                    append("</details>")
                }
            }
            fun url(b: String) = "$ISSUE_BASE?labels=bug&title=${encode(title)}&body=${encode(b)}"

            val withoutLog = url(body(null))
            // Room for the log section: what's left after the fixed parts and
            // the section's own wrapper.
            val wrapperCost = url(body("")).length - withoutLog.length
            var room = maxUrlChars - withoutLog.length - wrapperCost
            if (room <= 0) return withoutLog
            val kept = ArrayDeque<String>()
            for (line in log.trimEnd().lines().asReversed()) {
                val cost = encode(line + "\n").length
                if (cost > room) break
                kept.addFirst(line)
                room -= cost
            }
            return if (kept.isEmpty()) withoutLog else url(body(kept.joinToString("\n")))
        }

        /**
         * The `"main"` thread's block from an ANR trace: from its header line to
         * the blank line that ends it. The whole trace if the block isn't found.
         */
        internal fun mainThreadStack(trace: String): String {
            val lines = trace.lines()
            val start = lines.indexOfFirst { it.startsWith("\"main\"") }
            if (start < 0) return trace.trim()
            val end = (start until lines.size).firstOrNull { it > start && lines[it].isBlank() } ?: lines.size
            return lines.subList(start, end).joinToString("\n")
        }
        // Cap so the copied text pastes into a GitHub issue (~64 KB body limit)
        // and Slack / other apps without being rejected or silently truncated.
        // Keep the most RECENT slice — a just-happened bug's evidence is at the tail.
        private const val MAX_LINES = 1500
        private const val MAX_CHARS = 45_000
        private const val MASK = "«redacted»"

        private val REDACTIONS = listOf(
            Regex("""(?i)(authorization[:=]\s*bearer\s+)[A-Za-z0-9._\-]+"""),
            Regex("""(?i)((?:access|refresh|user|api|auth)[_-]?token["']?\s*[:=]\s*["']?)[A-Za-z0-9._\-]{8,}"""),
            Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-]{12,}"""),
        )
    }
}
