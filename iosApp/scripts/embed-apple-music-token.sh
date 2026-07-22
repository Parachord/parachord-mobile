#!/bin/bash
#
# Xcode build phase: stamp a freshly-minted Apple Music developer token into the
# built Info.plist (parachord-mobile#186).
#
# The token is an ES256 JWT with exp <= 6 months. It used to be hand-pasted into
# BOTH `iosApp/Parachord/Secrets.xcconfig` and Android `local.properties`;
# forgetting either meant that platform's Apple Music artist images silently
# stopped working, because `AppleMusicArtistProvider.isAvailable()` only checked
# `isNotBlank()`, never `exp`. Minting from the `.p8` per build kills both the
# drift and the silent expiry.
#
# ONE signer, shared by both platforms: this asks Gradle for the token
# (`:app:printAppleMusicToken` -> buildSrc/AppleMusicToken.kt), the same code
# path Android's BuildConfig uses. Do NOT reimplement JWT signing here — a
# second implementation is exactly what drifts out of sync.
#
# Why write the built plist instead of an xcconfig: xcconfig values are resolved
# before any build phase runs, so a phase physically cannot feed one. Writing
# `$TARGET_BUILD_DIR/$INFOPLIST_PATH` after "Process Info.plist" (and before
# code signing) is the supported way to inject a build-time value.
#
# FAIL-SOFT BY DESIGN: if generation fails (no .p8 configured — e.g. an outside
# contributor, or CI without the secret) this leaves whatever
# Secrets.xcconfig already supplied and only warns. Apple Music artist images
# are a metadata gap-filler; they must never break the build.
#
set -uo pipefail

PLIST="${TARGET_BUILD_DIR:-}/${INFOPLIST_PATH:-}"

if [ -z "${TARGET_BUILD_DIR:-}" ] || [ -z "${INFOPLIST_PATH:-}" ]; then
  echo "warning: Apple Music token: not in an Xcode build context; skipping"
  exit 0
fi

if [ ! -f "$PLIST" ]; then
  echo "warning: Apple Music token: no Info.plist at $PLIST; skipping"
  exit 0
fi

REPO_ROOT="$SRCROOT/.."
cd "$REPO_ROOT" || { echo "warning: Apple Music token: cannot cd to repo root"; exit 0; }

# -q keeps stdout to just the token; take the last line defensively.
TOKEN="$(./gradlew -q :app:printAppleMusicToken 2>/dev/null | tail -1 | tr -d '[:space:]')"

if [ -z "$TOKEN" ]; then
  echo "warning: Apple Music token: generation produced nothing — keeping the value from Secrets.xcconfig."
  echo "warning: To generate, set APPLE_MUSIC_P8_PATH / APPLE_MUSIC_KEY_ID / APPLE_MUSIC_TEAM_ID in local.properties."
  exit 0
fi

# Sanity-check it looks like a JWT (three dot-separated segments) before writing.
case "$TOKEN" in
  *.*.*) : ;;
  *)
    echo "warning: Apple Music token: output is not a JWT; keeping the Secrets.xcconfig value"
    exit 0
    ;;
esac

/usr/libexec/PlistBuddy -c "Set :AppleMusicDeveloperToken $TOKEN" "$PLIST" 2>/dev/null \
  || /usr/libexec/PlistBuddy -c "Add :AppleMusicDeveloperToken string $TOKEN" "$PLIST"

echo "Apple Music token: embedded freshly-minted JWT into $INFOPLIST_PATH"
