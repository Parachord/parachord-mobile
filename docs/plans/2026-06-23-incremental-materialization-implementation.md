# Incremental-Diff Materialization — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace N-way playlist sync's destructive replace-all write with a per-provider, non-destructive, capability-driven incremental identity-diff, so playlists converge over cycles as IDs backfill instead of being blocked by a coverage threshold.

**Architecture:** Reconcile (identity isrc/mbid/norm, 3-way merge — unchanged) is decoupled from materialize (new). The materialize layer fetches each provider's remote, diffs canonical-vs-remote by identity, and emits `add`/`remove` ops dispatched on per-provider capability flags. Unresolvable adds are left pending (backfilled over time); existing remote tracks are never collateral. Build the new path alongside the old behind the existing `nway_propagate` flag; flip the swap and delete the old coverage-SKIP / Step-3 machinery only once the new materialization harness is fully green.

**Tech Stack:** Kotlin Multiplatform (`shared/commonMain`), SQLDelight, Koin, Ktor, WorkManager, JUnit4 + in-memory JDBC SQLDelight harness.

**Design doc:** `docs/plans/2026-06-23-incremental-materialization-design.md`. **Grounding map** (exact line anchors used below) was captured 2026-06-23.

**Branch:** `nway-phase2-migration` (no worktree — same working tree; see CLAUDE.md if that ever changes).

**KMP guardrails (commonMain):** no `System.currentTimeMillis()` (use `currentTimeMillis()`), no `java.*`, no `Dispatchers.IO`, `import kotlin.concurrent.Volatile`. Build BOTH platforms each task:
`./gradlew :shared:compileDebugKotlinAndroid :shared:compileKotlinIosSimulatorArm64 :shared:testDebugUnitTest :app:testDebugUnitTest`

**Invariant (never regress):** propagation must never drop a track present in / identity-matching a track in any copy. The new path enforces this structurally (removes come only from a positive identity-diff against canonical; an unresolvable add is a no-op, never a delete).

---

## Task 1: Wire Spotify targeted track removal (`DELETE`)

Spotify is the only provider needing new client code. The `DELETE /v1/playlists/{id}/tracks` (body `{tracks:[{uri}]}`) endpoint mirrors the existing `removeTracks` DELETE-with-body precedent at `SpotifyClient.kt:378`.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/SpotifyClient.kt` (body types near `:546`; method near `:431`)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/api/SpotifyClientPlaylistRemoveTest.kt` (new)

**Step 1 — Write the failing test** (Ktor `MockEngine`; assert URL, method, body):

```kotlin
class SpotifyClientPlaylistRemoveTest {
    @Test fun `removePlaylistTracks DELETEs the tracks-by-uri body`() = runBlocking {
        var capturedMethod: String? = null; var capturedUrl: String? = null; var capturedBody: String? = null
        val engine = MockEngine { req ->
            capturedMethod = req.method.value; capturedUrl = req.url.toString()
            capturedBody = (req.body as TextContent).text
            respond("""{"snapshot_id":"snap1"}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = SpotifyClient(/* httpClient built on `engine`, token provider, no-op gate */)
        val snap = client.removePlaylistTracks("PL1", SpTracksUriRefRequest(listOf(SpUriRef("spotify:track:a"))))
        assertEquals("DELETE", capturedMethod)
        assertTrue(capturedUrl!!.endsWith("/v1/playlists/PL1/tracks"))
        assertTrue(capturedBody!!.contains("spotify:track:a"))
    }
}
```

Mirror the existing SpotifyClient test setup (look for the harness used by other `shared/commonTest` Ktor tests, e.g. `ListenBrainzSubmitListensTest`). If `SpotifyClient`'s ctor isn't MockEngine-friendly, add a test-only secondary ctor or factory taking an `HttpClient` (follow whatever the sibling client tests already do — do not invent a new pattern).

**Step 2 — Run, verify it fails** (`SpTracksUriRefRequest` / `removePlaylistTracks` unresolved):
`./gradlew :shared:testDebugUnitTest --tests "*SpotifyClientPlaylistRemoveTest*"`

**Step 3 — Implement.** Add body types beside `SpIdsRequest` (`:546`):

```kotlin
@Serializable data class SpUriRef(val uri: String)
@Serializable data class SpTracksUriRefRequest(val tracks: List<SpUriRef>)
```

Add the method beside `addPlaylistTracks` (`:431`), routed through `gatedSend` (`:211`) — a write MUST be gated:

```kotlin
suspend fun removePlaylistTracks(playlistId: String, body: SpTracksUriRefRequest): HttpResponse =
    gatedSend {
        httpClient.delete("$BASE/v1/playlists/$playlistId/tracks") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
        }
    }
```

**Step 4 — Run, verify pass.** **Step 5 — Commit:** `feat(spotify): wire targeted DELETE playlist-tracks-by-uri`.

---

## Task 2: Capability flags on `ProviderFeatures`

Add the orthogonal write-capability surface the executor dispatches on, so SyncEngine never branches on `provider.id`.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncProvider.kt` (`:192-212`)
- Modify: `SpotifySyncProvider.kt:47`, `ListenBrainzSyncProvider.kt:40`, `AppleMusicSyncProvider.kt:101`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/sync/ProviderCapabilitiesTest.kt` (new — but it asserts real provider instances, which need clients; instead assert via the harness FakeProviders OR a tiny pure test of the enum defaults). Prefer: assert the three providers' declared `trackRemoveMode` in an `:app` test where they can be constructed, or simply assert defaults on a throwaway `object : SyncProvider`.

**Step 1 — Failing test** (defaults + per-provider declaration, using a minimal stub):

```kotlin
@Test fun `removeMode defaults to ReplaceOnly and providers override correctly`() {
    val stub = object : SyncProvider {
        override val id = "x"; override val displayName = "x"
        override val features = ProviderFeatures(snapshots = SnapshotKind.None)
        override suspend fun fetchPlaylists(onProgress: ((Int,Int)->Unit)?) = emptyList<SyncedPlaylist>()
        override suspend fun fetchPlaylistTracks(e: String) = emptyList<PlaylistTrack>()
        override suspend fun getPlaylistSnapshotId(e: String): String? = null
        override suspend fun createPlaylist(n: String, d: String?) = RemoteCreated("e", null)
        override suspend fun replacePlaylistTracks(e: String, ids: List<String>): String? = null
        override suspend fun updatePlaylistDetails(e: String, n: String?, d: String?) {}
        override suspend fun deletePlaylist(e: String) = DeleteResult.Success
    }
    assertEquals(TrackRemoveMode.ReplaceOnly, stub.features.trackRemoveMode)
}
```

**Step 2 — Verify fails** (`TrackRemoveMode` unresolved).

**Step 3 — Implement.** In `SyncProvider.kt` after `SnapshotKind` (`:212`):

```kotlin
/** How a provider deletes specific tracks from a remote playlist. Drives the
 *  materialize executor's removal dispatch — the executor never branches on id. */
enum class TrackRemoveMode {
    ByNativeId,   // Spotify: DELETE by track URI
    ByPosition,   // ListenBrainz: POST item/delete by index+count
    Unsupported,  // Apple Music: no playlist-track remove endpoint (add-only)
    ReplaceOnly,  // fallback for a future provider with only wholesale replace
}
```

Add to `ProviderFeatures` (`:192-203`), defaulting conservatively (`ReplaceOnly`, no reorder) so a new provider that forgets to declare is never *incrementally* destructive:

```kotlin
val trackRemoveMode: TrackRemoveMode = TrackRemoveMode.ReplaceOnly,
val canReorder: Boolean = false,
```

Declare per provider:
- `SpotifySyncProvider.kt:47` features → add `trackRemoveMode = TrackRemoveMode.ByNativeId, canReorder = true`.
- `ListenBrainzSyncProvider.kt:40` → `trackRemoveMode = TrackRemoveMode.ByPosition`.
- `AppleMusicSyncProvider.kt:101` → `trackRemoveMode = TrackRemoveMode.Unsupported`.

**Step 4 — Pass. Step 5 — Commit:** `feat(sync): declare per-provider track remove capabilities`.

---

## Task 3: Incremental add/remove primitives on `SyncProvider`

Add the primitives the executor calls. **No unsafe default:** add has a safe per-provider impl on all three; the two remove variants default to throwing so a mis-dispatch is loud (the executor only calls the variant matching `trackRemoveMode`).

**Files:** `SyncProvider.kt` (interface), the three providers, `SpotifySyncProvider.kt` etc.
**Test:** exercised end-to-end by the Task 6 harness; add focused provider tests only where a client mock already exists.

**Step 1 — Interface additions** (`SyncProvider.kt`, near `:72`):

```kotlin
/** Append [externalTrackIds] to the remote, non-destructively. Every provider
 *  supports an append; no safe replace-based default exists, so each overrides. */
suspend fun addPlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? =
    throw UnsupportedOperationException("addPlaylistTracks not implemented by $id")

/** Remove specific tracks by their native ID (TrackRemoveMode.ByNativeId only). */
suspend fun removePlaylistTracksByNativeId(externalPlaylistId: String, externalTrackIds: List<String>): String? =
    throw UnsupportedOperationException("removeByNativeId not supported by $id")

/** Remove tracks by 0-based remote position (TrackRemoveMode.ByPosition only). */
suspend fun removePlaylistTracksByPosition(externalPlaylistId: String, positions: List<Int>): String? =
    throw UnsupportedOperationException("removeByPosition not supported by $id")
```

**Step 2 — Spotify** (`SpotifySyncProvider.kt`): `addPlaylistTracks` → chunk + `spotifyClient.addPlaylistTracks` (reuse the chunk loop from `replacePlaylistTracks:406`, but POST every chunk, never PUT). `removePlaylistTracksByNativeId` → `spotifyClient.removePlaylistTracks(extId, SpTracksUriRefRequest(ids.map(::SpUriRef)))`, return the snapshot. Both via `withRetry { }`.

**Step 3 — ListenBrainz** (`ListenBrainzSyncProvider.kt`): `addPlaylistTracks` → `client.addPlaylistItems(extId, recordingMbids = ids, token)` then return `getPlaylistLastModified`. `removePlaylistTracksByPosition` → sort positions **descending** and call `client.deletePlaylistItems(extId, index = pos, count = 1, token)` per position (descending so earlier deletes don't shift later indices). Trip `authFailedForSession` on 401 like `:181`.

**Step 4 — Apple Music** (`AppleMusicSyncProvider.kt`): `addPlaylistTracks` → `api.appendPlaylistTracks(extId, AmTracksRequest(ids.map { AmTrackReference(it, "songs") }))`, honoring `INTER_REQUEST_DELAY_MS`; map 401 → `AppleMusicReauthRequiredException`. Leave both remove methods as the throwing default (mode is `Unsupported`, never dispatched).

**Step 5 — Build both platforms; commit:** `feat(sync): per-provider incremental add/remove primitives`.

---

## Task 4: Pure identity-diff function

The heart, and the most fixture-testable piece: given canonical tracks and the provider's current remote tracks, compute identity-level `adds` / `removes` / whether a reorder is needed — using the existing `unifyTrackKeys` so the same song matches across drifted keys.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/sync/PlaylistMaterializeDiff.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/sync/PlaylistMaterializeDiffTest.kt` (new)

**Step 1 — Failing tests** (drive the API; identity-aware; non-destructive intent):

```kotlin
class PlaylistMaterializeDiffTest {
    private fun t(mbid: String) = PlaylistTrack(playlistId="p", position=0, trackTitle="", trackArtist="", trackRecordingMbid=mbid)

    @Test fun `add-only when canonical has a track remote lacks`() {
        val diff = computeMaterializeDiff(canonical = listOf(t("a"), t("b")), remote = listOf(t("a")))
        assertEquals(listOf("b"), diff.addKeys); assertTrue(diff.removeKeys.isEmpty())
    }
    @Test fun `remove when remote has a track canonical dropped`() {
        val diff = computeMaterializeDiff(canonical = listOf(t("a")), remote = listOf(t("a"), t("b")))
        assertEquals(listOf("b"), diff.removeKeys); assertTrue(diff.addKeys.isEmpty())
    }
    @Test fun `drifted-key same song is neither added nor removed`() {
        // same ISRC, different MBID — unifyTrackKeys must bridge them
        val can = PlaylistTrack(playlistId="p", position=0, trackTitle="Zombie", trackArtist="X", trackRecordingMbid="m1", trackIsrc="USABC1234567")
        val rem = PlaylistTrack(playlistId="p", position=0, trackTitle="Zombie - 2025 Remaster", trackArtist="X", trackRecordingMbid="m2", trackIsrc="USABC1234567")
        val diff = computeMaterializeDiff(canonical = listOf(can), remote = listOf(rem))
        assertTrue(diff.addKeys.isEmpty()); assertTrue(diff.removeKeys.isEmpty())
    }
    @Test fun `idempotent — equal sets produce no ops`() {
        val diff = computeMaterializeDiff(canonical = listOf(t("a"), t("b")), remote = listOf(t("a"), t("b")))
        assertTrue(diff.addKeys.isEmpty() && diff.removeKeys.isEmpty() && !diff.reorderNeeded)
    }
    @Test fun `reorder flagged when membership equal but order differs`() {
        val diff = computeMaterializeDiff(canonical = listOf(t("a"), t("b")), remote = listOf(t("b"), t("a")))
        assertTrue(diff.addKeys.isEmpty() && diff.removeKeys.isEmpty() && diff.reorderNeeded)
    }
}
```

**Step 2 — Verify fails.**

**Step 3 — Implement** (reuse `trackKeysOf` + `unifyTrackKeys` from `NwayKeyUnify.kt`; representative-key set diff; order-aware reorder flag). Return *representative keys* (the caller maps keys→tracks/native-ids):

```kotlin
data class MaterializeDiff(
    val addKeys: List<String>,      // representative keys present in canonical, absent from remote
    val removeKeys: List<String>,   // representative keys present in remote, absent from canonical
    val reorderNeeded: Boolean,     // membership equal but order differs (best-effort)
)

fun computeMaterializeDiff(canonical: List<PlaylistTrack>, remote: List<PlaylistTrack>): MaterializeDiff {
    fun keysOf(ts: List<PlaylistTrack>) = ts.map {
        trackKeysOf(isrc = it.trackIsrc, recordingMbid = it.trackRecordingMbid, artist = it.trackArtist, title = it.trackTitle)
    }
    val unified = unifyTrackKeys(listOf(keysOf(canonical), keysOf(remote)))
    val canKeys = unified[0]; val remKeys = unified[1]
    val remSet = remKeys.toSet(); val canSet = canKeys.toSet()
    val addKeys = canKeys.filter { it !in remSet }
    val removeKeys = remKeys.filter { it !in canSet }
    val reorderNeeded = addKeys.isEmpty() && removeKeys.isEmpty() && canKeys != remKeys
    return MaterializeDiff(addKeys, removeKeys, reorderNeeded)
}
```

(Note: `unifyTrackKeys` returns canonical *representative* keys per list; identical songs across the two lists collapse to the same representative, so the set diff is identity-correct. `canKeys != remKeys` compares order under the unified keyspace.)

**Step 4 — Pass. Step 5 — Commit:** `feat(sync): pure identity-diff for incremental materialization`.

---

## Task 5: The materialize executor (capability dispatch + fallback ladder)

Applies a diff to one provider non-destructively. Pure-ish: takes the provider, the canonical tracks (key→track map), the fetched remote tracks, and a `hydrate(track) -> nativeId?` suspend lambda (so the executor itself is hydration-agnostic and testable). Returns a result for surfacing.

**Files:**
- Create: `shared/.../sync/PlaylistMaterializeExecutor.kt`
- Test: covered by the Task 6 harness (end-to-end with FakeProviders of each `removeMode`); optionally a focused executor test with a fake provider.

**Step 1 — Shape** (drive from the harness, but the contract):

```kotlin
data class MaterializeResult(
    val added: Int, val removed: Int,
    val pendingAdds: Int,       // adds whose native ID didn't resolve this cycle (left for backfill)
    val unsupportedRemoves: Int // removes a provider can't honor (e.g. Apple Music)
)

suspend fun materializeToProvider(
    provider: SyncProvider,
    externalId: String,
    canonical: List<PlaylistTrack>,          // representative-keyed, ordered
    remote: List<PlaylistTrack>,             // provider.fetchPlaylistTracks(externalId)
    resolveNativeId: suspend (PlaylistTrack) -> String?,  // cached-or-hydrate, budget-bounded by caller
): MaterializeResult
```

Algorithm:
1. `diff = computeMaterializeDiff(canonical, remote)`; build key→canonicalTrack and key→remoteTrack maps (reuse the unified keyspace — compute keys the same way).
2. **Removes**, dispatched on `provider.features.trackRemoveMode`:
   - `ByNativeId` → `provider.removePlaylistTracksByNativeId(extId, removeKeys.map { nativeIdOf(remoteTrack) })`.
   - `ByPosition` → map each removeKey to its index in `remote`, sort **descending**, `removePlaylistTracksByPosition`.
   - `Unsupported` → `unsupportedRemoves = removeKeys.size`; skip (log).
   - `ReplaceOnly` → only if add-coverage will be full (all adds resolvable): fall to `replacePlaylistTracks(canonical native ids)`; else skip removes (degrade to add-only) + count unsupported.
3. **Adds:** for each addKey, `resolveNativeId(canonicalTrack)`; resolved → collect; unresolved → `pendingAdds++`. `provider.addPlaylistTracks(extId, resolvedIds)`.
4. **Reorder:** if `diff.reorderNeeded && provider.features.canReorder` and adds/removes fully applied → best-effort reorder (Spotify); else skip. (May be a no-op stub in v1 — keep YAGNI; flag in the result only if you implement it.)
5. Return counts. **Never** call `replacePlaylistTracks` except in the `ReplaceOnly` full-coverage branch.

`nativeIdOf(track, provider)` replaces the per-id logic in `extractExternalTrackIds:2774` — make it provider-owned (a small `SyncProvider.nativeIdOf(track): String?` default that reads the right column, overridden per provider) so the central `when` dies.

**Step 5 — Commit:** `feat(sync): capability-dispatched materialize executor (non-destructive)`.

---

## Task 6: Materialization harness (the real-writes gate)

Copy the `NwayPartialCoverageTest` shape (`:53` FakeProvider, `:116` FakeSyncSettings, `:140` Harness) into a new harness whose FakeProviders implement the Task 3 primitives + declare a `trackRemoveMode`, and whose `hydrateFn` can be flipped per track. Encode every design-doc case. These are RED until Task 7 wiring lands.

**Files:**
- Create: `app/src/test/java/com/parachord/android/sync/NwayMaterializeTest.kt`

**Cases (one `@Test` each):**
1. `non-destructive partial coverage — unresolvable add never drops existing remote` (the core safety; replaces coverage-SKIP).
2. `incremental convergence — unresolvable cycle 1 resolves cycle 2, added without destructive intermediate`.
3. `add-heavy daily churn — 80% replaced flows (adds + removes), converges`.
4. `multi-master — add on copy A + remove on copy B reach every writable copy`.
5. `real removal propagates on ByNativeId / ByPosition; Unsupported skips + surfaces`.
6. `capability dispatch — FakeProviders of ByNativeId|ByPosition|Unsupported|ReplaceOnly each take the right path`.
7. `total-wipe-only — canonical→0 blocked; large non-empty drop allowed`.
8. `idempotency ×2 — no-op cycle emits zero add/remove ops` (assert FakeProvider call-logs empty).
9. `identity-diff — drifted-key same song yields no add+remove (no churn/dupe)`.
10. `negative-cache cooldown — an unresolvable track is not re-searched within the window` (assert hydrate call-count).

FakeProvider must record `addCalls` / `removeByIdCalls` / `removeByPositionCalls` / `replaceCalls` so the tests assert *which* path fired and that no-op cycles fire nothing.

**Commit:** `test(nway): materialization harness — non-destructive incremental diff gate` (RED).

---

## Task 7: Negative-cache table + two-tier hydration + time-bounded breakers

**7a — Negative-cache table.** New SQLDelight table `track_provider_id_cache(identityKey TEXT, providerId TEXT, resolvedId TEXT, lastAttemptAt INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(identityKey, providerId))`, copying `SyncPlaylistNway.sq` shape + a `selectStaleCandidates` query modeled on `Track.sq:76` (`resolvedId IS NULL AND (lastAttemptAt IS NULL OR lastAttemptAt < ?) ORDER BY attempts ASC, lastAttemptAt ASC LIMIT ?`). DAO copies `SyncPlaylistNwayDao.kt`. Bootstrap with `CREATE TABLE IF NOT EXISTS` in `AndroidModule.kt` beside `:317`; Koin `single { TrackProviderIdCacheDao(get()) }` beside `:365`. Cooldown `7d → 30d` by `attempts`.
- *Test:* DAO round-trip + cooldown candidate selection (in-memory JDBC, like existing DAO tests).

**7b — Two-tier hydration.** Extract a `HydrationCoordinator` that the executor's `resolveNativeId` closes over: (1) read the cache / the track's existing native-id column; (2) if absent and within a **per-cycle inline budget** (e.g. `MAX_INLINE_LOOKUPS = 12`) and the provider's `RateLimitGate` isn't cooling down → `provider.searchForTrackId(...)`, write resolved/failed to the cache + (null-only) to the row via `backfillResolverIds` (`PlaylistTrackDao.kt:114`); (3) else return null (pending). Stop the cycle's inline lookups on the first rate-limit signal.
- *Test:* budget cap honored; a 429 stops further inline lookups; failures stamp the cache.

**7c — Background backfill worker.** Copy `CrossResolverEnrichmentWorker/Scheduler/Service` (grounding §4): a `PlaylistHydrationBackfillService.runOnce()` that walks `selectStaleCandidates`, resolves a bounded batch (3s gap), writes cache + rows, stamps `lastAttemptAt`/`attempts` in a `finally`. Same WorkManager constraints (UNMETERED, battery-not-low, KEEP, 24h). Schedule from `ParachordApplication`.

**7d — Time-bounded breaker.** Replace AM's `iTunesSearchRateLimited` session flag (`AppleMusicSyncProvider.kt:143`) with a timestamp-based cooldown (`iTunesRateLimitedUntilMs`, ~5 min) checked in `searchForTrackId:429`. One 429 pauses AM hydration briefly, not until app restart.
- *Test:* `RateLimitGateTest`-style: after a 429 stamp, calls within the window short-circuit; after it, they proceed (inject a `nowMs` clock).

**Commit each sub-task** (`feat(sync): negative-cache provider-id table`, `…two-tier hydration`, `…backfill worker`, `fix(applemusic): time-bound the iTunes rate-limit breaker`).

---

## Task 8: Swap the push path; delete the subsumed machinery

Only once Tasks 4–7 are green and `NwayMaterializeTest` passes.

**Files:** `shared/.../sync/SyncEngine.kt`

**Step 1 — Swap.** In `propagateReconcilePlaylist`, replace the PUSH loop (`:1565-1617`) so each target calls `materializeToProvider(provider, externalId, mergedTracks, provider.fetchPlaylistTracks(externalId), resolveNativeId = hydrationCoordinator::resolve)`. Persist resolved IDs to local rows via null-only `backfillResolverIds` (not the destructive `replaceAll` at `:1621`). Build the per-playlist `NwayPropagationEntry` from the aggregated `MaterializeResult`s.

**Step 2 — Decouple the baseline.** Move the baseline advance + `clearLocallyModified` (`:1630-1632`) **out of** the `allCovered` gate — advance to canonical **every** cycle (materialization completeness is now tracked per-track via the cache/diff, not via the baseline). Delete `allCovered`.

**Step 3 — Delete the subsumed code:**
- The >25% coverage SKIP (`:1573-1583`) and `dropFraction` (`:1571-1572`).
- The Step-3 `NWAY_FILL_PENDING_ACTION` const (`:77`) + all its sites (`:1443`, `:1580`, `:1591`, `:1595`) + the fill-target gather branch (`:1443-1448`). Grep to confirm zero references remain (the generic `pendingAction` reader at `:2567-2570` stays — it's the unrelated remote-deleted skip).
- `extractExternalTrackIds` (`:2774-2782`) → replaced by provider `nativeIdOf` (Task 5).
- The `persist=false` propagation hydration mode is gone (executor handles persistence).

**Step 4 — Re-run the FULL suite.** `NwayMaterializeTest`, `NwayPartialCoverageTest` (update/retire the now-obsolete catalog-gap residual assertions — they tested the deleted machinery), `ListenBrainzPushShortCircuitTest`, `NwayShadowTest`, `NwayKeyUnifyTest`, both platforms.

**Step 5 — Commit:** `feat(nway): swap to incremental materialization; delete coverage-SKIP + fill markers`.

---

## Task 9: Coverage surfacing + on-device validation + arm real-writes

**9a — Surface pending coverage.** Thread `MaterializeResult.pendingAdds` / `unsupportedRemoves` into `NwayPropagationEntry` and the Settings → Developer propagation readout (a quiet "N tracks not yet on {provider}"). (Full per-playlist UI is a follow-up; the dev readout is enough to validate.)

**9b — On-device validation** (debug build; `./gradlew installDebug` then force-stop — see CLAUDE.md worktree/deploy rules; verify the APK mtime is newer than your edits):
1. Small mainstream playlist mirrored to 2–3 providers; enable N-way + real writes.
2. Add a track on one service → appears on all writable copies (N+1), nothing dropped; `adb logcat | grep "N-way"` shows incremental add ops, not replace.
3. Remove a track → removed on Spotify/LB; AM surfaces "1 not removable."
4. Sync ×2 with no change → zero ops (idempotent).
5. A playlist with one genuinely-uncatalogable track → it stays pending on that provider, everything else converges (no SKIP).

**9c — Arm.** Real-writes (`nway_propagate`) stays default-OFF in code; the user flips the toggle to validate. Update the design doc + project memory: gate met = `NwayMaterializeTest` green + on-device pass. Desktop #911 unaffected (identity unchanged).

**Commit:** `feat(nway): surface pending coverage; materialization validated`.

---

## Done when

`NwayMaterializeTest` green (all 10 cases) + the rest of the sync suite green on both platforms; the coverage-SKIP / Step-3 markers / `extractExternalTrackIds when` are deleted; on-device shows incremental add/remove (not replace) with no drops and incremental convergence; design doc + memory updated. Real-writes remains a user toggle (default OFF) — armed on Android's own harness, not desktop parity.
