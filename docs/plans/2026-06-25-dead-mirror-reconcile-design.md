# Dead-Mirror Reconcile — probe-gated removal + detach (not propagation)

**Date:** 2026-06-25 · **Branch:** `nway-phase2-migration`

## Problem

A user manually deleted duplicate playlists on Android (including from the
Spotify/Apple Music/ListenBrainz mirrors). On their iPad the duplicates **still
showed, with the per-service chips**, and weren't removed on the next sync.

Root cause analysis (see the investigation in this session):

1. **Playlist pull is deliberately non-destructive** for *surviving* rows — a
   playlist vanishing from a provider does not, on its own, remove the local
   copy (protects against transient/partial fetches nuking real playlists).
2. **Stale chips come from stale `sync_playlist_link` rows.** A `local-*` /
   hosted-XSPF playlist is *exempt* from the pull removal cleanup (it's
   canonically local), so its links to now-deleted mirrors linger → chips
   linger.
3. **The push loop would *resurrect* the dead mirror.** In the three-layer
   dedup, a stale link (externalId absent from the fetched list) was cleared and
   the loop fell through to **re-create** the playlist on the provider — so the
   mirror (and chip) came back, pointing at a fresh duplicate.
4. A separate, pre-existing latent bug: the **removal cleanups treated any
   successful fetch as authoritative**, so a *partial* Spotify/AM fetch could
   mass-delete real (non-local) playlist rows.

The duplicate *creation* root (syncing a non-owned Spotify playlist created an
owned copy) was already fixed earlier via the `playlists.writable` / #269 work
("followed playlists mirror OUT but never back IN"). This design only addresses
the **cleanup of existing stale mirrors + the removal safety gate**.

## Decision

Full cross-device *whole-playlist deletion propagation* was considered and
**rejected as overkill and risky** (it can lose playlists on a false positive,
and devices only reconcile through providers, never peer-to-peer). Instead:

- **Accept manual per-device deletion** of the playlist *row* (a local-origin
  row is canonically owned by each device).
- **Keep the chips/links honest**: when a mirror is *definitively* gone, detach
  it so the chip disappears and the push loop won't recreate it.
- **Harden the existing removal** so a partial fetch can never mass-delete.

Everything keys off one primitive: a **targeted existence probe**
(`SyncProvider.remotePlaylistExists` → a single `GET`, 404 = gone). Contract
(unchanged): **only a definitive 404 returns `false`**; any 429 / auth /
transport error / rate-limit cooldown returns `true` (conservative — a
wrongly-cleared link recreates a live remote as a duplicate). Spotify lacked an
override (the `SyncProvider` default returns `true`), so a Spotify mirror was
never detected gone — **`SpotifyClient.playlistExists` + the override were
added.** Apple Music and ListenBrainz already had it.

This is **NOT** behind a feature flag — it's a correctness/safety fix, not the
gated N-way real-writes propagation (`isNwayPropagateEnabled`). The previously
scoped `isPlaylistDeletePropagateEnabled` flag is moot under this approach (no
whole-playlist deletion happens).

## Design

Pure decision core: `shared/.../sync/DeadMirrorReconcile.kt` (unit-tested,
mirrors the `PlaylistMerge` / `computeNwayPropagationPlan` pattern):

- `suspectedGone(localExternalIds, bulkRemoteIds)` = set difference (the probe
  candidates).
- `confirmedGone(suspected, probeExists)` = suspects whose probe returned an
  explicit `false`. A missing entry or `true` → still exists. **A partial fetch
  with no confirming probe yields the empty set** — the load-bearing safety.
- `overrideAfterDetach(effectiveChannels, deadProvider)` = `effective -
  deadProvider`, seeded from the playlist's EFFECTIVE channels so every *other*
  live mirror survives (the channel-override-is-an-allowlist rule). May be
  empty — callers write the empty set (a `" "` sentinel that round-trips as
  `emptySet`), never null, or the stale link resurrects the chip.

### Workstream B — probe-gated removal

Both pull removal cleanups (inline Spotify ~`SyncEngine.kt:2331`, helper
`pullPlaylistsForProvider` ~`:2710`) now compute the removable set via
`confirmedGoneMirrors(provider, candidateExternalIds, fullRemoteIds)`:
suspected-against-the-FULL-fetched-list → probe each → only confirmed-404 rows
are removed. A **mass-disappearance floor** (`DEAD_MIRROR_MASS_FLOOR = 30`)
skips the reconcile entirely if an implausible number vanish at once (partial
fetch ⇒ refuse to act, and skip the probe storm). The `local-*` / hosted-XSPF
exemption is unchanged.

### Workstream A — detach, don't resurrect

At the push dedup's stale-link branch (`SyncEngine.kt:2843`), instead of
clearing the link and falling through to re-create, it **probes first**:

- still-exists (partial fetch) → **keep the link, skip** (no clear, no
  re-create);
- confirmed-gone (404) → **detach**: set the channel override to exclude the
  provider (`overrideAfterDetach`, seeded from `getPlaylistMirrors().keys`) +
  drop the link + drop the nway token + skip. The chip disappears on both
  platforms (chips read the override-aware effective channels), the push loop
  won't recreate it, and the user can re-enable the mirror via the Sync menu.

This is the *correct place* — it's exactly where resurrection happened — and it
also fixes the partial-fetch case in the push path.

### Intentional behavior changes

1. The inline Spotify removal now suspects against the **full** `remotePlaylists`
   (not `selectedRemote`), so a *deselected-but-present* playlist is no longer
   auto-removed here (deselection is handled by the picker's keep/remove prompt
   — matches the helper's documented behavior).
2. A confirmed-gone mirror on a push-candidate playlist is now **detached**
   (chip removed; re-enable via the Sync menu), not auto-recreated by the push
   self-heal.

### Known limitation

A `local-*` / hosted-XSPF *row* is never auto-removed cross-device — the
deletion of the playlist itself remains a per-device manual action. Only the
*mirror links/chips* are reconciled.

## Testing

- `DeadMirrorReconcileTest` (commonTest) — the pure decision logic; the key
  property is `confirmedGone` returning empty under a partial fetch.
- `DeadMirrorReconcileIntegrationTest` (app/JDBC harness) — B1: a still-live
  mirror absent from a partial fetch is NOT removed; A1: a confirmed-gone mirror
  is detached (link dropped + override excludes it) while a live LB mirror
  survives; A2: a stale-in-fetch but still-live link is kept, not re-created.
- Coverage gap: the inline Spotify *removal* path can't be driven by a fake (it
  casts to the concrete `SpotifySyncProvider`); it reuses the same
  `confirmedGoneMirrors` gate as the helper (B1) + pure tests, plus on-device
  verification.

## Desktop #911 parity

The same gaps exist on desktop (the code comment "playlist tombstones are out of
scope; matches desktop"). The desktop #911 N-way port should adopt the same
posture: a conservative `remotePlaylistExists`-style probe gating any
mirror-removal/detach, suspected-against-the-full-list, with a mass floor; and
detach-via-channel-override rather than resurrect-on-stale-link. No
whole-playlist deletion propagation, no new gate.

## Key files

- `shared/.../sync/DeadMirrorReconcile.kt` (pure) + `DeadMirrorReconcileTest.kt`
- `shared/.../sync/SyncEngine.kt` — `confirmedGoneMirrors`, the two removal
  gates, the push-dedup detach intercept, `DEAD_MIRROR_MASS_FLOOR`
- `shared/.../api/SpotifyClient.kt` — `playlistExists` probe
- `shared/.../sync/SpotifySyncProvider.kt` — `remotePlaylistExists` override
- `app/.../sync/DeadMirrorReconcileIntegrationTest.kt`
