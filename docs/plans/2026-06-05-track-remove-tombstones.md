# Track-Level Remove Tombstones Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Stop sync from re-importing tracks the user deliberately removed, by persisting durable "user removed this on purpose" tombstones keyed by `(providerId, externalId)`, TTL'd at 365 days with re-arm-on-sync-hit — mirroring desktop PR #865 (parachord#864 / parachord-mobile#172).

**Architecture:** A pure Kotlin module `TrackTombstones` (verbatim port of desktop `sync-engine/tombstones.js`, including its 26-test spec) operating over a `TombstoneStore` interface. Production backing is `KvTombstoneStore` — a single JSON blob in `KvStore` mirroring desktop's electron-store shape. A `Mutex`-guarded suspend facade `TrackTombstoneService` is what integration code calls. Three integration points: write tombstones on remove (`SyncEngine.onTrackRemoved`), filter+re-arm on sync pull (`SyncEngine.applyTrackDiff`), clear on user re-add (`LibraryRepository` add paths). App-start prune in `ParachordApplication`.

**Tech Stack:** Kotlin Multiplatform (`shared/commonMain` + `shared/commonTest`), kotlinx.serialization (JSON blob), kotlinx.coroutines `Mutex`, Koin DI, JUnit (commonTest runs on JVM).

---

## Design Decisions (locked)

| Decision | Value | Source |
|---|---|---|
| Storage | `KvStore` JSON blob (NOT a SQLDelight table) | User decision this session; Room is gone, desktop module is blob-shaped |
| KvStore key | `"removed_track_tombstones"` | mirrors desktop `TOMBSTONE_KEY` |
| Blob shape | `{ providerId: { externalId: { removedAt } } }` | mirrors desktop electron-store shape |
| TTL | 365 days (`365L * 24 * 60 * 60 * 1000`) | desktop `TOMBSTONE_TTL_MS` |
| TTL re-arm | every `filterRemoteByTombstones` hit refreshes `removedAt = now` | desktop, load-bearing — see ticket "Re-arm semantics" |
| Providers tombstoned on remove | every `SyncSource` row's `(providerId, externalId)` for the track | desktop `removeTrackFromCollection` |
| Providers cleared on re-add | `deriveTombstoneEntries(track)` → spotify/applemusic/listenbrainz | desktop `deriveTombstoneEntries` |
| Prune cadence | once per app launch (fire-and-forget) | desktop app-start sweep |

**CRITICAL — clear boundary:** tombstones are cleared ONLY on *user-intent* re-add (`LibraryRepository.addTrack`/`addTracks`/`addToCollection`). They are NOT cleared in `applyTrackDiff`'s sync-add path — sync-add goes through `trackDao.insertAll` directly (never `LibraryRepository.addTrack`), and the diff filter already prevents tombstoned tracks from being sync-added. Clearing on sync-add would defeat the tombstone. Do not add a clear call inside `applyTrackDiff`.

**Local-only store:** tombstones are per-device (KvStore is local, not synced). "Parity" means *semantic* parity (key shape, TTL, re-arm), not a shared persisted blob. The Kotlin JSON representation need not be wire-compatible with desktop's electron-store.

---

## KMP / commonMain rules (apply throughout)

Per CLAUDE.md "KMP / shared/commonMain rules":
- `currentTimeMillis()` from `com.parachord.shared.platform`, NOT `System.currentTimeMillis()`.
- No `Dispatchers.IO` (only `Default`/`Main`).
- `kotlinx.coroutines.sync.Mutex` for thread-safety (NOT `ConcurrentHashMap`).
- Generic `Exception`/`IllegalArgumentException`, not JVM-only exception types.
- `import kotlin.concurrent.Volatile` if `@Volatile` is ever needed (it isn't here).

---

## Reference material (read before starting)

- Desktop pure module (the thing being ported): `/tmp/pd-tomb/tombstones.js` (also at https://github.com/Parachord/parachord/blob/main/sync-engine/tombstones.js)
- Desktop test spec (the 26 cases to mirror): `/tmp/pd-tomb/tombstones.test.js` (also https://github.com/Parachord/parachord/blob/main/tests/sync/track-tombstones.test.js)
- Ticket: `gh issue view 172 --repo Parachord/parachord-mobile` — read the maintainer comment for the locked schema/TTL/re-arm decisions.

---

### Task 1: Pure `TrackTombstones` module + data types + 26-test port

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/sync/TrackTombstones.kt`
- Create (test): `shared/src/commonTest/kotlin/com/parachord/shared/sync/TrackTombstonesTest.kt`

**Context:** This is the pure heart — no I/O, no coroutines, fully synchronous, matching `tombstones.js`. It operates over a `TombstoneStore` whose `read()` returns the mutable nested map and `write(map)` persists it. The 26 desktop tests are ported 1:1 against an in-memory fake store.

**Data types (in `TrackTombstones.kt`):**

```kotlin
package com.parachord.shared.sync

import kotlinx.serialization.Serializable

/**
 * A single tombstone payload. `removedAt` is nullable so a deserialized
 * corrupt entry (`{}`) round-trips to `removedAt = null` and is pruned —
 * mirrors desktop's "removes corrupt entries lacking removedAt" semantics.
 */
@Serializable
data class Tombstone(val removedAt: Long? = null)

/** A (providerId, externalId) pair for batch add/clear. */
data class TombstoneEntry(val providerId: String, val externalId: String)

/** Result of [TrackTombstones.filterRemoteByTombstones]. */
data class TombstoneFilterResult<T>(val filtered: List<T>?, val dropped: Int)

/**
 * Store abstraction over the persisted tombstone blob. `read()` returns a
 * MUTABLE deep-ish copy the caller may mutate before `write()`. Production
 * impl is KvTombstoneStore (JSON in KvStore); tests inject an in-memory fake.
 *
 * Map shape: providerId -> (externalId -> Tombstone).
 */
interface TombstoneStore {
    fun read(): MutableMap<String, MutableMap<String, Tombstone>>
    fun write(data: Map<String, Map<String, Tombstone>>)
}
```

**Pure functions (port of tombstones.js, same names/semantics):**

```kotlin
object TrackTombstones {
    const val TTL_MS: Long = 365L * 24 * 60 * 60 * 1000 // 365 days

    private fun valid(s: String?): Boolean = !s.isNullOrEmpty()

    fun addTombstone(store: TombstoneStore, providerId: String?, externalId: String?, now: Long): Boolean {
        if (!valid(providerId) || !valid(externalId)) return false
        val all = store.read()
        val bucket = all.getOrPut(providerId!!) { mutableMapOf() }
        bucket[externalId!!] = Tombstone(now)
        store.write(all)
        return true
    }

    fun addTombstones(store: TombstoneStore, entries: List<TombstoneEntry>?, now: Long): Int {
        if (entries.isNullOrEmpty()) return 0
        val all = store.read()
        var written = 0
        for (e in entries) {
            if (!valid(e.providerId) || !valid(e.externalId)) continue
            all.getOrPut(e.providerId) { mutableMapOf() }[e.externalId] = Tombstone(now)
            written++
        }
        if (written > 0) store.write(all)
        return written
    }

    fun getTombstone(store: TombstoneStore, providerId: String, externalId: String): Tombstone? =
        store.read()[providerId]?.get(externalId)

    fun clearTombstone(store: TombstoneStore, providerId: String, externalId: String): Boolean {
        val all = store.read()
        val bucket = all[providerId] ?: return false
        if (bucket.remove(externalId) == null) return false
        if (bucket.isEmpty()) all.remove(providerId)
        store.write(all)
        return true
    }

    fun clearTombstones(store: TombstoneStore, entries: List<TombstoneEntry>?): Int {
        if (entries.isNullOrEmpty()) return 0
        val all = store.read()
        var cleared = 0
        for (e in entries) {
            val bucket = all[e.providerId] ?: continue
            if (bucket.remove(e.externalId) != null) {
                cleared++
                if (bucket.isEmpty()) all.remove(e.providerId)
            }
        }
        if (cleared > 0) store.write(all)
        return cleared
    }

    fun pruneExpired(store: TombstoneStore, ttlMs: Long, now: Long): Int {
        val all = store.read()
        var pruned = 0
        val providerIds = all.keys.toList()
        for (providerId in providerIds) {
            val bucket = all[providerId] ?: continue
            val externalIds = bucket.keys.toList()
            for (externalId in externalIds) {
                val removedAt = bucket[externalId]?.removedAt
                if (removedAt == null || (now - removedAt) > ttlMs) {
                    bucket.remove(externalId)
                    pruned++
                }
            }
            if (bucket.isEmpty()) all.remove(providerId)
        }
        if (pruned > 0) store.write(all)
        return pruned
    }

    fun <T> filterRemoteByTombstones(
        store: TombstoneStore,
        items: List<T>?,
        providerId: String?,
        now: Long,
        externalIdOf: (T) -> String?,
    ): TombstoneFilterResult<T> {
        if (items.isNullOrEmpty()) return TombstoneFilterResult(items, 0)
        if (!valid(providerId)) return TombstoneFilterResult(items, 0)
        val all = store.read()
        val providerMap = all[providerId] ?: return TombstoneFilterResult(items, 0)
        val kept = mutableListOf<T>()
        var dropped = 0
        var touched = false
        for (item in items) {
            val ext = externalIdOf(item)
            if (ext != null && providerMap.containsKey(ext)) {
                providerMap[ext] = Tombstone(now) // re-arm TTL
                touched = true
                dropped++
            } else {
                kept.add(item)
            }
        }
        if (touched) store.write(all)
        return TombstoneFilterResult(kept, dropped)
    }
}
```

**Step 1: Write the failing test** — port all 26 desktop cases. Use an in-memory `TombstoneStore`:

```kotlin
package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TrackTombstonesTest {

    /** In-memory fake mirroring desktop's makeStore({}). */
    private class FakeStore : TombstoneStore {
        var data: MutableMap<String, MutableMap<String, Tombstone>> = mutableMapOf()
        override fun read() = data
        override fun write(d: Map<String, Map<String, Tombstone>>) {
            data = d.mapValues { (_, v) -> v.toMutableMap() }.toMutableMap()
        }
    }

    private val TTL = TrackTombstones.TTL_MS

    // ── addTombstone ──
    @Test fun `writes a new entry under provider+external`() {
        val s = FakeStore()
        assertTrue(TrackTombstones.addTombstone(s, "spotify", "abc123", 1000))
        assertEquals(Tombstone(1000), TrackTombstones.getTombstone(s, "spotify", "abc123"))
    }

    @Test fun `refreshes removedAt when called twice for same key`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc123", 1000)
        TrackTombstones.addTombstone(s, "spotify", "abc123", 2000)
        assertEquals(Tombstone(2000), TrackTombstones.getTombstone(s, "spotify", "abc123"))
    }

    @Test fun `keeps providers independent`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        TrackTombstones.addTombstone(s, "applemusic", "abc", 2000)
        assertEquals(Tombstone(1000), TrackTombstones.getTombstone(s, "spotify", "abc"))
        assertEquals(Tombstone(2000), TrackTombstones.getTombstone(s, "applemusic", "abc"))
    }

    @Test fun `rejects empty providerId`() {
        val s = FakeStore()
        assertFalse(TrackTombstones.addTombstone(s, "", "abc", 1000))
        assertTrue(s.data.isEmpty())
    }

    @Test fun `rejects empty externalId`() {
        val s = FakeStore()
        assertFalse(TrackTombstones.addTombstone(s, "spotify", "", 1000))
        assertTrue(s.data.isEmpty())
    }

    @Test fun `rejects null providerId or externalId`() {
        val s = FakeStore()
        assertFalse(TrackTombstones.addTombstone(s, null, "abc", 1000))
        assertFalse(TrackTombstones.addTombstone(s, "spotify", null, 1000))
    }

    // ── getTombstone ──
    @Test fun `returns null for missing keys`() {
        assertNull(TrackTombstones.getTombstone(FakeStore(), "spotify", "never"))
    }

    @Test fun `returns null for missing provider bucket`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        assertNull(TrackTombstones.getTombstone(s, "applemusic", "abc"))
    }

    // ── addTombstones (batch) ──
    @Test fun `batch writes multiple entries`() {
        val s = FakeStore()
        val n = TrackTombstones.addTombstones(s, listOf(
            TombstoneEntry("spotify", "a"),
            TombstoneEntry("spotify", "b"),
            TombstoneEntry("applemusic", "c"),
        ), 1000)
        assertEquals(3, n)
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "a"))
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "b"))
        assertNotNull(TrackTombstones.getTombstone(s, "applemusic", "c"))
    }

    @Test fun `batch skips invalid without rejecting whole batch`() {
        val s = FakeStore()
        val n = TrackTombstones.addTombstones(s, listOf(
            TombstoneEntry("spotify", "a"),
            TombstoneEntry("", "b"),
            TombstoneEntry("spotify", "c"),
        ), 1000)
        assertEquals(2, n)
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "a"))
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "c"))
    }

    @Test fun `batch returns 0 for empty input without touching store`() {
        val s = FakeStore()
        assertEquals(0, TrackTombstones.addTombstones(s, emptyList(), 1000))
        assertEquals(0, TrackTombstones.addTombstones(s, null, 1000))
        assertTrue(s.data.isEmpty())
    }

    // ── clearTombstone ──
    @Test fun `clears a single entry`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        assertTrue(TrackTombstones.clearTombstone(s, "spotify", "abc"))
        assertNull(TrackTombstones.getTombstone(s, "spotify", "abc"))
    }

    @Test fun `clear cleans up empty provider buckets`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "only", 1000)
        TrackTombstones.clearTombstone(s, "spotify", "only")
        assertFalse(s.data.containsKey("spotify"))
    }

    @Test fun `clear returns false when nothing to clear`() {
        assertFalse(TrackTombstones.clearTombstone(FakeStore(), "spotify", "nope"))
    }

    // ── clearTombstones (batch) ──
    @Test fun `batch clears across providers`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "a", 1000)
        TrackTombstones.addTombstone(s, "applemusic", "b", 1000)
        TrackTombstones.addTombstone(s, "spotify", "c", 1000)
        val cleared = TrackTombstones.clearTombstones(s, listOf(
            TombstoneEntry("spotify", "a"),
            TombstoneEntry("applemusic", "b"),
        ))
        assertEquals(2, cleared)
        assertNull(TrackTombstones.getTombstone(s, "spotify", "a"))
        assertNull(TrackTombstones.getTombstone(s, "applemusic", "b"))
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "c"))
    }

    @Test fun `batch clear silently skips missing`() {
        assertEquals(0, TrackTombstones.clearTombstones(FakeStore(), listOf(
            TombstoneEntry("spotify", "never"),
        )))
    }

    // ── pruneExpired ──
    @Test fun `prunes entries older than TTL`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "old", 0)
        TrackTombstones.addTombstone(s, "spotify", "recent", TTL / 2)
        val pruned = TrackTombstones.pruneExpired(s, TTL, TTL + 1)
        assertEquals(1, pruned)
        assertNull(TrackTombstones.getTombstone(s, "spotify", "old"))
        assertNotNull(TrackTombstones.getTombstone(s, "spotify", "recent"))
    }

    @Test fun `prune cleans up empty provider buckets`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "only", 0)
        TrackTombstones.pruneExpired(s, TTL, TTL + 1)
        assertFalse(s.data.containsKey("spotify"))
    }

    @Test fun `prune returns 0 when nothing expired`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "recent", 1000)
        assertEquals(0, TrackTombstones.pruneExpired(s, TTL, 2000))
    }

    @Test fun `prune removes corrupt entries lacking removedAt`() {
        val s = FakeStore()
        s.data = mutableMapOf("spotify" to mutableMapOf("bad" to Tombstone(null)))
        val pruned = TrackTombstones.pruneExpired(s, TTL, 1000)
        assertEquals(1, pruned)
        assertNull(TrackTombstones.getTombstone(s, "spotify", "bad"))
    }

    // ── filterRemoteByTombstones ──
    private data class FakeItem(val externalId: String?, val title: String = "")

    @Test fun `filter drops items tombstoned for same provider`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        val items = listOf(FakeItem("abc", "tombstoned"), FakeItem("def", "kept"))
        val r = TrackTombstones.filterRemoteByTombstones(s, items, "spotify", 9999) { it.externalId }
        assertEquals(listOf("kept"), r.filtered!!.map { it.title })
        assertEquals(1, r.dropped)
    }

    @Test fun `filter re-arms TTL on hit`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        TrackTombstones.filterRemoteByTombstones(s, listOf(FakeItem("abc")), "spotify", 5000) { it.externalId }
        assertEquals(Tombstone(5000), TrackTombstones.getTombstone(s, "spotify", "abc"))
    }

    @Test fun `filter does not touch other providers`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "applemusic", "abc", 1000)
        val r = TrackTombstones.filterRemoteByTombstones(s, listOf(FakeItem("abc")), "spotify", 5000) { it.externalId }
        assertEquals(1, r.filtered!!.size)
        assertEquals(0, r.dropped)
        assertEquals(1000L, TrackTombstones.getTombstone(s, "applemusic", "abc")!!.removedAt)
    }

    @Test fun `filter handles empty or null items`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        val empty = TrackTombstones.filterRemoteByTombstones(s, emptyList<FakeItem>(), "spotify", 1) { it.externalId }
        assertEquals(0, empty.dropped); assertTrue(empty.filtered!!.isEmpty())
        val nul = TrackTombstones.filterRemoteByTombstones<FakeItem>(s, null, "spotify", 1) { it.externalId }
        assertEquals(0, nul.dropped); assertNull(nul.filtered)
    }

    @Test fun `filter passes through items without externalId`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1000)
        val items = listOf(FakeItem(null, "no-ext"), FakeItem(null), FakeItem("abc"))
        val r = TrackTombstones.filterRemoteByTombstones(s, items, "spotify", 9999) { it.externalId }
        assertEquals(2, r.filtered!!.size)
        assertEquals(1, r.dropped)
    }

    // ── integration ──
    @Test fun `end-to-end add filter clear filter`() {
        val s = FakeStore()
        TrackTombstones.addTombstone(s, "spotify", "track1", 1000)
        val sync1 = TrackTombstones.filterRemoteByTombstones(s, listOf(FakeItem("track1", "foo")), "spotify", 2000) { it.externalId }
        assertEquals(1, sync1.dropped)
        assertEquals(2000L, TrackTombstones.getTombstone(s, "spotify", "track1")!!.removedAt)
        TrackTombstones.clearTombstones(s, listOf(TombstoneEntry("spotify", "track1")))
        val sync2 = TrackTombstones.filterRemoteByTombstones(s, listOf(FakeItem("track1", "foo")), "spotify", 3000) { it.externalId }
        assertEquals(0, sync2.dropped)
        assertEquals(1, sync2.filtered!!.size)
    }
}
```

**Step 2: Run to verify it fails** — `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.sync.TrackTombstonesTest"` → Expected: FAIL (unresolved `TrackTombstones`/`TombstoneStore`).

**Step 3: Implement** `TrackTombstones.kt` exactly as specced above.

**Step 4: Run to verify it passes** — same command → Expected: PASS (26 tests).

**Step 5: Commit**
```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/sync/TrackTombstones.kt \
        shared/src/commonTest/kotlin/com/parachord/shared/sync/TrackTombstonesTest.kt
git commit -m "Track tombstones: pure module + 26-test port (#172)"
```

---

### Task 2: `KvTombstoneStore` — JSON-blob-backed `TombstoneStore`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/sync/KvTombstoneStore.kt`
- Create (test): `shared/src/commonTest/kotlin/com/parachord/shared/sync/KvTombstoneStoreTest.kt`

**Context:** Backs `TombstoneStore` with a single `KvStore` string key holding kotlinx JSON of `Map<String, Map<String, Tombstone>>`. `read()` deserializes (returns empty mutable map on missing/corrupt blob — never throws); `write()` serializes. `KvStore.getString`/`setString` exist (see `shared/.../store/KvStore.kt`). NOTE: `KvStore.setString` is `suspend`, but `TombstoneStore.write` is synchronous (the pure module calls it sync). Resolve by using the **synchronous** backing write: `KvTombstoneStore` will hold the `KvStore` and call `getString` (sync) in `read()`; for `write()`, persist synchronously. Check `KvStore` for a non-suspend setter; if only `suspend setString` exists, store via the underlying `ObservableSettings`-backed sync path by making `KvTombstoneStore` take a small `suspend`-free writer lambda from the Koin binding (Android `SharedPreferences.edit().putString().apply()` is synchronous). **Simplest:** give `KvTombstoneStore` two lambdas injected at construction: `loadBlob: () -> String?` and `saveBlob: (String) -> Unit`, both synchronous, wired in the Koin binding to `kvStore.getStringOrNull(KEY)` and a synchronous put. This keeps the pure store sync and avoids leaking `suspend` into the pure module.

```kotlin
package com.parachord.shared.sync

import kotlinx.serialization.json.Json

/**
 * [TombstoneStore] backed by a single JSON blob, mirroring desktop's
 * electron-store key `removed_track_tombstones`. Read/write are synchronous
 * (the pure [TrackTombstones] functions call them inline). Backing I/O is
 * injected as sync lambdas so this stays KMP-pure (no suspend, no platform
 * deps). On a missing or corrupt blob, [read] returns an empty map — never
 * throws — so a malformed persisted value self-heals on next write.
 */
class KvTombstoneStore(
    private val loadBlob: () -> String?,
    private val saveBlob: (String) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TombstoneStore {

    override fun read(): MutableMap<String, MutableMap<String, Tombstone>> {
        val raw = loadBlob() ?: return mutableMapOf()
        if (raw.isBlank()) return mutableMapOf()
        return try {
            json.decodeFromString<Map<String, Map<String, Tombstone>>>(raw)
                .mapValues { (_, v) -> v.toMutableMap() }
                .toMutableMap()
        } catch (e: Exception) {
            mutableMapOf() // corrupt blob → start clean
        }
    }

    override fun write(data: Map<String, Map<String, Tombstone>>) {
        saveBlob(json.encodeToString(data))
    }

    companion object {
        const val KEY = "removed_track_tombstones"
    }
}
```

**Step 1: Failing test:**
```kotlin
package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KvTombstoneStoreTest {
    private class MemBlob {
        var blob: String? = null
        fun store() = KvTombstoneStore({ blob }, { blob = it })
    }

    @Test fun `round-trips through JSON`() {
        val m = MemBlob()
        val s = m.store()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1234)
        // New store instance reading the same blob sees the entry
        val s2 = m.store()
        assertEquals(Tombstone(1234), TrackTombstones.getTombstone(s2, "spotify", "abc"))
    }

    @Test fun `empty store reads as empty map`() {
        assertTrue(MemBlob().store().read().isEmpty())
    }

    @Test fun `corrupt blob reads as empty, does not throw`() {
        val m = MemBlob().apply { blob = "{not valid json" }
        assertTrue(m.store().read().isEmpty())
    }

    @Test fun `persists nested provider buckets`() {
        val m = MemBlob()
        TrackTombstones.addTombstones(m.store(), listOf(
            TombstoneEntry("spotify", "a"),
            TombstoneEntry("applemusic", "b"),
        ), 1000)
        val reread = m.store()
        assertEquals(Tombstone(1000), TrackTombstones.getTombstone(reread, "spotify", "a"))
        assertEquals(Tombstone(1000), TrackTombstones.getTombstone(reread, "applemusic", "b"))
        assertNull(TrackTombstones.getTombstone(reread, "spotify", "missing"))
    }
}
```

**Step 2: Verify fails** — `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.sync.KvTombstoneStoreTest"` → FAIL (unresolved `KvTombstoneStore`).

**Step 3: Implement** `KvTombstoneStore.kt` as above.

**Step 4: Verify passes.**

**Step 5: Commit**
```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/sync/KvTombstoneStore.kt \
        shared/src/commonTest/kotlin/com/parachord/shared/sync/KvTombstoneStoreTest.kt
git commit -m "Track tombstones: KvStore JSON-blob store backing (#172)"
```

---

### Task 3: `TrackTombstoneService` (Mutex-guarded facade) + `deriveTombstoneEntries`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/sync/TrackTombstoneService.kt`
- Create (test): `shared/src/commonTest/kotlin/com/parachord/shared/sync/TrackTombstoneServiceTest.kt`

**Context:** Integration code (SyncEngine, LibraryRepository, app-start) must NOT touch the raw pure functions — concurrent sync + UI add/remove would race the read-modify-write. This facade serializes every operation behind a `Mutex` and exposes the suspend API the rest of the app calls. It also owns `deriveTombstoneEntries(track)` (the provider-ID extraction mirroring desktop).

```kotlin
package com.parachord.shared.sync

import com.parachord.shared.model.Track
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mutex-guarded suspend facade over [TrackTombstones] + [TombstoneStore].
 * All integration points (SyncEngine, LibraryRepository, app-start prune)
 * go through this so concurrent sync / UI mutations can't interleave a
 * read-modify-write on the shared blob.
 */
class TrackTombstoneService(private val store: TombstoneStore) {
    private val mutex = Mutex()

    /** Write a tombstone for every (providerId, externalId) in [entries]. */
    suspend fun addAll(entries: List<TombstoneEntry>): Int = mutex.withLock {
        TrackTombstones.addTombstones(store, entries, currentTimeMillis())
    }

    /** Clear tombstones for every (providerId, externalId) in [entries]. */
    suspend fun clearAll(entries: List<TombstoneEntry>): Int = mutex.withLock {
        TrackTombstones.clearTombstones(store, entries)
    }

    /**
     * Drop tombstoned items from [items] for [providerId], re-arming TTL on
     * every hit. [externalIdOf] extracts the remote ID from each item.
     */
    suspend fun <T> filterRemote(
        items: List<T>,
        providerId: String,
        externalIdOf: (T) -> String?,
    ): TombstoneFilterResult<T> = mutex.withLock {
        TrackTombstones.filterRemoteByTombstones(store, items, providerId, currentTimeMillis(), externalIdOf)
    }

    /** Sweep entries older than TTL. Call once per app launch. */
    suspend fun prune(ttlMs: Long = TrackTombstones.TTL_MS): Int = mutex.withLock {
        TrackTombstones.pruneExpired(store, ttlMs, currentTimeMillis())
    }

    companion object {
        /**
         * Provider tombstone keys derivable from a track's known external IDs.
         * Mirrors desktop `deriveTombstoneEntries` — spotify/applemusic/
         * listenbrainz only (the providers that sync the collection). Dedups
         * by "providerId:externalId".
         */
        fun deriveTombstoneEntries(track: Track): List<TombstoneEntry> {
            val out = mutableListOf<TombstoneEntry>()
            val seen = mutableSetOf<String>()
            fun push(providerId: String, externalId: String?) {
                if (externalId.isNullOrEmpty()) return
                if (seen.add("$providerId:$externalId")) out.add(TombstoneEntry(providerId, externalId))
            }
            push("spotify", track.spotifyId)
            push("applemusic", track.appleMusicId)
            push("listenbrainz", track.recordingMbid)
            return out
        }
    }
}
```

**Step 1: Failing test** (uses an in-memory `TombstoneStore`; `runTest` for suspend):
```kotlin
package com.parachord.shared.sync

import com.parachord.shared.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class TrackTombstoneServiceTest {
    private class FakeStore : TombstoneStore {
        var data: MutableMap<String, MutableMap<String, Tombstone>> = mutableMapOf()
        override fun read() = data
        override fun write(d: Map<String, Map<String, Tombstone>>) {
            data = d.mapValues { (_, v) -> v.toMutableMap() }.toMutableMap()
        }
    }

    @Test fun `deriveTombstoneEntries pulls spotify applemusic listenbrainz`() {
        val t = Track(id = "1", title = "t", artist = "a",
            spotifyId = "sp", appleMusicId = "am", recordingMbid = "mb")
        val e = TrackTombstoneService.deriveTombstoneEntries(t)
        assertEquals(setOf(
            TombstoneEntry("spotify", "sp"),
            TombstoneEntry("applemusic", "am"),
            TombstoneEntry("listenbrainz", "mb"),
        ), e.toSet())
    }

    @Test fun `deriveTombstoneEntries skips null external ids`() {
        val t = Track(id = "1", title = "t", artist = "a", spotifyId = "sp")
        assertEquals(listOf(TombstoneEntry("spotify", "sp")),
            TrackTombstoneService.deriveTombstoneEntries(t))
    }

    @Test fun `addAll then filterRemote drops the item`() = runTest {
        val svc = TrackTombstoneService(FakeStore())
        svc.addAll(listOf(TombstoneEntry("spotify", "x")))
        val r = svc.filterRemote(listOf("x", "y"), "spotify") { it }
        assertEquals(listOf("y"), r.filtered)
        assertEquals(1, r.dropped)
    }

    @Test fun `clearAll lets a previously tombstoned item through`() = runTest {
        val svc = TrackTombstoneService(FakeStore())
        svc.addAll(listOf(TombstoneEntry("spotify", "x")))
        svc.clearAll(listOf(TombstoneEntry("spotify", "x")))
        val r = svc.filterRemote(listOf("x"), "spotify") { it }
        assertEquals(listOf("x"), r.filtered)
        assertEquals(0, r.dropped)
    }

    @Test fun `prune removes expired`() = runTest {
        val store = FakeStore()
        store.data = mutableMapOf("spotify" to mutableMapOf("old" to Tombstone(0)))
        val svc = TrackTombstoneService(store)
        val pruned = svc.prune(ttlMs = 1L) // anything older than 1ms ago
        assertEquals(1, pruned)
        assertNull(TrackTombstones.getTombstone(store, "spotify", "old"))
    }
}
```

**Step 2: Verify fails.** **Step 3: Implement.** **Step 4: Verify passes.**

**Step 5: Commit**
```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/sync/TrackTombstoneService.kt \
        shared/src/commonTest/kotlin/com/parachord/shared/sync/TrackTombstoneServiceTest.kt
git commit -m "Track tombstones: Mutex-guarded service facade + deriveTombstoneEntries (#172)"
```

---

### Task 4: Koin bindings for store + service

**Files:**
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt`

**Context:** Register `TombstoneStore` (as `KvTombstoneStore` wired to `KvStore`) and `TrackTombstoneService` as singletons so later tasks can inject them into `SyncEngine` (via `singleOf(::SyncEngine)`) and `LibraryRepository`. The `KvStore` binding already exists (`single { KvStoreFactory.create(androidContext()) }`). `KvTombstoneStore` needs synchronous load/save lambdas — `KvStore.getStringOrNull` is synchronous; for a synchronous put, use the `KvStore`'s synchronous string read and a synchronous write. **`KvStore.setString` is `suspend`** — so to keep `saveBlob` synchronous, wire it to the underlying Android `SharedPreferences` via `androidContext()`, OR add a synchronous `putStringSync` to `KvStore`. Prefer the latter only if trivial; otherwise the binding can persist using a dedicated `SharedPreferences` instance:

```kotlin
// In AndroidModule, near the KvStore binding (~line 322):
single<com.parachord.shared.sync.TombstoneStore> {
    val prefs = androidContext().getSharedPreferences("parachord_tombstones", android.content.Context.MODE_PRIVATE)
    com.parachord.shared.sync.KvTombstoneStore(
        loadBlob = { prefs.getString(com.parachord.shared.sync.KvTombstoneStore.KEY, null) },
        saveBlob = { value -> prefs.edit().putString(com.parachord.shared.sync.KvTombstoneStore.KEY, value).apply() },
    )
}
single { com.parachord.shared.sync.TrackTombstoneService(get()) }
```

> Rationale for a dedicated `SharedPreferences`: `SharedPreferences.edit().apply()` is synchronous-to-call (async disk flush) and avoids leaking `suspend` into the pure store. The tombstone blob is independent of other prefs, so an isolated file is clean. This stays Android-only (in `:app`); iOS will wire its own `TombstoneStore` when the shell lands (NSUserDefaults-backed lambdas).

**Step 1:** Add both bindings.
**Step 2: Verify compiles** — `./gradlew :app:compileDebugKotlin` → Expected: SUCCESS. (No behavior test here — bindings have no consumers yet; the build is the gate.)
**Step 3: Commit**
```bash
git add app/src/main/java/com/parachord/android/di/AndroidModule.kt
git commit -m "Track tombstones: Koin bindings for store + service (#172)"
```

---

### Task 5: Write tombstones on remove — `SyncEngine.onTrackRemoved`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncEngine.kt` (constructor + `onTrackRemoved` ~L2284)
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` (only if `SyncEngine` is NOT bound via `singleOf` — verify; if `singleOf(::SyncEngine)`, the new constructor param auto-resolves from Task 4's binding and no DI edit is needed)

**Context:** `onTrackRemoved` iterates `syncSourceDao.getByItem(track.id, "track")` and calls `provider.removeTracks` per source. Write a tombstone for EVERY source's `(providerId, externalId)` regardless of whether the remote removal succeeded (desktop writes unconditionally — the whole point is durability when remote-remove fails). Add the tombstone write before `syncSourceDao.deleteAllForItem`.

Add constructor param:
```kotlin
class SyncEngine(
    // ...existing params...
    private val tombstones: TrackTombstoneService,
) {
```

In `onTrackRemoved`:
```kotlin
suspend fun onTrackRemoved(track: Track) {
    val sources = syncSourceDao.getByItem(track.id, "track")
    val providersById = providers.associateBy { it.id }
    // Tombstone EVERY synced provider for this track up front, so a failed or
    // unsupported remote removal can't be undone by the next sync re-import (#172).
    val entries = sources.mapNotNull { s ->
        s.externalId?.let { TombstoneEntry(s.providerId, it) }
    }
    if (entries.isNotEmpty()) tombstones.addAll(entries)
    for (source in sources) {
        val externalId = source.externalId ?: continue
        val provider = providersById[source.providerId] ?: continue
        try {
            provider.removeTracks(listOf(externalId))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove track from ${provider.displayName}", e)
        }
    }
    syncSourceDao.deleteAllForItem(track.id, "track")
}
```

**Step 1: Verify SyncEngine binding.** `grep -n "SyncEngine" app/src/main/java/com/parachord/android/di/AndroidModule.kt` — confirm `singleOf(::SyncEngine)`. If so, no DI edit. If it's an explicit `single { SyncEngine(...) }`, add `tombstones = get()`.

**Step 2: Add the constructor param + the tombstone write.**

**Step 3: Verify compiles** — `./gradlew :shared:compileDebugKotlinAndroid :app:compileDebugKotlin` → SUCCESS.

**Step 4: Test.** If a `SyncEngineTest` harness with mock DAOs exists (`grep -rl "class SyncEngineTest" app shared`), add a test asserting `onTrackRemoved` calls `tombstones.addAll` with the right entries (inject a real `TrackTombstoneService` over a `FakeStore`, mock `syncSourceDao.getByItem` to return two sources, assert both are tombstoned). If NO such harness exists, SKIP a bespoke SyncEngine test (the write logic is a thin 3-line delegation to the already-fully-tested service) and note it for the on-device smoke in Task 8. Do not build a heavy mock harness from scratch just for this.

**Step 5: Commit**
```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncEngine.kt
# + AndroidModule.kt only if edited
git commit -m "Track tombstones: write on remove in SyncEngine.onTrackRemoved (#172)"
```

---

### Task 6: Filter + re-arm on sync pull — `SyncEngine.applyTrackDiff`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncEngine.kt` (`applyTrackDiff` ~L460)

**Context:** `applyTrackDiff(remote: List<SyncedTrack>, localSources, providerId)` computes `toAdd = remote.filter { it.spotifyId !in localByExternalId }`. Mirror desktop: filter `remote` through tombstones BEFORE the diff, using `SyncedTrack.spotifyId` as the external ID (it's the generic external-id field on `SyncedTrack`, named `spotifyId` historically — confirm with the Task-1 Explore notes / the file). The filter both drops tombstoned items from `toAdd` AND re-arms their TTL. Tombstoned items are never local (they were deleted on remove), so dropping them from `remote` does not perturb `toRemove`/the mass-removal safeguard.

Replace the opening of `applyTrackDiff`:
```kotlin
private suspend fun applyTrackDiff(
    remoteIn: List<SyncedTrack>,
    localSources: List<SyncSource>,
    providerId: String,
): TypeSyncResult {
    // Drop tracks the user removed on purpose (re-arming their TTL), so a
    // still-present remote track isn't re-imported against the user's intent (#172).
    val tombResult = tombstones.filterRemote(remoteIn, providerId) { it.spotifyId }
    val remote = tombResult.filtered ?: remoteIn
    if (tombResult.dropped > 0) {
        Log.d(TAG, "applyTrackDiff: tombstones dropped ${tombResult.dropped} remote tracks for $providerId")
    }
    val remoteByExternalId = remote.associateBy { it.spotifyId }
    // ...rest unchanged (localByExternalId, toAdd, toRemove, toUpdate, writes)...
```
(Rename the parameter `remote` → `remoteIn` and introduce the filtered local `remote`, or keep `remote` as the param and assign to a new `val effectiveRemote` used downstream — pick whichever yields the smallest diff. Ensure ALL downstream references use the filtered list.)

**Step 1: Apply the filter** at the top of `applyTrackDiff`.

**Step 2: Verify compiles** — `./gradlew :shared:compileDebugKotlinAndroid` → SUCCESS.

**Step 3: Test.** Same rule as Task 5 — only add a SyncEngine-level test if a mock-DAO harness already exists. The filter semantics are already covered by `TrackTombstoneServiceTest` + the 26 pure tests. Note for on-device smoke.

**Step 4: Commit**
```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncEngine.kt
git commit -m "Track tombstones: filter + re-arm on sync pull in applyTrackDiff (#172)"
```

---

### Task 7: Clear on user re-add — `LibraryRepository` add paths

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/repository/LibraryRepository.kt` (constructor + `addTrack` / `addTracks`)
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` (LibraryRepository binding — add `tombstones = get()`)

**Context:** Clear tombstones when the user re-adds a track, so a deliberate re-add re-enables sync for it. `addToCollection` calls `addTrack`, so clearing in `addTrack` covers the user-love path; also clear in `addTracks` (batch import). Do NOT clear anywhere in the sync-add path (`applyTrackDiff` / `trackDao` direct inserts) — see the CRITICAL boundary note at the top of this plan.

Add constructor param:
```kotlin
class LibraryRepository(
    // ...existing params...
    private val tombstones: TrackTombstoneService,
) {
```

In `addTrack` (after the insert):
```kotlin
suspend fun addTrack(track: Track) {
    val existing = trackDao.getById(track.id)
    trackDao.insert(if (existing != null) track.copy(addedAt = existing.addedAt) else track)
    // Re-adding clears any tombstone so sync may re-import this track again (#172).
    tombstones.clearAll(TrackTombstoneService.deriveTombstoneEntries(track))
    mbidEnrichTrack(track.id, track.artist, track.title)
}
```

In `addTracks` (after `insertAll`):
```kotlin
suspend fun addTracks(tracks: List<Track>) {
    trackDao.insertAll(tracks)
    val entries = tracks.flatMap { TrackTombstoneService.deriveTombstoneEntries(it) }
    if (entries.isNotEmpty()) tombstones.clearAll(entries)
    mbidEnrichBatch(tracks)
}
```

Update the `LibraryRepository` Koin binding (explicit `single { LibraryRepository(...) }` ~L493) to pass `tombstones = get()`.

**Step 1:** Add constructor param + clear calls + DI wiring.
**Step 2: Verify compiles** — `./gradlew :shared:compileDebugKotlinAndroid :app:compileDebugKotlin` → SUCCESS.
**Step 3: Test.** If `LibraryRepositoryTest` with mock DAOs exists, add a test: `addTrack` on a track with `spotifyId="x"` calls `tombstones.clearAll` containing `TombstoneEntry("spotify","x")` (inject real service over FakeStore; pre-seed a tombstone; assert it's gone after `addTrack`). Else skip per the Task-5 rule.
**Step 4: Commit**
```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/repository/LibraryRepository.kt \
        app/src/main/java/com/parachord/android/di/AndroidModule.kt
git commit -m "Track tombstones: clear on user re-add in LibraryRepository (#172)"
```

---

### Task 8: App-start prune + full verification

**Files:**
- Modify: `app/src/main/java/com/parachord/android/app/ParachordApplication.kt`

**Context:** Once per launch, sweep expired tombstones (TTL 365d). Fire-and-forget in `appScope.launch`, try/catch, mirroring the existing `imageEnrichmentService.regenerateAllPlaylistMosaics()` block.

Add inject + call:
```kotlin
private val trackTombstoneService: com.parachord.shared.sync.TrackTombstoneService by inject()

// In onCreate, after crossResolverEnrichmentScheduler.enable():
appScope.launch {
    try {
        val pruned = trackTombstoneService.prune()
        if (pruned > 0) com.parachord.shared.platform.Log.d("ParachordApplication", "Pruned $pruned expired track tombstones")
    } catch (_: Exception) {
        // Background sweep; never fail app startup on a prune error.
    }
}
```

**Step 1:** Add inject + prune call.
**Step 2: Verify compiles** — `./gradlew :app:compileDebugKotlin` → SUCCESS.
**Step 3: Full test suite** — `./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest` → ALL PASS (the new 26 + store + service tests, plus all existing).
**Step 4: Install + on-device smoke** — `./gradlew installDebug`, force-stop (`adb shell am force-stop com.parachord.android.debug`), relaunch. Manual smoke:
  1. With Spotify sync on, remove a synced track from the collection.
  2. Trigger a sync (in-app sync now, or wait for the 15-min timer).
  3. Confirm the track does NOT reappear.
  4. Re-add the same track via UI; sync; confirm it now persists (tombstone cleared).
  Capture logcat for the `applyTrackDiff: tombstones dropped N` line to confirm the filter fired: `adb logcat | grep -i tombstone`.
**Step 5: Commit**
```bash
git add app/src/main/java/com/parachord/android/app/ParachordApplication.kt
git commit -m "Track tombstones: app-start prune + wire-up (#172)"
```

---

### Task 9: CLAUDE.md docs + PR

**Files:**
- Modify: `CLAUDE.md` (add a "Track-Level Remove Tombstones" subsection under the sync area)

**Context:** Document the invariant for future maintainers + iOS parity: storage key/shape, TTL, re-arm semantics, the clear-only-on-user-readd boundary, the three integration points, and that iOS must mirror this (port `TrackTombstones` is already KMP-shared; iOS only needs its own `TombstoneStore` backing + app-start prune call).

**Step 1:** Add the doc subsection (keep it tight, ~25 lines, matching the style of the "Playlist Sync — Duplicate Prevention" section).
**Step 2: Commit**
```bash
git add CLAUDE.md
git commit -m "docs: track remove tombstones invariant + iOS parity note (#172)"
```
**Step 3:** Push branch, open PR against `Parachord/parachord-mobile` (base `main`), reference #172 + desktop parachord#865. Summarize: pure module + 26-test port, KvStore blob storage, three integration points, app-start prune, the clear-only-on-user-readd boundary, and the AM-consumer-token-DELETE caveat (out of scope — flagged in the ticket).

---

## Out of scope (per ticket)

- Album-level / artist-level tombstones (separate tickets; same shape).
- Playlist-level tombstones (desktop `suppressSync`; separate).
- Confirming Apple Music `DELETE /v1/me/library/songs/{id}` actually removes on a consumer token (a testing call-out, not code — but the tombstone makes the answer not matter for durability).
- Wizard "clear remove history" UI.

## Parity checklist (verify against desktop before PR)

- [ ] Key `removed_track_tombstones`, shape `{ providerId: { externalId: { removedAt } } }`
- [ ] TTL 365 days
- [ ] `filterRemoteByTombstones` re-arms `removedAt` on every hit
- [ ] Tombstone written for EVERY synced provider on remove (not just the one that succeeded)
- [ ] Cleared ONLY on user-intent re-add, never on sync-add
- [ ] App-start prune once per launch
- [ ] All 26 pure-module test semantics reproduced
