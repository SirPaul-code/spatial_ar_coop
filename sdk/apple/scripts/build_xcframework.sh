#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
OUT=${1:-"$ROOT/build/apple"}
rm -rf "$OUT"; mkdir -p "$OUT"
for SDK in iphoneos iphonesimulator; do
  if [[ "$SDK" == iphoneos ]]; then ARCHS=arm64; else ARCHS="arm64;x86_64"; fi
  cmake -S "$ROOT/sdk/native" -B "$OUT/$SDK" -G Xcode \
    -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$SDK" -DCMAKE_OSX_ARCHITECTURES="$ARCHS" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 -DSTABLEAR_BUILD_TESTS=OFF -DSTABLEAR_BUILD_SHARED=OFF
  cmake --build "$OUT/$SDK" --config Release --target stablear
  LIB=$(find "$OUT/$SDK" -name 'libstablear.a' -type f | head -1)
  test -n "$LIB"; cp "$LIB" "$OUT/libstablear-$SDK.a"
done
xcodebuild -create-xcframework \
  -library "$OUT/libstablear-iphoneos.a" -headers "$ROOT/sdk/apple/Headers" \
  -library "$OUT/libstablear-iphonesimulator.a" -headers "$ROOT/sdk/apple/Headers" \
  -output "$OUT/StableARNative.xcframework"
echo "$OUT/StableARNative.xcframework"
