package com.parachord.shared.websession

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebSessionCookiesTest {

    private val now = 1_000_000L
    private val jar = listOf(
        WebCookie("session-id", "s1", ".amazon.com", "/", secure = true),
        WebCookie("at-main", "a1", ".amazon.com", "/", secure = true, expiresAtMs = now + 10),
        WebCookie("old", "o1", ".amazon.com", "/", expiresAtMs = now - 1),
        WebCookie("host-only", "h1", "www.amazon.com", "/"),
        WebCookie("deep", "d1", "music.amazon.com", "/api"),
        WebCookie("empty", "", ".amazon.com", "/"),
        WebCookie("other", "x1", ".apple.com", "/"),
        WebCookie("lookalike", "l1", "notamazon.com", "/"),
    )

    @Test
    fun `a request sends the cookies that domain- path- and secure-match`() {
        assertEquals("deep=d1; session-id=s1; at-main=a1; empty=", WebSessionCookies.header(jar, "https://music.amazon.com/api/x?y=1", now))
        assertEquals("session-id=s1; at-main=a1; host-only=h1; empty=", WebSessionCookies.header(jar, "https://www.amazon.com/", now))
        // Secure cookies need https; a path that only shares a prefix doesn't match.
        assertEquals("empty=", WebSessionCookies.header(jar, "http://music.amazon.com/apix", now))
        assertNull(WebSessionCookies.header(jar, "https://example.com/", now))
    }

    @Test
    fun `state names the live non-empty cookies at each domain's root`() {
        assertEquals(
            listOf("at-main", "host-only", "session-id"),
            WebSessionCookies.namesUnder(jar, listOf("amazon.com", "music.amazon.com", "www.amazon.com"), now),
        )
    }

    @Test
    fun `clear takes the cookies under the declared domains and nothing else`() {
        val doomed = WebSessionCookies.under(jar, listOf("amazon.com", "music.amazon.com", "www.amazon.com")).map { it.name }
        assertEquals(listOf("session-id", "at-main", "old", "host-only", "deep", "empty"), doomed)
    }

    @Test
    fun `path rules`() {
        assertEquals("/", WebSessionCookies.requestPath("https://a.com"))
        assertEquals("/", WebSessionCookies.requestPath("https://a.com?x=1"))
        assertEquals("/p/q", WebSessionCookies.requestPath("https://a.com/p/q#f"))
        assertEquals(true, WebSessionCookies.pathMatches("/api/", "/api/x"))
        assertEquals(false, WebSessionCookies.pathMatches("/api", "/apix"))
    }
}
