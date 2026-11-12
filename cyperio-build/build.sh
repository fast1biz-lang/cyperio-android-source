#!/bin/bash
# Build the APKs in Docker:  cyperio-build/build.sh   (APKs land in ./out)
# Signing: put cyperio.jks + signing.env (STORE_PASS, KEY_ALIAS, KEY_PASS) in $CYPERIO_KEYS
# (default ~/.cyperio-keys). Without them the APKs are unsigned.
set -euo pipefail
cd "$(dirname "$0")/.."
KEYS="${CYPERIO_KEYS:-$HOME/.cyperio-keys}"; mkdir -p "$KEYS"
# The image only holds the toolchain; build it once (REBUILD_IMAGE=1 forces a rebuild).
if [ -n "${REBUILD_IMAGE:-}" ] || ! docker image inspect cyperio-android-build >/dev/null 2>&1; then
  docker build -q -t cyperio-android-build cyperio-build
fi
MSYS_NO_PATHCONV=1 docker run --rm -e PLAY -v "$(pwd -W 2>/dev/null || pwd):/src" -v cyperio-build-cache:/cache \
  -v "$(cd "$KEYS" && (pwd -W 2>/dev/null || pwd)):/keys:ro" --entrypoint bash cyperio-android-build /src/cyperio-build/inside.sh "$@"
