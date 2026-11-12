package com.parachord.shared.api.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundCloudPluginRetryTest {
    private val rejected = IllegalStateException("Plugin call failed: SoundCloud search failed: 401")

    @Test
    fun `the plugin's 401 is a rejection — other failures are not`() {
        assertTrue(isSoundCloudRejection(rejected))
        assertTrue(isSoundCloudRejection(IllegalStateException("soundcloud resolve failed: SoundCloud search failed: 401")))
        assertFalse(isSoundCloudRejection(IllegalStateException("Plugin call failed: SoundCloud search failed: 503")))
        assertFalse(isSoundCloudRejection(IllegalStateException("Plugin call didn't answer in time")))
    }

    @Test
    fun `a 401 refreshes once with the token read before the call — then asks again`() = runTest {
        var calls = 0
        val refreshedWith = mutableListOf<String?>()
        val result = retryAfterSoundCloudRejection(
            "soundcloud",
            currentToken = { "old" },
            refreshAfterRejection = { refreshedWith += it; true },
        ) {
            calls++
            if (calls == 1) throw rejected
            "found"
        }
        assertEquals("found", result)
        assertEquals(2, calls)
        assertEquals(listOf<String?>("old"), refreshedWith)
    }

    @Test
    fun `a failed refresh passes the 401 on — so the resolve is unanswered`() = runTest {
        var calls = 0
        val e = assertFailsWith<IllegalStateException> {
            retryAfterSoundCloudRejection("soundcloud", { "old" }, { false }) { calls++; throw rejected }
        }
        assertTrue(isSoundCloudRejection(e))
        assertEquals(1, calls)
    }

    @Test
    fun `a second 401 isn't retried again`() = runTest {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            retryAfterSoundCloudRejection("soundcloud", { "old" }, { true }) { calls++; throw rejected }
        }
        assertEquals(2, calls)
    }

    @Test
    fun `other failures and other resolvers pass through untouched`() = runTest {
        var refreshes = 0
        assertFailsWith<IllegalStateException> {
            retryAfterSoundCloudRejection("soundcloud", { "t" }, { refreshes++; true }) {
                throw IllegalStateException("Plugin call failed: SoundCloud search failed: 503")
            }
        }
        var tokenReads = 0
        assertFailsWith<IllegalStateException> {
            retryAfterSoundCloudRejection("bandcamp", { tokenReads++; "t" }, { refreshes++; true }) { throw rejected }
        }
        assertEquals(0, refreshes)
        assertEquals(0, tokenReads)
        assertEquals(null, retryAfterSoundCloudRejection<String?>("soundcloud", { "t" }, { true }) { null })
    }
}
