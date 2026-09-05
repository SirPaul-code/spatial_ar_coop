#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT/android"
./gradlew --no-daemon clean assembleDebug
printf 'APK: %s\n' "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
