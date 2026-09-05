#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "APK not found. Run scripts/build-android.sh first." >&2; exit 1; }
if [ "$#" -gt 0 ]; then
  adb -s "$1" install -r "$APK"
  exit $?
fi
DEVICES=$(adb devices | awk 'NR>1 && $2 == "device" {print $1}')
[ -n "$DEVICES" ] || { echo "No adb devices found." >&2; exit 1; }
for d in $DEVICES; do
  echo "Installing on $d"
  adb -s "$d" install -r "$APK"
done
