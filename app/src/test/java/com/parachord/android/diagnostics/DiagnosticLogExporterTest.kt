package com.parachord.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * The bug-report issue carries its own evidence: reports filed through the
 * clipboard-paste flow arrived with no logs at all (#447, #448, #449).
 */
class DiagnosticLogExporterTest {

    private val header = "Parachord diagnostics\napp:         0.9.9 (23) debug"

    private fun body(url: String): String =
        URLDecoder.decode(url.substringAfter("&body="), "UTF-8")

    private fun log(lines: Int) = (1..lines).joinToString("\n") {
        "10-28 13:47:0${it % 10}.123  1234  5678 D PlaybackController: line $it of the log"
    }

    @Test fun `the issue carries the newest log lines`() {
        val url = DiagnosticLogExporter.buildIssueUrl("Bug report", header, null, log(5))
        val b = body(url)
        assertTrue(b.contains("<summary>Recent log</summary>"))
        (1..5).forEach { assertTrue("line $it", b.contains("line $it of the log")) }
        assertTrue(b.contains(header))
    }

    @Test fun `a long log is trimmed from the oldest end and the url stays under the limit`() {
        val url = DiagnosticLogExporter.buildIssueUrl("Bug report", header, null, log(2_000))
        assertTrue("url ${url.length} chars", url.length <= DiagnosticLogExporter.MAX_URL_CHARS)
        val b = body(url)
        assertTrue(b.contains("line 2000 of the log"))
        assertFalse(b.contains("line 1 of the log\n"))
        assertTrue(b.trimEnd().endsWith("</details>"))
    }

    @Test fun `exits always go in, and a long ANR stack is capped`() {
        val stack = (1..400).joinToString("\n") { "  at com.parachord.android.Foo.bar$it(Foo.kt:$it)" }
        val exits = "Recent exits (newest first):\n2026-10-28 13:45:02  ANR (app froze)\n\nLast ANR, main thread:\n$stack"
        val url = DiagnosticLogExporter.buildIssueUrl("Bug report", header, exits, log(2_000))
        assertTrue("url ${url.length} chars", url.length <= DiagnosticLogExporter.MAX_URL_CHARS)
        val b = body(url)
        assertTrue(b.contains("ANR (app froze)"))
        assertTrue(b.contains("Foo.bar1("))
        assertFalse(b.contains("Foo.bar400("))
        assertTrue(b.contains("line 2000 of the log"))
    }

    @Test fun `mainThreadStack keeps only the main thread block`() {
        val trace = """
            ----- pid 1234 at 2026-10-28 13:45:02 -----
            "Signal Catcher" daemon prio=10 tid=2 Runnable
              at x.y(Z.java:1)

            "main" prio=5 tid=1 Blocked
              at android.webkit.WebView.evaluateJavascript(WebView.java:10)
              at com.parachord.android.bridge.JsBridge.evaluate(JsBridge.kt:102)

            "OkHttp" prio=5 tid=20 Waiting
              at a.b(C.java:3)
        """.trimIndent()
        assertEquals(
            "\"main\" prio=5 tid=1 Blocked\n" +
                "  at android.webkit.WebView.evaluateJavascript(WebView.java:10)\n" +
                "  at com.parachord.android.bridge.JsBridge.evaluate(JsBridge.kt:102)",
            DiagnosticLogExporter.mainThreadStack(trace),
        )
    }
}
