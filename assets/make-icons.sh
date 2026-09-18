#!/usr/bin/env bash
# Regenerates every app icon from assets/bridge-mark.png.
# Outputs: mac/Bridge.icns, the Android mipmap/drawable resources, windows/assets/icon*.
set -euo pipefail
cd "$(dirname "$0")"

BIN=/tmp/bridge-make-icons
swiftc -O make-icons.swift -o "$BIN"
"$BIN" bridge-mark.png ../mac ../android/app/src/main/res ../windows/assets
iconutil -c icns ../mac/Bridge.iconset -o ../mac/Bridge.icns
rm -rf ../mac/Bridge.iconset
echo "wrote mac/Bridge.icns"
