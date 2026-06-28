package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Byte-parity guard against desktop `tests/sync/sync-engine-mode.test.js`
 * (parachord#911 / parachord-mobile#289). The two engines MUST agree on what
 * each mode gates, or a mixed legacy↔new fleet double-writes or strands writes.
 */
class SyncEngineModeTest {

    @Test
    fun modes_are_the_canonical_three_in_order() {
        assertEquals(listOf("legacy", "shadow", "new"), SyncEngineMode.ALL)
    }

    @Test
    fun normalize_coerces_unknown_and_null_to_legacy() {
        assertEquals("legacy", SyncEngineMode.normalize(null))
        assertEquals("legacy", SyncEngineMode.normalize(""))
        assertEquals("legacy", SyncEngineMode.normalize("nonsense"))
        assertEquals("legacy", SyncEngineMode.normalize("NEW")) // case-sensitive, like desktop
        assertEquals("legacy", SyncEngineMode.normalize("legacy"))
        assertEquals("shadow", SyncEngineMode.normalize("shadow"))
        assertEquals("new", SyncEngineMode.normalize("new"))
    }

    @Test
    fun legacy_playlist_sync_runs_in_every_mode_except_new() {
        assertTrue(SyncEngineMode.legacyPlaylistSyncEnabled("legacy"))
        assertTrue(SyncEngineMode.legacyPlaylistSyncEnabled("shadow"))
        assertFalse(SyncEngineMode.legacyPlaylistSyncEnabled("new"))
        // unknown coerces to legacy → legacy sync stays ON (fail-safe: never stand down on garbage)
        assertTrue(SyncEngineMode.legacyPlaylistSyncEnabled("garbage"))
        assertTrue(SyncEngineMode.legacyPlaylistSyncEnabled(null))
    }

    @Test
    fun nway_writes_only_in_new() {
        assertFalse(SyncEngineMode.nwayWritesEnabled("legacy"))
        assertFalse(SyncEngineMode.nwayWritesEnabled("shadow"))
        assertTrue(SyncEngineMode.nwayWritesEnabled("new"))
        assertFalse(SyncEngineMode.nwayWritesEnabled(null))
    }

    @Test
    fun nway_active_in_shadow_and_new() {
        assertFalse(SyncEngineMode.nwayActive("legacy"))
        assertTrue(SyncEngineMode.nwayActive("shadow"))
        assertTrue(SyncEngineMode.nwayActive("new"))
        assertFalse(SyncEngineMode.nwayActive(null))
    }

    @Test
    fun default_mode_is_legacy_for_existing_installs() {
        assertEquals("legacy", SyncEngineMode.DEFAULT_MODE)
    }

    @Test
    fun legacy_boolean_migration_maps_to_three_states() {
        assertEquals("legacy", SyncEngineMode.fromLegacyBooleans(nwayEnabled = false, nwayPropagate = false))
        assertEquals("shadow", SyncEngineMode.fromLegacyBooleans(nwayEnabled = true, nwayPropagate = false))
        assertEquals("new", SyncEngineMode.fromLegacyBooleans(nwayEnabled = true, nwayPropagate = true))
        // propagate-without-enabled is incoherent; treat as legacy (never silently write)
        assertEquals("legacy", SyncEngineMode.fromLegacyBooleans(nwayEnabled = false, nwayPropagate = true))
    }
}
