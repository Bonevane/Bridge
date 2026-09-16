#!/usr/bin/env bash
# Builds Bridge.app (a menu-bar app) into ./build and signs it for this Mac.
set -euo pipefail
cd "$(dirname "$0")"

# One version string for both bundle keys, so About shows "Version 0.1"
# rather than "Version 0.1 (1)". Override: VERSION=0.2 ./make-app.sh
VERSION="${VERSION:-0.1.0}"
# Signing identity. Ad-hoc ("-") works but macOS forgets the app's permissions
# (Bluetooth, notifications) on every rebuild; a certificate, even a self-signed
# one from Keychain Access, keeps them. Override: SIGN_ID="Bridge Dev" ./make-app.sh
SIGN_ID="${SIGN_ID:--}"

swift build -c release

APP="build/Bridge.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/BridgeMac "$APP/Contents/MacOS/Bridge"
cp Bridge.icns MenuBarIcon.png MenuBarIcon@2x.png "$APP/Contents/Resources/"   # regenerate with ../assets/make-icons.sh

# Bundle the two command-line tools so a downloaded Bridge works with nothing
# else installed: dumbpipe (the tunnel) and adb (Set Up Over USB only). Both
# are Apache-2.0/MIT; see ../NOTICE. Taken from Homebrew if present, otherwise
# from ./vendor (put the binaries there by hand on a machine without Homebrew).
bundle_tool() {
    local name="$1"; shift
    for candidate in "$@" "vendor/$name"; do
        if [ -x "$candidate" ]; then
            cp "$(readlink -f "$candidate")" "$APP/Contents/MacOS/$name"
            echo "bundled $name from $candidate"
            return
        fi
    done
    echo "warning: $name not found; the app will look for it on the system instead" >&2
}
bundle_tool dumbpipe /opt/homebrew/bin/dumbpipe /usr/local/bin/dumbpipe
bundle_tool adb /opt/homebrew/share/android-commandlinetools/platform-tools/adb \
                /usr/local/share/android-commandlinetools/platform-tools/adb \
                "$HOME/Library/Android/sdk/platform-tools/adb"

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
    <key>CFBundleIconFile</key><string>Bridge</string>
    <key>CFBundleShortVersionString</key><string>$VERSION</string>
    <key>CFBundleVersion</key><string>$VERSION</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>LSUIElement</key><true/>
    <key>NSHumanReadableCopyright</key><string>© 2026 Bonevane</string>
    <key>NSBluetoothAlwaysUsageDescription</key>
    <string>Bridge uses Bluetooth for the short-range link to your phone, so notifications and the clipboard don't need an internet connection.</string>
</dict>
</plist>
PLIST

# Sign the bundled tools first (nested code must be signed before the bundle),
# then the app. --deep is deprecated but the CLT toolchain has nothing better
# for a plain script; the explicit inner signs are what actually matter.
for tool in "$APP"/Contents/MacOS/dumbpipe "$APP"/Contents/MacOS/adb; do
    [ -f "$tool" ] && codesign --force --sign "$SIGN_ID" "$tool"
done
codesign --force --sign "$SIGN_ID" "$APP"
echo "Built $APP (version $VERSION, signed by ${SIGN_ID}). Open it with: open $APP"
