#!/usr/bin/env bash
# Builds the shippable macOS artefact: build/Bridge-<version>.zip.
#   VERSION=0.1 SIGN_ID="Bridge Dev" ./release.sh
# ditto (not zip) keeps the code signature and resource forks intact.
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${VERSION:-0.1}"
export VERSION SIGN_ID="${SIGN_ID:--}"
./make-app.sh
rm -f "build/Bridge-$VERSION.zip"
ditto -c -k --keepParent build/Bridge.app "build/Bridge-$VERSION.zip"
codesign --verify --deep --strict build/Bridge.app && echo "signature ok"
ls -la "build/Bridge-$VERSION.zip" | awk '{print $5, $9}'
