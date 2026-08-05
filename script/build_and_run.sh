#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-run}"
APP_PROCESS="NumenBridge"
BUNDLE_ID="com.dwinovo.numen.bridge"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="$ROOT_DIR/bridge/macos"
APP_BUNDLE="$PROJECT_DIR/build/Numen Bridge.app"
APP_BINARY="$APP_BUNDLE/Contents/MacOS/NumenBridge"

pkill -x "$APP_PROCESS" >/dev/null 2>&1 || true
for _ in {1..10}; do
  if ! pgrep -x "$APP_PROCESS" >/dev/null; then
    break
  fi
  sleep 0.2
done
if pgrep -x "$APP_PROCESS" >/dev/null; then
  pkill -KILL -x "$APP_PROCESS" >/dev/null 2>&1 || true
fi

cd "$PROJECT_DIR"
CLANG_MODULE_CACHE_PATH=/private/tmp/numen-clang-cache \
SWIFT_MODULECACHE_PATH=/private/tmp/numen-swift-cache \
./scripts/package-app.sh

open_app() {
  /usr/bin/open -n "$APP_BUNDLE"
}

case "$MODE" in
  run)
    open_app
    ;;
  --debug|debug)
    /usr/bin/lldb -- "$APP_BINARY"
    ;;
  --logs|logs)
    open_app
    /usr/bin/log stream --info --style compact --predicate "process == \"$APP_PROCESS\""
    ;;
  --telemetry|telemetry)
    open_app
    /usr/bin/log stream --info --style compact --predicate "subsystem == \"$BUNDLE_ID\""
    ;;
  --verify|verify)
    open_app
    sleep 2
    pgrep -x "$APP_PROCESS" >/dev/null
    ;;
  *)
    echo "usage: $0 [run|--debug|--logs|--telemetry|--verify]" >&2
    exit 2
    ;;
esac
