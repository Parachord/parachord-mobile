#!/usr/bin/env bash
#
# Archive + export (and optionally upload) the Parachord iOS app for
# TestFlight / App Store Connect.
#
#   ./iosApp/scripts/testflight.sh              # archive + export a signed .ipa
#   ./iosApp/scripts/testflight.sh --upload     # also upload to App Store Connect
#
# Signing is Automatic (team YR3XETE537). For the archive/export steps, Xcode
# must be signed into an account with access to the team (Xcode > Settings >
# Accounts) — -allowProvisioningUpdates then creates the distribution cert +
# App Store profiles for the app AND the embedded Share Extension.
#
# --upload needs an App Store Connect API key (avoids an interactive password):
#   export ASC_KEY_ID=XXXXXXXXXX          # the key's Key ID
#   export ASC_ISSUER_ID=xxxxxxxx-....    # the Issuer ID (App Store Connect > Users and Access > Integrations)
# and place the key at ~/.appstoreconnect/private_keys/AuthKey_<ASC_KEY_ID>.p8
# (altool searches ./private_keys, ~/private_keys, ~/.private_keys,
#  ~/.appstoreconnect/private_keys).
#
# First-time prerequisites (do these ONCE, outside this script):
#   1. Create the app record for com.parachord.ios in App Store Connect.
#   2. Fill iosApp/Parachord/Secrets.xcconfig (Last.fm / Apple Music JWT /
#      Achordion) — the build works without it but ships those features inert.
#   3. Answer export-compliance in App Store Connect (HTTPS-only ⇒ exempt) or
#      you'll be prompted per upload.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

PROJECT="$IOS_DIR/Parachord.xcodeproj"
SCHEME="Parachord"
BUILD_DIR="$IOS_DIR/build/testflight"
ARCHIVE="$BUILD_DIR/Parachord.xcarchive"
EXPORT_DIR="$BUILD_DIR/export"
EXPORT_OPTIONS="$IOS_DIR/ExportOptions.plist"

UPLOAD=0
[[ "${1:-}" == "--upload" ]] && UPLOAD=1

# --- preflight ---------------------------------------------------------------
if [[ ! -f "$IOS_DIR/Parachord/Secrets.xcconfig" ]]; then
  echo "⚠️  iosApp/Parachord/Secrets.xcconfig is missing — Last.fm, Apple Music"
  echo "    artist images, and Achordion pre-warm will be INERT in this build."
  echo "    Ctrl-C to abort, or continuing in 5s…"
  sleep 5
fi

# --- archive -----------------------------------------------------------------
echo "▶︎ Cleaning + archiving (Release, generic/platform=iOS)…"
rm -rf "$ARCHIVE" "$EXPORT_DIR"
xcodebuild clean archive \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$ARCHIVE" \
  -allowProvisioningUpdates

# --- export ------------------------------------------------------------------
echo "▶︎ Exporting signed .ipa…"
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE" \
  -exportPath "$EXPORT_DIR" \
  -exportOptionsPlist "$EXPORT_OPTIONS" \
  -allowProvisioningUpdates

IPA="$(/usr/bin/find "$EXPORT_DIR" -maxdepth 1 -name '*.ipa' | head -1)"
if [[ -z "$IPA" ]]; then
  echo "✗ No .ipa produced in $EXPORT_DIR" >&2
  exit 1
fi
echo "✅ Exported: $IPA"

# --- upload (optional) -------------------------------------------------------
if [[ "$UPLOAD" == "1" ]]; then
  : "${ASC_KEY_ID:?set ASC_KEY_ID (App Store Connect API Key ID) for --upload}"
  : "${ASC_ISSUER_ID:?set ASC_ISSUER_ID (API Key Issuer ID) for --upload}"
  echo "▶︎ Uploading to App Store Connect…"
  xcrun altool --upload-app \
    --type ios \
    --file "$IPA" \
    --apiKey "$ASC_KEY_ID" \
    --apiIssuer "$ASC_ISSUER_ID"
  echo "✅ Uploaded. The build appears in TestFlight after processing (~5–15 min)."
  echo "   Internal testers get it immediately; external groups need one Beta App Review."
else
  cat <<EOF

Archive is signed and ready. Upload it one of three ways:
  • Xcode → Window → Organizer → select the archive → Distribute App → App Store Connect
  • Transporter.app → drag in:
        $IPA
  • Re-run with an API key configured:
        ASC_KEY_ID=… ASC_ISSUER_ID=… $0 --upload
EOF
fi
