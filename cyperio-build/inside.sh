#!/bin/bash
# Runs inside the build container. /src = repo (bind mount), /cache = persistent
# volume (gradle home, native libs), /keys = signing keystore (optional), APKs -> /src/out.
set -euo pipefail
XRAY_TAG=v26.7.5   # tag of the AndroidLibXrayLite submodule commit
mkdir -p /work /cache/gradle /cache/dl
rsync -a --delete --exclude .git --exclude out --exclude 'V2rayNG/app/build' --exclude 'V2rayNG/.gradle' /src/ /work/
cd /work

# A Windows checkout stores git symlinks as small text files holding the target
# path; turn those back into real symlinks (the hev headers rely on them).
find hev-socks5-tunnel -type f -size -200c -path '*/include/*' | while read -r f; do
  t=$(cat "$f")
  if [ "$(printf '%s' "$t" | wc -l)" = 0 ] && [ -f "$(dirname "$f")/$t" ]; then
    ln -sf "$t" "$f"
  fi
done

key=$(cat compile-hevtun.sh hev-socks5-tunnel/build.mk | sha1sum | cut -c1-12)-$NDK_VERSION
if [ ! -d /cache/hev-$key ]; then
  bash compile-hevtun.sh
  cp -r libs /cache/hev-$key
fi
mkdir -p V2rayNG/app/libs && cp -r /cache/hev-$key/* V2rayNG/app/libs/
[ -f /cache/dl/libv2ray-$XRAY_TAG.aar ] || curl -fL -o /cache/dl/libv2ray-$XRAY_TAG.aar \
  https://github.com/2dust/AndroidLibXrayLite/releases/download/$XRAY_TAG/libv2ray.aar
cp /cache/dl/libv2ray-$XRAY_TAG.aar V2rayNG/app/libs/libv2ray.aar

cd V2rayNG
grep -q ndkVersion app/build.gradle.kts || sed -i "10i\    ndkVersion = \"$NDK_VERSION\"" app/build.gradle.kts
# Use the build-tools that are in the image. Without this the Android plugin wants its own
# default (36.0.0) and downloads it on every run - which fails whenever dl.google.com is unreachable.
grep -q buildToolsVersion app/build.gradle.kts || sed -i "10i\    buildToolsVersion = \"37.0.0\"" app/build.gradle.kts
echo "sdk.dir=$ANDROID_HOME" > local.properties
SIGN=()
# PLAY=1: the Google Play build (flavor gplay, AAB) signed with the Play upload key; otherwise the APKs.
if [ -n "${PLAY:-}" ]; then
  [ -f /keys/upload.jks ] || { echo "no upload key: /keys/upload.jks (see build-play.sh)"; exit 1; }
  . /keys/upload.env
  SIGN=(-Pandroid.injected.signing.store.file=/keys/upload.jks -Pandroid.injected.signing.store.password="$STORE_PASS"
        -Pandroid.injected.signing.key.alias="$KEY_ALIAS" -Pandroid.injected.signing.key.password="$KEY_PASS")
elif [ -f /keys/cyperio.jks ]; then
  . /keys/signing.env
  SIGN=(-Pandroid.injected.signing.store.file=/keys/cyperio.jks -Pandroid.injected.signing.store.password="$STORE_PASS"
        -Pandroid.injected.signing.key.alias="$KEY_ALIAS" -Pandroid.injected.signing.key.password="$KEY_PASS")
fi
export GRADLE_USER_HOME=/cache/gradle
# Gradle asks for build-tools 36, which the image does not have: it used to download them on
# every run (and failed whenever dl.google.com was unreachable). Keep them in the cache volume.
BT=36.0.0
[ -d /cache/build-tools-$BT ] && [ ! -d $ANDROID_HOME/build-tools/$BT ] && cp -r /cache/build-tools-$BT $ANDROID_HOME/build-tools/$BT
if [ -n "${PLAY:-}" ]; then
  bash ./gradlew --no-daemon bundleGplayRelease "${SIGN[@]}" "$@"
  [ -d $ANDROID_HOME/build-tools/$BT ] && [ ! -d /cache/build-tools-$BT ] && cp -r $ANDROID_HOME/build-tools/$BT /cache/build-tools-$BT
  # the Play build must not be able to update itself or sell: fail if the merged manifest still says so
  M=$(find app/build/intermediates -path '*gplayRelease*' -name AndroidManifest.xml | head -1)
  [ -n "$M" ] || { echo "merged manifest not found"; exit 1; }
  if grep -n -B2 -A2 'REQUEST_INSTALL_PACKAGES\|QUERY_ALL_PACKAGES\|BuyActivity' "$M"; then echo "gplay manifest still has self-update / buy / all-packages: $M"; exit 1; fi
  mkdir -p /src/out/play && rm -f /src/out/play/*.aab
  cp app/build/outputs/bundle/gplayRelease/*.aab /src/out/play/
  ls -la /src/out/play
  exit 0
fi
bash ./gradlew --no-daemon assemblePlaystoreRelease "${SIGN[@]}" "$@"
[ -d $ANDROID_HOME/build-tools/$BT ] && [ ! -d /cache/build-tools-$BT ] && cp -r $ANDROID_HOME/build-tools/$BT /cache/build-tools-$BT
mkdir -p /src/out && rm -f /src/out/*.apk
cp app/build/outputs/apk/playstore/release/*.apk /src/out/
ls -la /src/out
