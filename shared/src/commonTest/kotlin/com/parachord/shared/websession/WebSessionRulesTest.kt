package com.parachord.shared.websession

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Desktop's `web-session.js` predicates, ported (`tests/main-process/web-session.test.js`). */
class WebSessionRulesTest {
    private val hosts = listOf("music.amazon.com", "gql.music.amazon.com")
    private val domains = listOf("amazon.com", "music.amazon.com", "www.amazon.com")

    @Test
    fun `urlOnAllowedHost is https and an exact host`() {
        assertTrue(WebSessionRules.urlOnAllowedHost("https://music.amazon.com/search/x", hosts))
        assertTrue(WebSessionRules.urlOnAllowedHost("HTTPS://GQL.Music.Amazon.com/", hosts))
        assertTrue(WebSessionRules.urlOnAllowedHost("https://music.amazon.com:443/x", hosts))
        assertFalse(WebSessionRules.urlOnAllowedHost("http://music.amazon.com/", hosts))
        assertFalse(WebSessionRules.urlOnAllowedHost("https://www.amazon.com/", hosts))
        assertFalse(WebSessionRules.urlOnAllowedHost("https://amazon.com/", hosts))
        assertFalse(WebSessionRules.urlOnAllowedHost("https://music.amazon.com.evil.com/", hosts))
        assertFalse(WebSessionRules.urlOnAllowedHost("https://evil.com/music.amazon.com", hosts))
        assertFalse(WebSessionRules.urlOnAllowedHost("not a url", hosts))
        assertFalse(WebSessionRules.urlOnAllowedHost(null, hosts))
        assertFalse(WebSessionRules.urlOnAllowedHost("https:///x", hosts))
    }

    @Test
    fun `urlOnAllowedHost reads userinfo and backslashes the way a browser does`() {
        // The host is what follows the LAST @ of the authority.
        assertFalse(WebSessionRules.urlOnAllowedHost("https://music.amazon.com@evil.com/", hosts))
        assertTrue(WebSessionRules.urlOnAllowedHost("https://evil.com@music.amazon.com/", hosts))
        // A backslash ends the authority (WHATWG treats it as a slash).
        assertTrue(WebSessionRules.urlOnAllowedHost("https://music.amazon.com\\@evil.com/", hosts))
        // Tabs and newlines are removed before parsing.
        assertFalse(WebSessionRules.urlOnAllowedHost("https://music.amazon\n.com.evil.com/", hosts))
    }

    @Test
    fun `urlUnderCookieDomains is suffix semantics`() {
        assertTrue(WebSessionRules.urlUnderCookieDomains("https://www.amazon.com/ap/signin", domains))
        assertTrue(WebSessionRules.urlUnderCookieDomains("https://na.account.amazon.com/", domains))
        assertTrue(WebSessionRules.urlUnderCookieDomains("https://amazon.com/", listOf(".amazon.com")))
        assertFalse(WebSessionRules.urlUnderCookieDomains("https://notamazon.com/", domains))
        assertFalse(WebSessionRules.urlUnderCookieDomains("https://amazon.com.evil.com/", domains))
        assertFalse(WebSessionRules.urlUnderCookieDomains("garbage", domains))
    }

    @Test
    fun `doneWhenProblem accepts known keys with short non-empty strings`() {
        fun problem(json: String?) = WebSessionRules.doneWhenProblem(json?.let { Json.parseToJsonElement(it) })
        assertNull(problem(null))
        assertNull(problem("null"))
        assertNull(problem("""{"bodyIncludes":"searchText"}"""))
        assertNull(problem("""{"bodyIncludes":"a","urlIncludes":"b"}"""))
        assertNotNull(problem("{}"))
        assertNotNull(problem("[]"))
        assertNotNull(problem("\"x\""))
        assertNotNull(problem("""{"bodyIncludes":""}"""))
        assertNotNull(problem("""{"bodyIncludes":5}"""))
        assertNotNull(problem("""{"bodyIncludes":"a","other":"b"}"""))
        assertNotNull(problem("""{"urlIncludes":"${"x".repeat(513)}"}"""))
        assertNull(problem("""{"urlIncludes":"${"x".repeat(512)}"}"""))
    }

    @Test
    fun `satisfiesDoneWhen needs headers and every needle and never an echo`() {
        val done = DoneWhen(bodyIncludes = "searchText", urlIncludes = "/graphql")
        val capture = WebSessionCapture("https://gql.music.amazon.com/graphql", "POST", mapOf("a" to "b"), "{\"searchText\":1}", 0, false)
        assertTrue(WebSessionRules.satisfiesDoneWhen(done, capture))
        assertFalse(WebSessionRules.satisfiesDoneWhen(done, capture.copy(echo = true)))
        assertFalse(WebSessionRules.satisfiesDoneWhen(done, capture.copy(headers = null)))
        assertFalse(WebSessionRules.satisfiesDoneWhen(done, capture.copy(body = null)))
        assertFalse(WebSessionRules.satisfiesDoneWhen(done, capture.copy(url = "https://gql.music.amazon.com/other")))
        assertFalse(WebSessionRules.satisfiesDoneWhen(null, capture))
    }

    @Test
    fun `matchesCapturePattern is an anchored star glob`() {
        assertTrue(WebSessionRules.matchesCapturePattern("https://gql.music.amazon.com/", "https://gql.music.amazon.com/*"))
        assertTrue(WebSessionRules.matchesCapturePattern("https://gql.music.amazon.com/a?b=c", "https://gql.music.amazon.com/*"))
        assertTrue(WebSessionRules.matchesCapturePattern("https://music.amazon.com/NA/api/x", "https://music.amazon.com/*/api/*"))
        assertFalse(WebSessionRules.matchesCapturePattern("https://music.amazon.com/NA/other/x", "https://music.amazon.com/*/api/*"))
        assertFalse(WebSessionRules.matchesCapturePattern("https://gql.music.amazon.com", "https://gql.music.amazon.com/*"))
        assertFalse(WebSessionRules.matchesCapturePattern("https://evil.com/https://gql.music.amazon.com/", "https://gql.music.amazon.com/*"))
        assertTrue(WebSessionRules.matchesCapturePattern("https://music.amazon.com/x", "https://music.amazon.com/x"))
        assertFalse(WebSessionRules.matchesCapturePattern("https://music.amazon.com/xy", "https://music.amazon.com/x"))
    }

    @Test
    fun `setCookieNames reads names only`() {
        assertEquals(
            listOf("session-id", "at-main", "flag"),
            WebSessionRules.setCookieNames(listOf("session-id=abc; Path=/", "at-main=\"x=y\"; Secure", "flag; HttpOnly", "=novalue")),
        )
    }

    @Test
    fun `capBudget takes the smaller and leaves non-numbers absent`() {
        assertEquals(60_000L, WebSessionRules.capBudget(JsonPrimitive(999_999), WebSessionRules.LOAD_TIMEOUT_CAP_MS))
        assertEquals(5_000L, WebSessionRules.capBudget(JsonPrimitive(5_000), WebSessionRules.LOAD_TIMEOUT_CAP_MS))
        assertNull(WebSessionRules.capBudget(JsonPrimitive("5000"), WebSessionRules.LOAD_TIMEOUT_CAP_MS))
        assertNull(WebSessionRules.capBudget(null, WebSessionRules.LOAD_TIMEOUT_CAP_MS))
        assertNull(WebSessionRules.capBudget(JsonPrimitive(true), WebSessionRules.LOAD_TIMEOUT_CAP_MS))
        assertEquals(0L, WebSessionRules.capBudget(JsonPrimitive(-5), WebSessionRules.LOAD_TIMEOUT_CAP_MS))
    }
}
