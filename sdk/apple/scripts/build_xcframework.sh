#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
OUT=${1:-"$ROOT/build/apple"}
rm -rf "$OUT"; mkdir -p "$OUT"

for SDK in iphoneos iphonesimulator; do
  if [[ "$SDK" == iphoneos ]]; then ARCHS=arm64; else ARCHS="arm64;x86_64"; fi

  cmake -S "$ROOT/sdk/native" -B "$OUT/core-$SDK" -G Xcode \
    -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$SDK" -DCMAKE_OSX_ARCHITECTURES="$ARCHS" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 -DSTABLEAR_BUILD_TESTS=OFF -DSTABLEAR_BUILD_SHARED=OFF
  cmake --build "$OUT/core-$SDK" --config Release --target stablear
  CORE_LIB=$(find "$OUT/core-$SDK" -name 'libstablear.a' -type f | head -1)
  test -n "$CORE_LIB"; cp "$CORE_LIB" "$OUT/libstablear-$SDK.a"

  # The learned XFeat matcher is OpenCV-free. It intentionally ships as a second native archive
  # whose unresolved StableAR geometry symbols are satisfied by StableARNative at final app link.
  cmake -S "$ROOT/sdk/native-vision" -B "$OUT/xfeat-$SDK" -G Xcode \
    -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$SDK" -DCMAKE_OSX_ARCHITECTURES="$ARCHS" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 -DSTABLEAR_VISION_BUILD_TESTS=OFF \
    -DSTABLEAR_VISION_BUILD_OPENCV=OFF -DSTABLEAR_BUILD_TESTS=OFF
  cmake --build "$OUT/xfeat-$SDK" --config Release --target stablear_xfeat
  XFEAT_LIB=$(find "$OUT/xfeat-$SDK" -name 'libstablear_xfeat.a' -type f | head -1)
  test -n "$XFEAT_LIB"; cp "$XFEAT_LIB" "$OUT/libstablear-xfeat-$SDK.a"
done

xcodebuild -create-xcframework \
  -library "$OUT/libstablear-iphoneos.a" -headers "$ROOT/sdk/apple/Headers" \
  -library "$OUT/libstablear-iphonesimulator.a" -headers "$ROOT/sdk/apple/Headers" \
  -output "$OUT/StableARNative.xcframework"

XFEAT_HEADERS="$OUT/XFeatHeaders"
mkdir -p "$XFEAT_HEADERS"
cp "$ROOT/sdk/native-vision/include/stablear/vision_c.h" "$XFEAT_HEADERS/stablear_vision_c.h"
cat > "$XFEAT_HEADERS/module.modulemap" <<'EOF'
module StableARXFeatNative {
  header "stablear_vision_c.h"
  export *
}
EOF
xcodebuild -create-xcframework \
  -library "$OUT/libstablear-xfeat-iphoneos.a" -headers "$XFEAT_HEADERS" \
  -library "$OUT/libstablear-xfeat-iphonesimulator.a" -headers "$XFEAT_HEADERS" \
  -output "$OUT/StableARXFeatNative.xcframework"

echo "$OUT/StableARNative.xcframework"
echo "$OUT/StableARXFeatNative.xcframework"
