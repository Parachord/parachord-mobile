#!/bin/bash
#
# Xcode build phase: stamp a current Apple Music developer token into the built
# Info.plist (parachord-mobile#186, iOS half).
#
# The token is an ES256 JWT that Apple caps at 180 days. Android already mints
# it during the Gradle build (app/build.gradle.kts -> scripts/apple-music-token.py
# --ensure), so an Android dev machine can't ship a dead token. iOS was the
# remaining hand-paste: Secrets.xcconfig held a static value, and when it lapsed
# Apple Music catalog resolution and artist images silently stopped working —
# that is exactly what happened in Sept 2026.
#
# ONE signer, shared by both platforms: this shells out to the same
# scripts/apple-music-token.py Gradle uses. Do NOT reimplement JWT signing here
# — a second implementation is precisely what drifts out of sync, which is the
# problem this is meant to end.
#
# --ensure, not --print: it rotates only inside the 30-day renewal window, so a
# normal build re-uses the existing token and Info.plist stays stable instead of
# churning on every compile.
#
# Why write the BUILT plist rather than an xcconfig: xcconfig values are
# resolved before any build phase runs, so a phase physically cannot feed one.
# Writing "$TARGET_BUILD_DIR/$INFOPLIST_PATH" after "Process Info.plist" (and
# before code signing) is the supported way to inject a build-time value — and
# it is why a token rotated during THIS build still reaches THIS build.
#
# FAIL-SOFT BY DESIGN: if no .p8 is configured (an outside contributor, or CI
# without the key) this leaves whatever Secrets.xcconfig supplied and only
# warns. A metadata token must never break the build.
#
set -uo pipefail

if [ -z "${TARGET_BUILD_DIR:-}" ] || [ -z "${INFOPLIST_PATH:-}" ]; then
  echo "warning: Apple Music token: not in an Xcode build context; skipping"
  exit 0
fi

PLIST="$TARGET_BUILD_DIR/$INFOPLIST_PATH"
if [ ! -f "$PLIST" ]; then
  echo "warning: Apple Music token: no Info.plist at $PLIST; skipping"
  exit 0
fi

REPO_ROOT="$SRCROOT/.."
cd "$REPO_ROOT" || { echo "warning: Apple Music token: cannot cd to repo root"; exit 0; }

# Rotate only if expiring; writes Secrets.xcconfig (and local.properties) so the
# next build starts fresh. Never fatal — no .p8 configured just means no rotation.
python3 ./scripts/apple-music-token.py --ensure >/dev/null 2>&1 || true

# Read back whatever is current: the just-rotated value, or the existing one.
TOKEN="$(sed -n 's/^[[:space:]]*APPLE_MUSIC_DEVELOPER_TOKEN[[:space:]]*=[[:space:]]*//p' \
  iosApp/Parachord/Secrets.xcconfig 2>/dev/null | tail -1 | tr -d '[:space:]"')"

if [ -z "$TOKEN" ]; then
  echo "warning: Apple Music token: none available — keeping the value already in the plist."
  echo "warning: Set APPLE_MUSIC_AUTHKEY_P8 in local.properties to mint one (see local.properties.example)."
  exit 0
fi

# Sanity-check it looks like a JWT before writing; a malformed value in the
# plist is worse than the stale one already there.
case "$TOKEN" in
  *.*.*) : ;;
  *)
    echo "warning: Apple Music token: value is not a JWT; keeping the plist value"
    exit 0
    ;;
esac

/usr/libexec/PlistBuddy -c "Set :AppleMusicDeveloperToken $TOKEN" "$PLIST" 2>/dev/null \
  || /usr/libexec/PlistBuddy -c "Add :AppleMusicDeveloperToken string $TOKEN" "$PLIST"

echo "Apple Music token: embedded current JWT into $INFOPLIST_PATH"
