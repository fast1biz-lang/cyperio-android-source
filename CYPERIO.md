# Cyperio for Android

Cyperio is a rebranded build of [v2rayNG](https://github.com/2dust/v2rayNG) (GPL-3.0), based on tag 2.2.6.
This repository contains the complete modified source, as the licence requires.

Changes from upstream (all applied by `cyperio-build/brand.py`):
- app name, application id (`com.cyperio.app`), icons and colours
- first-run dialog asking for the subscription link / QR code
- imported subscriptions auto-update every 3 hours; default routing preset is "Iran" (Iranian sites direct)
- removed: promotion entry, upstream update check, upstream support links

Build (Docker): `bash cyperio-build/build.sh` -> APKs in `out/`.

Google Play build (flavor `gplay`): `bash cyperio-build/build-play.sh` -> signed AAB in `out/play/`.
It is the same app without the self-update (updates come from Google Play) and without the in-app
purchase page; the customer only enters their subscription link. The APK flavor (`playstore`) keeps both.
Signing keys are not part of this repository.

Source code for the Google Play release: https://github.com/fast1biz-lang/cyperio-android-source
