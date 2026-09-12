#!/usr/bin/env bash
# Builds Bridge.app (a menu-bar app) into ./build and signs it for this Mac.
set -euo pipefail
cd "$(dirname "$0")"

swift build -c release

APP="build/Bridge.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp .build/release/BridgeMac "$APP/Contents/MacOS/Bridge"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>Bridge</string>
    <key>CFBundleDisplayName</key><string>Bridge</string>
    <key>CFBundleIdentifier</key><string>com.bonevane.bridge.mac</string>
    <key>CFBundleExecutable</key><string>Bridge</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>0.1</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>LSUIElement</key><true/>
    <key>NSHumanReadableCopyright</key><string>Made by Bonevane · bonevane.vercel.app</string>
    <key>NSBluetoothAlwaysUsageDescription</key>
    <string>Bridge uses Bluetooth for the short-range link to your phone, so notifications and the clipboard don't need an internet connection.</string>
</dict>
</plist>
PLIST

codesign --force --sign - "$APP"
echo "Built $APP. Open it with: open $APP"
