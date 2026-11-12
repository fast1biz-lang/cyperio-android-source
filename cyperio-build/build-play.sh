#!/bin/bash
# Google Play build:  cyperio-build/build-play.sh   ->  out/play/Cyperio_<ver>-play.aab
# Flavor "gplay": no self-update, no purchase page (see app/build.gradle.kts). Signed with the Play
# UPLOAD key $CYPERIO_KEYS/upload.jks (+ upload.env); it is made here on first use. Google re-signs the
# app with its own app-signing key (Play App Signing). Keep upload.jks + upload.env backed up and out of git:
# without them no update can be uploaded (Play support can reset the upload key, which takes days).
# The APK build (build.sh) is not touched by this.
set -euo pipefail
cd "$(dirname "$0")/.."
KEYS="${CYPERIO_KEYS:-$HOME/.cyperio-keys}"; mkdir -p "$KEYS"
if [ ! -f "$KEYS/upload.jks" ]; then
  PASS=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 28)
  keytool -genkeypair -v -keystore "$KEYS/upload.jks" -storetype PKCS12 -alias upload -keyalg RSA -keysize 4096 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" -dname "CN=Cyperio, O=Optical Commercial International" >/dev/null
  printf 'STORE_PASS=%s\nKEY_ALIAS=upload\nKEY_PASS=%s\n' "$PASS" "$PASS" > "$KEYS/upload.env"
  chmod 600 "$KEYS/upload.jks" "$KEYS/upload.env" 2>/dev/null || true
  echo "new upload key: $KEYS/upload.jks (password in upload.env) - back both up"
fi
PLAY=1 bash cyperio-build/build.sh "$@"
VER=$(grep -o 'versionName = "[0-9.]*' V2rayNG/app/build.gradle.kts | cut -d'"' -f2)
for f in out/play/*.aab; do [ "$f" = "out/play/Cyperio_${VER}-play.aab" ] || mv -f "$f" "out/play/Cyperio_${VER}-play.aab"; done
ls -la out/play
