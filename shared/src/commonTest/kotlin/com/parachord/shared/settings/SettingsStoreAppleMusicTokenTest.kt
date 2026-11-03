package com.parachord.shared.settings

import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #498 (desktop parachord#1216 / #1218): an Apple Music developer token saved
 * in Settings wins while it's current, and an expired one falls through to
 * the build's instead of breaking Apple Music.
 */
@OptIn(ExperimentalSettingsApi::class, ExperimentalEncodingApi::class)
class SettingsStoreAppleMusicTokenTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    /** An unsigned JWT whose `exp` is [expMs]; only the claim matters here. */
    private fun jwt(expMs: Long, tag: String): String {
        fun b64(s: String) = Base64.UrlSafe.encode(s.encodeToByteArray()).trimEnd('=')
        return "${b64("""{"alg":"ES256"}""")}.${b64("""{"exp":${expMs / 1000},"t":"$tag"}""")}.sig"
    }

    private val savedCurrent = jwt(now + 30 * day, "saved")
    private val savedExpired = jwt(now - day, "saved")
    private val buildCurrent = jwt(now + 90 * day, "build")
    private val buildExpired = jwt(now - 2 * day, "build")

    @Test
    fun aCurrentSavedTokenStillWins() {
        assertEquals(savedCurrent, SettingsStore.pickAppleMusicDeveloperToken(savedCurrent, buildCurrent, now))
    }

    @Test
    fun anExpiredSavedTokenFallsThroughToTheBuilds() {
        var skipped = 0
        assertEquals(
            buildCurrent,
            SettingsStore.pickAppleMusicDeveloperToken(savedExpired, buildCurrent, now) { skipped++ },
        )
        assertEquals(1, skipped)
    }

    @Test
    fun bothExpired_theBuildsIsReturned_soTheExpiryIsStillReported() {
        assertEquals(buildExpired, SettingsStore.pickAppleMusicDeveloperToken(savedExpired, buildExpired, now))
    }

    @Test
    fun noBuildToken_theExpiredSavedOneIsKept_soTheExpiryIsReported() {
        assertEquals(savedExpired, SettingsStore.pickAppleMusicDeveloperToken(savedExpired, "", now))
    }

    @Test
    fun nothingSaved_theBuildsIsUsed_andNothingAtAllIsNull() {
        assertEquals(buildCurrent, SettingsStore.pickAppleMusicDeveloperToken(null, buildCurrent, now))
        assertEquals(buildCurrent, SettingsStore.pickAppleMusicDeveloperToken(" ", buildCurrent, now))
        assertNull(SettingsStore.pickAppleMusicDeveloperToken(null, "", now))
    }

    @Test
    fun aTokenWithoutAReadableExpiryIsNotJudged() {
        assertEquals("opaque", SettingsStore.pickAppleMusicDeveloperToken("opaque", buildCurrent, now))
    }

    // ── Through SettingsStore (getter and flow), with the real clock ──

    private class FakeSecureStore(private val m: MutableMap<String, String>) : SecureTokenStore {
        override fun get(key: String): String? = m[key]
        override fun set(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun contains(key: String): Boolean = m.containsKey(key)
        override fun observe(key: String): Flow<String?> = flowOf(m[key])
    }

    private fun store(saved: String?, build: String): SettingsStore {
        val secure = mutableMapOf<String, String>()
        if (saved != null) secure["apple_music_developer_token"] = saved
        return SettingsStore(FakeSecureStore(secure), KvStore(MapSettings()), appleMusicDeveloperTokenFallback = build)
    }

    @Test
    fun theGetterAndFlowSkipAnExpiredSavedToken() = runTest {
        val longAgo = jwt(1_000_000_000_000L, "saved") // 2001
        val farOff = jwt(4_000_000_000_000L, "build")  // 2096
        val s = store(longAgo, farOff)
        assertEquals(farOff, s.getAppleMusicDeveloperToken())
        assertEquals(farOff, s.getAppleMusicDeveloperTokenFlow().first())
    }

    @Test
    fun theGetterAndFlowKeepACurrentSavedToken() = runTest {
        val savedFarOff = jwt(4_000_000_000_000L, "saved")
        val s = store(savedFarOff, jwt(4_100_000_000_000L, "build"))
        assertEquals(savedFarOff, s.getAppleMusicDeveloperToken())
        assertEquals(savedFarOff, s.getAppleMusicDeveloperTokenFlow().first())
    }
}
