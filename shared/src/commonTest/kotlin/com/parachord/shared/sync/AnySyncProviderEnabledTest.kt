package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate for "Sync now", the in-app timer, and the background worker (#377).
 *
 * All three used to read `SyncSettings.enabled` directly. That flag is written
 * only on the Spotify path by design, so an Apple-Music-only setup was fully
 * configured with `enabled = false`: the button was tappable and silently did
 * nothing, and background sync never ran.
 *
 * The opposite mistake is just as easy and worse — `enabledProviders` DEFAULTS
 * to `setOf("spotify")`, so a naive `isNotEmpty()` would report every fresh,
 * never-configured install as sync-enabled. Both directions are pinned here.
 */
class AnySyncProviderEnabledTest {

    private val spotify = SpotifySyncProvider.PROVIDER_ID

    // ── the bug ──────────────────────────────────────────────────────

    @Test
    fun `apple music only is enabled even though the legacy flag is false`() {
        assertTrue(isAnySyncProviderEnabled(legacyEnabled = false, enabledProviders = setOf("applemusic")))
    }

    @Test
    fun `listenbrainz only is enabled - same shape as apple music`() {
        assertTrue(isAnySyncProviderEnabled(legacyEnabled = false, enabledProviders = setOf("listenbrainz")))
    }

    @Test
    fun `a non-spotify provider alongside spotify counts, legacy flag off`() {
        // Spotify present but its own switch is off; AM still needs to sync.
        assertTrue(isAnySyncProviderEnabled(false, setOf(spotify, "applemusic")))
    }

    // ── the trap in the other direction ──────────────────────────────

    @Test
    fun `a fresh install is NOT enabled - the provider set defaults to spotify`() {
        // `parseEnabledSyncProviders` returns setOf("spotify") when unset, so
        // an isNotEmpty() check here would switch sync on for every new user
        // who never configured anything.
        assertFalse(isAnySyncProviderEnabled(legacyEnabled = false, enabledProviders = setOf(spotify)))
    }

    @Test
    fun `nothing configured at all is not enabled`() {
        assertFalse(isAnySyncProviderEnabled(legacyEnabled = false, enabledProviders = emptySet()))
    }

    // ── spotify's own path is unchanged ──────────────────────────────

    @Test
    fun `the legacy flag alone still enables sync - spotify path untouched`() {
        assertTrue(isAnySyncProviderEnabled(legacyEnabled = true, enabledProviders = setOf(spotify)))
        assertTrue(isAnySyncProviderEnabled(legacyEnabled = true, enabledProviders = emptySet()))
    }

    @Test
    fun `turning spotify off with no other provider disables sync`() {
        // The legacy flag must remain a real off switch, not be overridden by
        // spotify's mere presence in the default provider set.
        assertFalse(isAnySyncProviderEnabled(legacyEnabled = false, enabledProviders = setOf(spotify)))
    }
}
