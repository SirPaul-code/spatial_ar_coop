#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
(cd server && npm install && npm test && npm run check)
(cd android && ./gradlew testDebugUnitTest assembleDebug --stacktrace)
