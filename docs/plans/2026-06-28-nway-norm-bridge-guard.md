# N-way norm-bridge guard — cross-engine co-design proposal

**Date:** 2026-06-28 · **Status:** PROPOSAL (co-design — desktop + mobile must agree before either implements)
**Scope:** the shared `unifyTrackKeys` contract (`NwayKeyUnify.kt` ↔ desktop `playlist-key-unify.js`) + `docs/nway-key-unify-fixtures.json`
**Resolves:** the open question in `2026-06-21-nway-key-consistency-design.md` ("exact norm-bridge rule — needs its own fixtures") and audit findings **D-Nway-vs-Legacy-4 / -5** (`2026-06-28-identity-parity-audit.md`).
**Blocks:** arming N-way real writes. This is **P3** — the last identity precondition; both `isNwayPropagateEnabled` (mobile) and desktop real writes stay OFF until it lands byte-identically on both engines.

## Problem

`unifyTrackKeys` unions any two tracks sharing **any** tier (ISRC > MBID > norm), transitively. The `norm` tier (`<artist>|<title>`, remaster-stripped) is **always** computed, so two **genuinely distinct** recordings that share a normalized `artist|title` but carry **different ISRCs/MBIDs** collapse into one key:

- two "Intro" by The xx — album cut (`GBBKS0700116`) vs single edit (`GBBKS1300999`);
- "Wish You Were Here" vs "Wish You Were Here (2011 Remastered)" (different ISRCs, same norm after the remaster strip).

N-way's merge then believes the playlist holds one track and **false-removes** the other; a legacy client re-adds it → flapping. The current code documents this as a deferred residual (`NwayKeyUnify.kt:81-85`: "Guarding the `norm` bridge on disagreeing ISRCs is a tracked refinement").

The fix must **not** break the two legitimate cross-service bridges the norm tier exists for (`2026-06-21` Option A): an un-enriched **norm-only** track must still unify with its **ISRC/MBID twin** on another service, or the playlist duplicates instead.

## The guard rule

Split unification into two phases (the strong unions stay unconditional; only the norm tier is guarded):

1. **Strong phase (unconditional).** Union by equal ISRC, then by equal MBID. These are confident identity matches — never blocked.
2. **Norm phase (guarded), per norm group** (all tracks sharing the normalized `artist|title`), evaluated on the components that exist *after* the strong phase:
   - `cIsrc` = number of distinct components in the group that carry an ISRC.
   - **If `cIsrc ≤ 1`** → at most one confident ISRC identity is present (absent or agree) → **union the whole group** (norm-only + mbid-only + the one ISRC identity collapse together).
   - **Else (`cIsrc ≥ 2`)** → the group holds **≥2 disagreeing ISRC identities** → **do not bridge across them**. Union only the ISRC-free (mbid-only + pure-norm) nodes among themselves; leave each ISRC component intact.

> **CORRECTION (asymmetric, not symmetric — caught implementing against the existing incident fixtures).** An earlier draft of this rule also blocked on `cMbid ≥ 2`. That is **wrong**: it reverses the **MBID-variance bridge** (`norm_bridges_recording_mbid_variance` / `unify_bridgesRemasterDriftAcrossServices_viaNorm`) — two *different recording-MBIDs of the same song* with no ISRC MUST still unify, or the merge false-**removes** the baseline track (the exact 2026-06-22 data-loss class). **MBID disagreement is therefore NOT a conflict; `cMbid` is not counted.** Only ISRC is a hard discriminator. (Recording-MBID legitimately varies for the same song across services/enrichment; ISRC does not.)

Because two *distinct* components inside one norm group can never share a strong value (the strong phase already merged equal ISRCs/MBIDs), "two components both carry an ISRC" ⟹ "different ISRCs" ⟹ a confident disagreement. So `cIsrc ≥ 2` **is** the "confident id-disagreement" the `2026-06-21` doc says must block the norm bridge — while a shared MBID may legitimately span two ISRCs into **one** component (V10), so the count is over **components**, never distinct ISRC values.

### Why complementary tiers still bridge
A component with only an ISRC and a component with only an MBID (`cIsrc = 1`) do **not** disagree on ISRC — they union (repr = the stronger tier, ISRC). This preserves the norm↔strong cross-service dedup. The residual: two genuinely-different same-titled *ISRC-less* recordings still unify via norm — rare, low-harm (a duplicate, never a false-remove), and the same residual the norm tier already documents.

### Determinism & transitivity-safety
The decision (`union-all` vs `weak-only`) depends only on the **set** of components in the norm group and which tiers they carry — not on iteration order. It resolves the transitivity trap (a norm-only node `C` sitting between two conflicting strong components `A`,`B`): under `cIsrc ≥ 2`, `C` is **not** attached to either `A` or `B` (it stays in the norm-only union), so `A` and `B` never merge through `C`. Representative selection is unchanged (strongest tier present, lexicographically-min value).

## Fixture vectors (the cross-engine contract)

Add to `docs/nway-key-unify-fixtures.json`; both Kotlin and desktop JS must produce identical output. (`norm` shown pre-built; ISRC repr uppercased per `validateIsrc`.)

| # | Input tracks (one or two copies) | Expected reprs | Rule branch |
|---|---|---|---|
| V1 | `[{isrc:GBBKS0700116, norm:"the xx\|intro"}, {isrc:GBBKS1300999, norm:"the xx\|intro"}]` | `[isrc-GBBKS0700116, isrc-GBBKS1300999]` | `cIsrc=2` → **separate** (fixes D-Nway-4) |
| **V2** | `[{mbid:aaa…, norm:"x\|y"}, {mbid:bbb…, norm:"x\|y"}]` | both → `mbid-aaa…` | `cIsrc=0` → **UNION** (corrected — MBID disagreement never blocks; this IS the MBID-variance bridge) |
| V3 | `[{isrc:GBN9Y1100123, norm:"pink floyd\|wish you were here"}, {isrc:GBN9Y1100777, norm:"…"}]` | `[isrc-GBN9Y1100123, isrc-GBN9Y1100777]` | `cIsrc=2` → separate (fixes D-Nway-5; titles differ pre-strip) |
| V4 | copyA `[{isrc:USUM71900764, norm:"billie eilish\|bad guy"}]`, copyB `[{norm:"billie eilish\|bad guy"}]` | both → `isrc-USUM71900764` | `cIsrc=1` → **union** (keeps norm↔isrc cross-service dedup) |
| V5 | copyA `[{mbid:1a2b…, norm:"a\|b"}]`, copyB `[{norm:"a\|b"}]` | both → `mbid-1a2b…` | `cIsrc=0` → union (keeps norm↔mbid) |
| V6 | copyA `[{isrc:XXAAA0000001, norm:"a\|b"}]`, copyB `[{mbid:1a2b…, norm:"a\|b"}]` | both → `isrc-XXAAA0000001` | `cIsrc=1` → union (complementary; repr=isrc) |
| V7 | `[{isrc:XXAAA0000001, norm:"a\|b"}, {isrc:XXBBB0000002, norm:"a\|b"}, {norm:"a\|b"}]` | `[isrc-XXAAA0000001, isrc-XXBBB0000002, norm-a\|b]` | `cIsrc=2` → A,B separate; weak C stays its own norm component (transitivity) |
| V8 | `[{norm:"a\|b"}, {norm:"a\|b"}]` | both → `norm-a\|b` | `cIsrc=0` → union (pure-weak dedup) |
| V9 | `[{isrc:XXAAA0000001, norm:"a\|b"}, {isrc:XXBBB0000002, norm:"a\|b"}, {norm:"a\|b"}, {norm:"a\|b"}]` | `[isrc-XXAAA0000001, isrc-XXBBB0000002, norm-a\|b, norm-a\|b]` | `cIsrc=2` → A,B separate; the two weak nodes union together |
| **V10** | `[{isrc:XXAAA0000001,mbid:M, norm:"a\|b"}, {isrc:XXBBB0000002,mbid:M, norm:"a\|b"}, {norm:"a\|b"}]` | all three → `isrc-XXAAA0000001` | shared `mbid:M` → **one** component carrying {AAA,BBB} → `cIsrc=1` → union (count COMPONENTS, not values) |
| V11 | `[{isrc:XXAAA0000001,mbid:M, norm:"a\|b"}, {isrc:XXBBB0000002,mbid:M, norm:"a\|b"}, {isrc:XXCCC0000003, norm:"a\|b"}]` | `[isrc-XXAAA0000001, isrc-XXAAA0000001, isrc-XXCCC0000003]` | MBID-merged pair = one comp; unrelated CCC = second → `cIsrc=2` → separate |
| V12 | copyA `[{mbid:aaa…, norm:"the cranberries\|zombie"}]`, copyB `[{mbid:bbb…, norm:"…"}]` | both → `mbid-aaa…` | `cIsrc=0` → union — **pins the MBID-variance bridge** (no false-remove) |

V1/V3/V7/V9/V11 are the regression-defining (disagreeing-ISRC) vectors — today's un-guarded engine collapses them. V2/V4–V6/V8/V12 are the *must-not-break* cross-service/variance bridges. V10 is the components-not-values discriminator. (`M` = any valid recording MBID.)

## Sub-questions — RESOLVED (maintainer-agreed)

1. **Complementary-tier bridge (V6):** `isrc-only ⊕ mbid-only ⇒ union`. Agreed (matches "absent or agree"; avoids re-opening the norm↔strong half-enriched duplicate).
2. **Weak-union under conflict (V9):** norm-only nodes union with each other. Agreed (the existing no-ISRC dedup, scoped away from the conflicting ISRCs).
3. **MBID symmetry (added during implementation):** MBID disagreement does **not** block — only ISRC. Corrected from the original symmetric draft after the existing MBID-variance fixtures showed the symmetric rule re-introduces the data-loss class. See the CORRECTION callout above + V2/V12.

## Rollout (both engines, byte-identical — mirrors the merge-fixture contract)

1. Agree on the rule + V1–V12 here (this doc). ✅ rule + fixtures landed in `docs/nway-key-unify-fixtures.json` + Kotlin `NwayKeyUnify.unifyTrackKeys` (all pass, existing incident fixtures included).
2. Author the vectors into `docs/nway-key-unify-fixtures.json` **once**; both engines run them. ✅ (this commit)
3. Implement the guarded norm phase in `NwayKeyUnify.unifyTrackKeys` (Kotlin) and `playlist-key-unify.js` (desktop) against the shared fixtures — neither side merges until both pass identically.
4. Re-validate in shadow on a real library (the Guitarmageddon-class false-removes should not regress).
5. Real writes stay gated OFF until this + the no-false-drop harness land. P3 does not by itself arm anything.

I (mobile) am ready to implement the Kotlin side the moment the rule + fixtures are agreed; happy to author the shared JSON first so desktop codes against the same vectors.
