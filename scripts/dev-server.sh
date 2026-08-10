#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/../server"
export HOST="${HOST:-0.0.0.0}"
export PORT="${PORT:-8080}"
export DATA_DIR="${DATA_DIR:-$PWD/data}"
exec node src/server.mjs
