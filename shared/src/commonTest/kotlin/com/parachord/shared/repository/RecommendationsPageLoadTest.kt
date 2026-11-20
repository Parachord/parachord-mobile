package com.parachord.shared.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Recommendations page loads Home's history mixes too (desktop `app.js`
 * ~L29172: the page needs their ids even when Home hasn't been visited), so
 * its Playlists row leaves them out.
 */
class RecommendationsPageLoadTest {

    @Test
    fun `a page load also loads the history mixes with the same force`() = runTest {
        val calls = mutableListOf<String>()
        loadRecommendationsPage(false, backgroundScope, mixes = { calls += "mixes:$it" }, page = { calls += "page:$it" })
        runCurrent()
        loadRecommendationsPage(true, backgroundScope, mixes = { calls += "mixes:$it" }, page = { calls += "page:$it" })
        runCurrent()
        assertEquals(setOf("mixes:false", "page:false", "mixes:true", "page:true"), calls.toSet())
        assertEquals(4, calls.size)
    }

    @Test
    fun `a slow mixes load doesn't hold back the page`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var pageDone = false
        var mixesDone = false
        val job = launch {
            loadRecommendationsPage(false, backgroundScope, mixes = { gate.await(); mixesDone = true }, page = { pageDone = true })
        }
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(pageDone)
        assertTrue(!mixesDone)
        gate.complete(Unit)
        runCurrent()
        assertTrue(mixesDone)
    }

    @Test
    fun `a failing mixes load doesn't fail the page`() = runTest {
        var pageDone = false
        loadRecommendationsPage(false, backgroundScope, mixes = { throw IllegalStateException("offline") }, page = { pageDone = true })
        runCurrent()
        assertTrue(pageDone)
    }
}
