# iOS volume normalizer (desktop parity)

**Status:** designed 2026-09-20. Ports desktop's per-resolver volume offsets to iOS.

## The problem, restated

Reported as "iOS streams are much louder than Spotify." The reframing that
matters: **Spotify is the quiet one**, not Apple Music the loud one.

iOS has no in-app volume control at all — `RootView.swift` treats volume as
system-level and ignores it. So:

- **Apple Music** plays through `ApplicationMusicPlayer` at system volume.
- **Spotify** plays on a Spotify **Connect device**, whose own volume is a
  separate gain multiplying with system volume. Parachord has never set it, so
  it sits wherever the Spotify app last left it — quiet, and unreachable by
  turning up the phone.

## What desktop does

`getEffectiveVolume(baseVolume, resolverId, trackId)`:

```
resolverOffset + trackOffset  ->  dB
multiplier   = 10^(dB/20)
effective    = clamp(baseVolume * multiplier, 0, 100)
```

Applied to the Spotify Web API volume endpoint and to HTML5 `audio.volume` for
local files and SoundCloud. Per-resolver sliders, −12…+6 dB, step 1.

**Desktop deliberately excludes Apple Music** — `isVolumeDisabled = !stream ||
id === 'applemusic'`, labelled "System vol". That exclusion binds harder on
iOS, not less: there is no per-app volume on iOS and `ApplicationMusicPlayer`
has no volume API.

## Decisions

1. **Base volume is 100.** Desktop multiplies its app volume slider; iOS has
   none, so the base is fixed at 100 and the dB offset trims downward. With the
   default 0 dB Spotify lands at 100% and finally matches Apple Music at the
   same system volume. Positive offsets clamp at 100 and are therefore inert —
   accepted, since the offsets exist to attenuate loud sources.
2. **Set it on every Spotify play**, not once per session. One PUT per track
   start is the same order as `startPlayback` itself, not a burst, and
   CLAUDE.md classes volume PUTs as interactive and ungated. Skipped while a
   Spotify cooldown is active (advisory, per the same rule).
3. **Ship the sliders.** Without UI the offsets are unreachable and the feature
   collapses to "pin Spotify at 100%". Apple Music is shown disabled with
   "System vol", mirroring desktop rather than implying a control that cannot
   work.

## Shape

- `shared/commonMain/.../playback/ResolverVolume.kt` — pure
  `effectiveVolumePercent(baseVolume, offsetDb)`, unit-tested, byte-parity with
  desktop. Android stores the offsets today but applies them nowhere; it can
  adopt this helper later.
- **Spotify** — after a successful `IosSpotifyConnect.startPlayback`, set the
  Connect device volume. Fire-and-forget: a volume failure must never fail
  playback (Spotify Free devices are `restricted` and 403 here).
- **AVPlayer** (SoundCloud / local / direct) — `player.volume = effective/100`.
  This is the half of desktop's normalizer that ports directly.
- **Settings** — per-resolver sliders on the Plug-ins tab, −12…+6 dB.

## Explicitly not solved

**Apple Music cannot be attenuated.** No per-app volume exists on iOS and
`ApplicationMusicPlayer` follows the system. Raising Spotify to meet it is the
only available lever. A slider that appeared to lower Apple Music would be a
placebo, so it is shown disabled instead — the same call desktop made.
