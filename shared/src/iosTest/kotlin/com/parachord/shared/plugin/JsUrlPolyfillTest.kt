package com.parachord.shared.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import platform.JavaScriptCore.JSContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The URL polyfill in a real JavaScriptCore context, as the iOS plug-in host
 * runs it. Expected values are what a browser's WHATWG URL gives.
 */
@OptIn(ExperimentalForeignApi::class)
class JsUrlPolyfillTest {

    private val ctx = JSContext().also { it.evaluateScript(JsUrlPolyfill.SCRIPT) }

    private fun js(script: String): String = ctx.evaluateScript(script)?.toString() ?: "<null>"

    @Test
    fun `JavaScriptCore gets URL and URLSearchParams`() {
        assertEquals("function", js("typeof URL"))
        assertEquals("function", js("typeof URLSearchParams"))
    }

    @Test
    fun `amazonmusic's page check sees the album page as music amazon com`() {
        // The plug-in's isAmazonMusicPage: new URL(url).origin === 'https://music.amazon.com'.
        assertEquals("true", js("new URL('https://music.amazon.com/albums/B0FL7KJ5TJ').origin === 'https://music.amazon.com'"))
        assertEquals("music.amazon.com", js("new URL('https://Music.Amazon.com/albums/x').hostname"))
        assertEquals("false", js("new URL('https://www.amazon.com/ap/signin?return_to=https://music.amazon.com').hostname === 'music.amazon.com'"))
    }

    @Test
    fun `components follow WHATWG`() {
        js("var u = new URL('HTTPS://u:p@Example.com:443/a/./b/../c d?x=1 2#f g')")
        assertEquals("https://u:p@example.com/a/c%20d?x=1%202#f%20g", js("u.href"))
        assertEquals("https://example.com", js("u.origin"))
        assertEquals("", js("u.port"))
        assertEquals("/a/c%20d", js("u.pathname"))
        assertEquals("?x=1%202", js("u.search"))
        assertEquals("#f%20g", js("u.hash"))
        assertEquals("example.com:8080", js("new URL('https://example.com:8080/').host"))
        assertEquals("null", js("new URL('about:blank').origin"))
        assertEquals("blank", js("new URL('about:blank').pathname"))
    }

    @Test
    fun `relative URLs resolve against a base`() {
        assertEquals("https://a.com/b/x?y", js("new URL('../x?y', 'https://a.com/b/c/d').href"))
        assertEquals("https://cdn.x.com/a", js("new URL('//cdn.x.com/a', 'https://a.com/').href"))
        assertEquals("https://a.com/p?q=1", js("new URL('?q=1', 'https://a.com/p?z#h').href"))
        assertEquals("https://a.com/p?z#f", js("new URL('#f', 'https://a.com/p?z').href"))
    }

    @Test
    fun `an invalid URL throws a TypeError`() {
        for (bad in listOf("notaurl", "", "https://", "https://a b.com/")) {
            assertEquals("TypeError", js("(function(){ try { new URL('$bad'); return 'none'; } catch (e) { return e.name; } })()"), bad)
        }
    }

    @Test
    fun `searchParams reads and writes back to the URL`() {
        js("var s = new URL('https://a.com/p?x=1&y=a+b')")
        assertEquals("a b", js("s.searchParams.get('y')"))
        js("s.searchParams.set('x', '2 3'); s.searchParams.append('z', \"!'\")")
        assertEquals("https://a.com/p?x=2+3&y=a+b&z=%21%27", js("s.href"))
        assertEquals("a=1&b=x+y", js("String(new URLSearchParams({a: '1', b: 'x y'}))"))
    }
}
