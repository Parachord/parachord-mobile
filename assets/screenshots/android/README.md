# Android screen reference screenshots

Full-page screenshots of the **existing Parachord Android UI**, kept here as a
visual reference for the iOS design agent to match against (parachord-mobile#173).

**Whenever you make a visible change to a screen, re-capture it** so these stay
accurate (see "Refreshing" below). Each image is the *entire* screen rendered on
a tall virtual display and captured in one shot, not just the visible viewport.

## Files

| File | What |
|------|------|
| `*.png` | One full-page screenshot per screen (filename = screen name). |
| `capture.sh` | Drives the device: deep-links to each screen on a tall display, captures, trims. |
| `trim.py` | Removes the empty band between the content and the bottom-pinned mini-player/nav (Pillow). |

## How it works

1. Each screen is reached by firing its `parachord://` **deep link** at the
   installed debug app (`MainActivity` is `singleTop`, so the link navigates
   in place — no UI tapping needed). Routes live in
   [`DeepLinkHandler.kt`](../../../app/src/main/java/com/parachord/android/deeplink/DeepLinkHandler.kt).
2. The screen is rendered onto a very **tall virtual display**
   (`adb shell wm size 1080xN`) so the whole page lays out at once, then grabbed
   in a single `adb exec-out screencap` — no scrolling, no stitching, no seams.
   Density is unchanged, so element sizes stay pixel-true; only the viewport is
   tall. The device's real resolution is restored (`wm size reset`) at the end.
3. `trim.py` removes the band of empty background between the content and the
   bottom-pinned mini-player/nav bar (so short screens aren't mostly whitespace).

**Length limit:** Android caps `wm size` at **3× the native height (~7272px)**.
Short/medium screens capture in full. Very long lists (a big library, 150
playlists) are captured to ~3 screenfuls — the full layout + plenty of rows,
which is all a design reference needs. `capture.sh` notes which screens hit that
ceiling.

## Refreshing

Prerequisites:
- The **debug** build installed on a connected device: `./gradlew installDebug`
- `adb` on PATH (or pass `ADB=/path/to/adb`)
- Python 3 with **Pillow**:
  ```bash
  python3 -m venv .venv && .venv/bin/pip install Pillow
  ```
- The device unlocked (the script wakes it and keeps the screen on over USB).

Run from this directory:
```bash
PY=.venv/bin/python ./capture.sh
```
Useful overrides: `SETTLE=8` (slower networks; the artist/discover screens fetch
a lot — bump this if one captures a loading skeleton), `ARTIST="Bjork"`
(artist-screen seed). See the header of `capture.sh`.

Re-run the whole script anytime (it's idempotent — overwrites every PNG), or
capture a single screen by hand with the same `wm size` → `screencap` →
`trim.py` → `wm size reset` steps.

## Screens captured by deep link (automatic, via `capture.sh`)

`home`, `library`, `playlists`, `history`, `settings`, `chat`, `search`,
`recommendations`, `charts` (Pop of the Tops), `critics-picks`
(Critical Darlings), `artist`, `album`.

## Screens captured by hand (no stable deep link)

These have no deep link, so they're navigated to in-app, then captured with the
same tall-display + `trim.py` method (set `wm size 1080x7272`, `screencap`,
`trim.py`, `wm size reset`). **Captured:**

| Screen | How it was reached |
|--------|--------------------|
| `now-playing` | Tap the mini-player while something is playing. |
| `queue` | Now Playing → expand "UP NEXT". |
| `playlist-detail` | Open any playlist from the Playlists screen. |
| `friends` | + FAB → Add Friend → Friends list. |
| `edit-playlist` | Playlist detail → ⋮ → Edit (reorder/remove). |
| `weekly-playlist` | Home → a Weekly Jams / Weekly Exploration card. |

**Not yet captured** (lower priority — these reuse track-row / album-grid /
detail patterns already visible in the captured screens):

| Screen | How to reach |
|--------|--------------|
| `concerts` | Discover → Concerts / an artist's On Tour tab. |
| `fresh-drops` | Discover → Fresh Drops. |
| `friend-detail` | Friends list → tap a friend. |

## Privacy note

These are captured from a real device, so they contain real library, playlist,
friend, and listening-history data. Scrub or re-capture with a throwaway account
if any of these will be shared outside the team.
