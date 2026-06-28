package com.parachord.shared.settings

import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Brick #2 of parachord-mobile#289: the consolidation of the two legacy N-way
 * booleans onto the single `sync_engine_mode` source of truth must be
 * behavior-neutral. Verified end-to-end against an in-memory [MapSettings].
 */
@OptIn(ExperimentalSettingsApi::class, ExperimentalCoroutinesApi::class)
class SettingsStoreSyncEngineModeTest {

    private class FakeSecureStore : SecureTokenStore {
        private val m = mutableMapOf<String, String>()
        override fun get(key: String): String? = m[key]
        override fun set(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun contains(key: String): Boolean = m.containsKey(key)
        override fun observe(key: String): Flow<String?> = flowOf(m[key])
    }

    private fun store(seed: MapSettings.() -> Unit = {}): Pair<SettingsStore, MapSettings> {
        val backing = MapSettings().apply(seed)
        return SettingsStore(FakeSecureStore(), KvStore(backing)) to backing
    }

    @Test
    fun fresh_install_consolidates_to_legacy() = runTest {
        val (s, backing) = store()
        assertEquals("legacy", s.getSyncEngineMode())
        assertFalse(s.isNwayEnabled())
        assertFalse(s.isNwayPropagateEnabled())
        // the consolidation wrote the single-source-of-truth key
        assertEquals("legacy", backing.getStringOrNull(SettingsStore.SYNC_ENGINE_MODE))
    }

    @Test
    fun legacy_enabled_boolean_consolidates_to_shadow() = runTest {
        val (s, _) = store { putBoolean(SettingsStore.NWAY_ENABLED, true) }
        assertEquals("shadow", s.getSyncEngineMode())
        assertTrue(s.isNwayEnabled())
        assertFalse(s.isNwayPropagateEnabled())
    }

    @Test
    fun legacy_both_booleans_consolidate_to_new() = runTest {
        val (s, _) = store {
            putBoolean(SettingsStore.NWAY_ENABLED, true)
            putBoolean(SettingsStore.NWAY_PROPAGATE, true)
        }
        assertEquals("new", s.getSyncEngineMode())
        assertTrue(s.isNwayEnabled())
        assertTrue(s.isNwayPropagateEnabled())
    }

    @Test
    fun set_mode_new_writes_through_to_legacy_booleans() = runTest {
        val (s, backing) = store()
        s.setSyncEngineMode("new")
        assertTrue(s.isNwayEnabled())
        assertTrue(s.isNwayPropagateEnabled())
        // downgrade safety: legacy booleans kept coherent with the mode
        assertTrue(backing.getBoolean(SettingsStore.NWAY_ENABLED, false))
        assertTrue(backing.getBoolean(SettingsStore.NWAY_PROPAGATE, false))
    }

    @Test
    fun garbage_persisted_mode_normalizes_to_legacy() = runTest {
        val (s, _) = store { putString(SettingsStore.SYNC_ENGINE_MODE, "nonsense") }
        assertEquals("legacy", s.getSyncEngineMode())
    }

    @Test
    fun legacy_two_toggle_setters_map_onto_the_mode() = runTest {
        val (s, _) = store()
        s.setNwayEnabled(true)
        assertEquals("shadow", s.getSyncEngineMode())
        s.setNwayPropagateEnabled(true)
        assertEquals("new", s.getSyncEngineMode())
        s.setNwayPropagateEnabled(false)
        assertEquals("shadow", s.getSyncEngineMode())
        s.setNwayEnabled(false)
        assertEquals("legacy", s.getSyncEngineMode())
    }
}
