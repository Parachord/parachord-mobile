#!/usr/bin/env bash
#
# Capture FULL-PAGE reference screenshots of every Parachord Android screen.
#
# Purpose: a visual reference library of the *existing Android UI* for the iOS
# design agent to match against (parachord-mobile#173). Re-run after any visible
# change to a screen so the references stay current.
#
# Method: render each screen onto a very TALL virtual display (`adb shell wm
# size 1080xN`) so the whole page lays out at once, then grab it in a single
# `screencap` — no scrolling, no stitching. `trim.py` removes the empty band
# between the content and the bottom-pinned mini-player/nav bar. If the page is
# still taller than the virtual display (content truncated), we retry taller.
#
# This renders at the device's normal density, so element sizes are pixel-true;
# only the viewport is unusually tall. Each screen is reached by its
# `parachord://` deep link (routes: DeepLinkHandler.kt). The device's real
# resolution is restored (`wm size reset`) at the end.
#
# Requirements:
#   - DEBUG app installed on a connected device:  ./gradlew installDebug
#   - adb on PATH (or ADB=/path/to/adb)
#   - Python 3 with Pillow:  python3 -m venv .venv && .venv/bin/pip install Pillow
#   - Device unlocked (the script wakes it + keeps the screen on over USB).
#
# Usage:
#   PY=.venv/bin/python ./capture.sh
#   SETTLE=7 ./capture.sh            # longer render/settle on slow networks
#   ARTIST="Bjork" ./capture.sh      # override the artist-screen seed
#
# Screens with no stable deep link (Now Playing, Queue, Playlist/Friend detail,
# the Discover sub-tabs, Edit Playlist, Weekly playlist) are captured by hand —
# see README.md.
#
set -uo pipefail

ADB="${ADB:-adb}"
PKG="${PKG:-com.parachord.android.debug}"
PY="${PY:-python3}"
OUT="$(cd "$(dirname "$0")" && pwd)"
TRIM="$OUT/trim.py"

SETTLE="${SETTLE:-6}"            # settle after navigation (render + image load)
# Adaptive virtual-display heights. Android caps `wm size` at 3x the native
# height (7272 on a 2424 device), so that's the ceiling: short screens capture
# fully at 6000 (+ gap trim); anything taller than ~7272 (very long lists like
# playlists / a big library) is captured to ~3 screenfuls — a fine full-page
# reference, no one needs all 150 rows.
HEIGHTS="${HEIGHTS:-6000 7272}"

ARTIST="${ARTIST:-Radiohead}"
ALBUM_ARTIST="${ALBUM_ARTIST:-Radiohead}"
ALBUM_TITLE="${ALBUM_TITLE:-In Rainbows}"
SEARCH_QUERY="${SEARCH_QUERY:-radiohead}"

urlencode() { printf '%s' "$1" | sed 's/ /%20/g'; }

# capture_fullpage <name> <deeplink>
capture_fullpage() {
  local name="$1" link="$2"
  local raw; raw="$(mktemp).png"
  local h rc
  for h in $HEIGHTS; do
    "$ADB" shell wm size "1080x${h}" >/dev/null 2>&1
    sleep 1
    "$ADB" shell am start -a android.intent.action.VIEW -d "$link" "$PKG" >/dev/null 2>&1
    sleep "$SETTLE"
    "$ADB" exec-out screencap -p > "$raw"
    if "$PY" "$TRIM" "$raw" "$OUT/$name.png"; then
      rc=0; break                 # trimmed cleanly -> short/medium screen, fully captured
    else
      rc=$?                       # 3 = truncated -> retry taller (or accept at the ceiling)
    fi
  done
  # rc=3 at the tallest height just means a very long list captured to ~3
  # screenfuls — the image is still written; nothing to fix.
  [ "${rc:-3}" -ne 0 ] && echo "  (long screen: $name captured to display max — full layout shown, tail of the list cut)"
  rm -f "$raw"
}

echo "Parachord Android full-page capture -> $OUT"
echo "Device: $("$ADB" get-state 2>/dev/null || echo NONE)   Python: $("$PY" -c 'import PIL;print("Pillow",PIL.__version__)' 2>/dev/null || echo 'NO PILLOW')"

"$ADB" shell svc power stayon true        >/dev/null 2>&1
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; sleep 1
"$ADB" shell wm dismiss-keyguard           >/dev/null 2>&1; sleep 1
# Pause playback so the mini-player progress bar is static.
"$ADB" shell input keyevent 127           >/dev/null 2>&1; sleep 1

capture_fullpage home            "parachord://home"
capture_fullpage library         "parachord://library"
capture_fullpage playlists       "parachord://playlists"
capture_fullpage history         "parachord://history"
capture_fullpage settings        "parachord://settings"
capture_fullpage chat            "parachord://chat"
capture_fullpage search          "parachord://search?q=$(urlencode "$SEARCH_QUERY")"
capture_fullpage recommendations "parachord://recommendations"
capture_fullpage charts          "parachord://charts"
capture_fullpage critics-picks   "parachord://critics-picks"
capture_fullpage artist          "parachord://artist/$(urlencode "$ARTIST")"
capture_fullpage album           "parachord://album/$(urlencode "$ALBUM_ARTIST")/$(urlencode "$ALBUM_TITLE")"

"$ADB" shell wm size reset >/dev/null 2>&1
"$ADB" shell am start -a android.intent.action.VIEW -d "parachord://home" "$PKG" >/dev/null 2>&1
echo
echo "Restored display. Capture by hand (no stable deep link): now-playing, queue,"
echo "playlist-detail, friend-detail, friends, concerts, fresh-drops, edit-playlist, weekly-playlist."
