package com.parachord.shared.api.auth

import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SoundCloud token handling, a port of desktop's `soundcloud-token.test.js`:
 * the code exchange, refresh on expiry, one refresh at a time (refresh tokens
 * are single-use), and a dead refresh token signing out only when it's still
 * the stored one.
 */
class SoundCloudAuthTest {

    private class FakeSecureStore : SecureTokenStore {
        val m = mutableMapOf<String, String>()
        override fun get(key: String): String? = m[key]
        override fun set(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun contains(key: String): Boolean = m.containsKey(key)
        override fun observe(key: String): Flow<String?> = flowOf(m[key])
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private var now = 1_000_000_000L

    private class Rig(val auth: SoundCloudAuth, val store: SettingsStore, val secure: FakeSecureStore, val bodies: MutableList<String>)

    /** [answer] maps the n-th token request (0-based) to (status, body). */
    private fun rig(answer: (Int) -> Pair<HttpStatusCode, String>): Rig {
        val secure = FakeSecureStore()
        val store = SettingsStore(secure, KvStore(MapSettings()))
        secure.set("soundcloud_client_id", "CID")
        secure.set("soundcloud_client_secret", "SECRET")
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            bodies += (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val (status, body) = answer(bodies.size - 1)
            respond(body, status, jsonHeaders)
        }
        return Rig(SoundCloudAuth(HttpClient(engine), store, nowMs = { now }), store, secure, bodies)
    }

    private fun ok(access: String, refresh: String? = "R2", expiresIn: Long = 3600) =
        HttpStatusCode.OK to buildString {
            append("""{"access_token":"$access","expires_in":$expiresIn""")
            if (refresh != null) append(""","refresh_token":"$refresh"""")
            append("}")
        }

    private fun Rig.signedIn(access: String = "A1", refresh: String = "R1", expiresAt: Long = now + 60_000) {
        secure.set("soundcloud_access_token", access)
        secure.set("soundcloud_refresh_token", refresh)
        secure.set("soundcloud_token_expiry", expiresAt.toString())
        secure.set("soundcloud_last_refresh", now.toString())
    }

    @Test
    fun exchange_stores_tokens_and_expiry() = runTest {
        val r = rig { ok("A1", "R1") }
        r.auth.exchangeCode("CODE", "VERIFIER")
        assertEquals("A1", r.store.getSoundCloudToken())
        assertEquals("R1", r.store.getSoundCloudRefreshToken())
        assertEquals(now + 3_600_000, r.store.getSoundCloudTokenExpiresAt())
        val body = r.bodies.single()
        assertTrue("grant_type=authorization_code" in body)
        assertTrue("code_verifier=VERIFIER" in body)
        assertTrue("client_secret=SECRET" in body)
        assertTrue("redirect_uri=parachord%3A%2F%2Fauth%2Fcallback%2Fsoundcloud" in body)
    }

    @Test
    fun exchange_failure_throws_with_soundclouds_reason() = runTest {
        val r = rig { HttpStatusCode.BadRequest to """{"error":"invalid_grant","error_description":"Code expired"}""" }
        val e = assertFailsWith<IllegalStateException> { r.auth.exchangeCode("CODE", "V") }
        assertTrue("Code expired" in e.message.orEmpty())
        assertNull(r.store.getSoundCloudToken())
    }

    @Test
    fun exchange_without_credentials_throws_before_any_request() = runTest {
        val r = rig { ok("A1") }
        r.secure.remove("soundcloud_client_secret")
        assertFailsWith<IllegalStateException> { r.auth.exchangeCode("CODE", "V") }
        assertTrue(r.bodies.isEmpty())
    }

    @Test
    fun valid_token_is_returned_without_a_request() = runTest {
        val r = rig { ok("NEW") }
        r.signedIn()
        assertEquals("A1", r.auth.accessToken())
        assertTrue(r.bodies.isEmpty())
    }

    @Test
    fun expired_token_refreshes_and_keeps_the_rotated_refresh_token() = runTest {
        val r = rig { ok("A2", "R2") }
        r.signedIn(expiresAt = now - 1)
        assertEquals("A2", r.auth.accessToken())
        assertEquals("R2", r.store.getSoundCloudRefreshToken())
        assertTrue("refresh_token=R1" in r.bodies.single())
    }

    @Test
    fun refresh_without_a_new_refresh_token_keeps_the_old_one() = runTest {
        val r = rig { ok("A2", refresh = null) }
        r.signedIn(expiresAt = now - 1)
        assertEquals("A2", r.auth.accessToken())
        assertEquals("R1", r.store.getSoundCloudRefreshToken())
    }

    @Test
    fun token_with_no_recorded_expiry_counts_as_expired() = runTest {
        val r = rig { ok("A2") }
        r.signedIn()
        r.secure.remove("soundcloud_token_expiry")
        assertEquals("A2", r.auth.accessToken())
    }

    @Test
    fun concurrent_callers_share_one_refresh() = runTest {
        val r = rig { ok("A2", "R2") }
        r.signedIn(expiresAt = now - 1)
        val tokens = (1..8).map { async { r.auth.accessToken() } }.awaitAll()
        assertEquals(List(8) { "A2" }, tokens)
        assertEquals(1, r.bodies.size)
    }

    @Test
    fun dead_refresh_token_signs_out() = runTest {
        val r = rig { HttpStatusCode.BadRequest to """{"error":"invalid_grant"}""" }
        r.signedIn(expiresAt = now - 1)
        assertNull(r.auth.accessToken())
        assertNull(r.store.getSoundCloudToken())
        assertNull(r.store.getSoundCloudRefreshToken())
    }

    @Test
    fun dead_answer_for_an_already_rotated_refresh_token_keeps_the_stored_tokens() = runTest {
        lateinit var rr: Rig
        val r = rig {
            // Another client rotated the tokens while this request was out.
            rr.signedIn(access = "A3", refresh = "R3", expiresAt = now + 60_000)
            HttpStatusCode.BadRequest to """{"error":"invalid_grant"}"""
        }
        rr = r
        r.signedIn(expiresAt = now - 1)
        assertEquals("A3", r.auth.accessToken())
        assertEquals("R3", r.store.getSoundCloudRefreshToken())
    }

    @Test
    fun network_failure_on_a_proactive_refresh_returns_the_valid_token() = runTest {
        val r = rig { HttpStatusCode.ServiceUnavailable to "{}" }
        r.signedIn(expiresAt = now + 60_000)
        r.secure.set("soundcloud_last_refresh", (now - SoundCloudAuth.PROACTIVE_REFRESH_MS - 1).toString())
        assertEquals("A1", r.auth.accessToken())
        assertEquals(1, r.bodies.size)
        assertEquals("A1", r.store.getSoundCloudToken())
    }

    @Test
    fun weekly_refresh_runs_while_the_token_is_still_valid() = runTest {
        val r = rig { ok("A2", "R2") }
        r.signedIn(expiresAt = now + 60_000)
        r.secure.set("soundcloud_last_refresh", (now - SoundCloudAuth.PROACTIVE_REFRESH_MS - 1).toString())
        assertEquals("A2", r.auth.accessToken())
    }

    @Test
    fun rejection_refresh_reuses_a_token_someone_else_already_replaced() = runTest {
        val r = rig { ok("A9") }
        r.signedIn(access = "A2")
        assertTrue(r.auth.refreshAfterRejection("A1"))
        assertTrue(r.bodies.isEmpty())
        assertTrue(r.auth.refreshAfterRejection("A2"))
        assertEquals("A9", r.store.getSoundCloudToken())
    }

    @Test
    fun signed_out_has_no_token_and_an_empty_plugin_config() = runTest {
        val r = rig { ok("A1") }
        assertNull(r.auth.accessToken())
        assertEquals("{}", r.auth.pluginConfigJson())
        assertFalse(r.auth.refreshAfterRejection(null))
        assertTrue(r.bodies.isEmpty())
    }

    @Test
    fun plugin_config_carries_the_token() = runTest {
        val r = rig { ok("A1") }
        r.signedIn()
        assertEquals("""{"token":"A1"}""", r.auth.pluginConfigJson())
    }

    @Test
    fun disconnect_clears_tokens_but_keeps_credentials() = runTest {
        val r = rig { ok("A1") }
        r.signedIn()
        r.auth.disconnect()
        assertNull(r.store.getSoundCloudToken())
        assertNull(r.store.getSoundCloudTokenExpiresAt())
        assertEquals("CID", r.store.getSoundCloudClientId())
    }
}
