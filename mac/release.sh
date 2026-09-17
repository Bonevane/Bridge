#!/usr/bin/env bash
# Builds the shippable macOS artefact: build/Bridge-<version>.dmg, the usual
# "drag Bridge onto Applications" disk image.
#   VERSION=0.1.0 SIGN_ID="Bridge Dev" ./release.sh
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${VERSION:-0.1.1}"
export VERSION SIGN_ID="${SIGN_ID:--}"
./make-app.sh
codesign --verify --deep --strict build/Bridge.app && echo "signature ok"

STAGE="build/dmg"
rm -rf "$STAGE" "build/Bridge-$VERSION.dmg"
mkdir -p "$STAGE"
cp -R build/Bridge.app "$STAGE/"
ln -s /Applications "$STAGE/Applications"
# A note for the Gatekeeper step, since the image isn't notarized.
cat > "$STAGE/First launch - read me.txt" <<'TXT'
Drag Bridge onto Applications.

Bridge isn't notarized by Apple, so the first time, right-click Bridge in
Applications and choose Open, then Open again. After that it opens normally.
TXT
hdiutil create -volname "Bridge $VERSION" -srcfolder "$STAGE" -ov -format UDZO -quiet "build/Bridge-$VERSION.dmg"
codesign --force --sign "$SIGN_ID" "build/Bridge-$VERSION.dmg"
rm -rf "$STAGE"
ls -la "build/Bridge-$VERSION.dmg" | awk '{print $5, $9}'
