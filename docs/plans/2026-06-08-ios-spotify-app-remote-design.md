# iOS Spotify on-device playback: `SPTAppRemote` design

**Status:** Design / decision record. **SDK DEFERRED** — see §0.
**Date:** 2026-06-08
**Decision driver:** On-device playback is the **primary** iOS user experience.

---

## 0. Outcome (2026-06-08): on-device works on the Web API — SDK deferred

Before adopting the SDK we hardened the Web API path and tested on-device.
**Result: cold on-device playback now works without the SDK.**

- The original breakage was the **suspension race** (waking Spotify backgrounds
  us → iOS suspends the device poll). A **`UIApplication` background-task
  assertion** around the wake+poll fixed it: the poll now completes, the
  phone's Spotify registers as a Connect device, and the Web API
  `startPlayback?device_id=…` plays the track. Verified on-device: track
  played, and play/pause works.
- We also switched the wake to the **track deep link** (`spotify:track:<id>`)
  so Spotify lands on the right track (no wrong-track auto-resume).
- The "does opening the track URI **autoplay**" micro-experiment was
  **inconclusive** (the state check fired at 2s, before the device registered)
  — but moot, because the Web API `startPlayback` plays it regardless.

**Why the SDK is deferred, not adopted:** per §3 fact #1, the SDK's
`authorizeAndPlayURI` **also foregrounds Spotify** on a cold start, so it would
NOT remove the one remaining bit of jank ("it opened Spotify") — that cold
foreground is unavoidable on iOS either way. The SDK's remaining marginal
value is **push playback state** (scrubber position / auto-advance without
polling) — and the auto-return below. So the SDK becomes a **later polish**
item, not a prerequisite for the primary experience.

**Caveat that sharpens the SDK case — auto-return to Parachord.** iOS does
**not** let an app foreground itself (no API; self-foregrounding is blocked).
So after the Web-API `spotify://` wake, the user is **stranded in Spotify**
until they manually switch back — we cannot bring Parachord forward. The
**only** mechanism that auto-returns is a redirect routed to us, which is
exactly what `authorizeAndPlayURI` produces: open Spotify → play → **redirect
to `parachord://` re-foregrounds Parachord**. (And since that redirect fires
*after* playback starts, the Android mistake-#19 "deprioritized before it
registered" concern doesn't apply.) So the SDK's distinctive win is **not** a
silent cold start (still flashes Spotify) but **returning the user to
Parachord automatically** — the Web-API path cannot do this on iOS. If
"don't strand the user in Spotify on cold start" is a priority, that revives
the SDK case on its own.

The design below is retained as the record for if/when push-state, auto-return,
or a cleaner cold-start justifies revisiting.

---

## 1. Problem

On iOS, Parachord controls Spotify entirely through the **Web API** (Spotify
Connect HTTP), mirroring the Android "April 2026 stance." That works for
*casting to an already-running device* (a Mac, a speaker), but it **cannot
play silently on the iPhone itself**:

- The Web API can only target a device that is *already* a live Connect
  endpoint. The phone's own Spotify usually isn't running.
- To make the phone a target we open `spotify://`, which **foregrounds
  Spotify and backgrounds Parachord**; iOS then suspends us mid-poll, so the
  device often never registers. Symptom (observed on-device): "opened
  Spotify, didn't play; went back, tried again, then it played."
- We worked around this all session (background-task assertion, longer poll,
  device picker). It's now *reliable-ish*, but the first on-device play still
  foregrounds Spotify and is structurally fragile.

Android does NOT have this problem: it has an **invisible** wake (media-button
broadcast to `com.spotify.music` → wakes Spotify's `MediaBrowserService` with
no UI). **iOS has no equivalent.** That asymmetry is the whole issue — the
capability that let Android drop the App Remote SDK is the one iOS lacks.

**If on-device playback is primary, Web-API-only is the wrong foundation for
the core path on iOS.**

## 2. Why the SDK, framed against "match Android"

`SPTAppRemote` is the **iOS-appropriate mechanism to deliver the same
on-device experience Android delivers** via broadcast + Web API. CLAUDE.md's
port table already lists `SPTAppRemote` as the iOS equivalent of Android's App
Remote. This is experience parity, not a UX divergence — exactly the kind of
platform-appropriate `actual` the KMP split anticipates.

It is **additive, not a replacement.** App Remote controls only the *local*
Spotify app, so the Web API stays for:
- **Resolution / search** (`SpotifyClient.searchTrack`, shared, unchanged).
- **Casting to remote devices** (Mac / speaker / TV) via `startPlayback` /
  transfer — the device picker still chooses between "this device" (SDK) and a
  remote (Web API).

## 3. Verified SDK facts (Spotify official iOS SDK, June 2026)

| # | Question | Answer |
|---|---|---|
| 1 | Cold-wake with a token, or must use `authorizeAndPlayURI`? | **Must use `authorizeAndPlayURI(playURI)`** when Spotify isn't running. It opens Spotify and plays the given track URI atomically, returns a token, and establishes the connection. `connect()` is only for subsequent reconnects once a token exists. |
| 2 | Does the user's dashboard need the Bundle ID, or just the Redirect URI? | **Only the Redirect URI** must be whitelisted in the dashboard (BYO users already do this for the Web API). The Bundle ID lives in *our* app's `Info.plist` URL-scheme config, not the user's dashboard. → BYO onboarding is **not materially worse**. |
| 3 | Does playback need to be active to keep the connection alive? | **Yes.** iOS suspends idle background apps; the App Remote link survives only while music is playing. Fine for a music player, but pausing for long may drop the connection (reconnect on next command). |

**Consequence of #1:** the SDK does **not** make the *first* on-device play
invisible — Spotify still comes to the front once. But it makes that play
**reliable** (wake + play your track in one atomic call, no poll race), and
**every subsequent** play/pause/seek is silent IPC control with **push state**
— no re-opening Spotify, no Web API calls.

Sources:
- [spotify/ios-sdk README](https://github.com/spotify/ios-sdk/blob/master/README.md)
- [SPTAppRemote class reference](https://spotify.github.io/ios-sdk/html/Classes/SPTAppRemote.html)
- [ios-sdk auth.md](https://github.com/spotify/ios-sdk/blob/master/docs/auth.md)
- [Getting Started with iOS SDK](https://developer.spotify.com/documentation/ios/quick-start/objective-c/)

## 4. How BYO-single-key changes things (net positive, with nuance)

Each user enters **one** Spotify client ID (BYO, runtime — Android's
`connectSpotify(clientId)`), and that one key is shared across their
desktop + Android + iOS (which is exactly why our 429s hit everywhere at once).

- **Rate-limit relief — the strongest pro.** App Remote play/pause/seek/state
  run over **local IPC, not the Web API**, so they consume **zero** of the
  user's single rate-limited key, and state arrives by push instead of
  `getPlaybackState` polling. Directly relieves the budget we keep tripping,
  and unblocks the play/pause / scrubber / auto-advance features we deferred
  *because* polling was rate-limit-prone.
- **Runtime config.** `SPTConfiguration(clientID:redirectURL:)` must be built
  *after* the user enters their key — not the SDK's default "set it in
  `Info.plist`" pattern. Minor.
- **Onboarding not worse** (fact #2): redirect URI only, already required.

## 5. Proposed architecture (dual-path)

```
                         ┌─ "This device" ──→ SPTAppRemote (local IPC)
 PlaybackRouter.spotify ─┤                     • authorizeAndPlayURI(uri) cold
 (device picker chooses) │                     • connect()+playerAPI warm
                         └─ remote device ──→ Web API startPlayback/transfer
 Resolution / search ─────────────────────────→ Web API (SpotifyClient, shared)
```

- **`IosSpotifyConnect` keeps the Web-API remote path** (today's code) and
  gains an `SPTAppRemote`-backed local path.
- **Device picker** (already built) stays the chooser: `localDeviceId` → SDK
  path; a real remote id → Web API path.
- **Play/pause** routes to whichever path is active: SDK `playerAPI`
  pause/resume for local, Web API for remote.
- **State** for the local path comes from `playerStateDidChange` (push) →
  feeds the coordinator's `spotifyPlaying` / position with **no polling**.
- **Auth:** keep the existing PKCE flow (Web API: resolution + remote). For the
  SDK, request the `app-remote-control` scope so the wake's returned token (or,
  if interchangeable, the PKCE token) authorizes the connection. The
  `authorizeAndPlayURI` redirect is handled in SwiftUI `.onOpenURL` — distinct
  from the `ASWebAuthenticationSession` PKCE callback, so no Android-style
  `RedirectUriReceiverActivity` conflict (that was an Android manifest issue).

## 6. Tradeoffs

**Pros**
- Reliable on-device first play (atomic wake+play) — fixes the core UX.
- **Auto-returns the user to Parachord** after the cold-start foreground (its
  `parachord://` redirect). The Web-API `spotify://` path **cannot** — iOS
  forbids self-foregrounding, so that path strands the user in Spotify.
- Silent on-device control + **push** state after first play.
- Playback control off the Web API → big rate-limit relief on the one key.
- Unblocks scrubber / accurate play-pause / auto-advance without polling.

**Cons / risks**
- **Additive complexity**: a second playback path (SDK local + Web API remote);
  `IosSpotifyConnect` grows a state machine (disconnected / waking /
  connected). Connection lifecycle (drops on pause/suspend → reconnect) is
  the historically finicky part of this SDK.
- **First play still foregrounds Spotify once** (fact #1). Not fully invisible
  — but reliable, then silent.
- **Binary dependency** (`SpotifyiOS.xcframework`) + per-user runtime config.
- **Premium + Spotify installed** required (same as today).
- **Spotify dev-terms drift**: each BYO user runs their own dev app; Spotify
  has tightened SDK access — more per-user surface for "Spotify changed the
  rules."

## 7. Alternatives considered

1. **Web-API-only, default to a live remote.** Keep today's code; when the
   phone isn't a live device, prefer casting to the Mac instead of waking.
   *Rejected as the primary path* — it makes *remote* playback primary, which
   contradicts the decision driver. Still the correct **fallback** when no SDK
   connection / no Premium / Spotify not installed.
2. **Keep the `spotify://` wake hardening** (this session's work). Works but
   structurally fragile and never silent. *Keep as the no-SDK fallback only.*
3. **Do nothing / on-device is best-effort.** Contradicts "on-device is
   primary."

## 8. Open questions to resolve before implementation

- **Token interchangeability:** can the PKCE access token (with
  `app-remote-control` scope) drive `connect()` directly, or must we always go
  through `authorizeAndPlayURI` for the token? Affects whether there are one or
  two token lifecycles.
- **Connection lifecycle policy:** when the link drops (long pause, Spotify
  killed), do we silently reconnect on next command, or surface state? Define
  the state machine.
- **SDK distribution:** SwiftPM vs. manual `xcframework`; license/redistribution
  check for shipping it.
- **Fallback matrix:** no Premium / Spotify not installed / connection refused
  → fall back to the Web API remote path or a clear message.

## 9. Recommendation

**Pursue `SPTAppRemote` for the primary on-device path**, retaining the Web API
for resolution and remote-device casting (the device picker already routes
between them). It directly fixes the primary UX and relieves the single-key
rate limit. Scope it as its own workstream behind the open questions in §8 —
**not** a slip-in — with the no-SDK Web-API path preserved as the fallback.

**Next step if approved:** resolve §8 (esp. token interchangeability), then a
`superpowers:writing-plans` implementation plan (SDK integration → auth wiring
→ dual-path router → push-state → fallback matrix), executed on a worktree
with on-device bake time per CLAUDE.md's playback track record.
