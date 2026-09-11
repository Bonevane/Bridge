#!/usr/bin/env bash
# Downloads the prebuilt Linux arm64 dumbpipe (the one that worked in Termux)
# and places it where the Android build packages it as a native library.
set -euo pipefail

VERSION="${1:-v0.39.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

URL="https://github.com/n0-computer/dumbpipe/releases/download/${VERSION}/dumbpipe-${VERSION}-linux-aarch64.tar.gz"
echo "Downloading $URL"
curl -fL "$URL" -o "$TMP/dumbpipe.tar.gz"
tar -xzf "$TMP/dumbpipe.tar.gz" -C "$TMP"

BIN="$(find "$TMP" -type f -name dumbpipe | head -1)"
if [ -z "$BIN" ]; then
  echo "Couldn't find the dumbpipe binary inside the archive." >&2
  exit 1
fi

mkdir -p "$DEST"
cp "$BIN" "$DEST/libdumbpipe.so"
chmod 755 "$DEST/libdumbpipe.so"
echo "Done: $DEST/libdumbpipe.so"
