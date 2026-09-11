#!/usr/bin/env bash
# Downloads scrcpy's server (Apache-2.0, https://github.com/Genymobile/scrcpy),
# the part that captures the screen and injects input on the phone. It is a
# dex jar, but we ship it under a .so name so Android extracts it to disk at
# install time (same trick as dumbpipe) where the shell-uid daemon can load it
# with `CLASSPATH=... app_process`.
set -euo pipefail

VERSION="${1:-v4.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/jniLibs/arm64-v8a"

URL="https://github.com/Genymobile/scrcpy/releases/download/${VERSION}/scrcpy-server-${VERSION}"
echo "Downloading $URL"
mkdir -p "$DEST"
curl -fL "$URL" -o "$DEST/libscrcpy.so"
echo "Done: $DEST/libscrcpy.so"
