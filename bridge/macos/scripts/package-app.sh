#!/bin/zsh
set -euo pipefail

SCRIPT_DIR=${0:A:h}
PROJECT_DIR=${SCRIPT_DIR:h}
BUILD_DIR="$PROJECT_DIR/build"
APP_DIR="$BUILD_DIR/Numen Bridge.app"
CONTENTS_DIR="$APP_DIR/Contents"

cd "$PROJECT_DIR"
swift build -c release
BIN_DIR=$(swift build -c release --show-bin-path)

if [[ -e "$APP_DIR" ]]; then
    /bin/rm -rf "$APP_DIR"
fi
/bin/mkdir -p "$CONTENTS_DIR/MacOS" "$CONTENTS_DIR/Resources"
/bin/cp "$BIN_DIR/NumenBridge" "$CONTENTS_DIR/MacOS/NumenBridge"
/bin/cp "$PROJECT_DIR/Resources/Info.plist" "$CONTENTS_DIR/Info.plist"
/usr/bin/plutil -lint "$CONTENTS_DIR/Info.plist"

/usr/bin/codesign --force --sign - \
    --entitlements "$PROJECT_DIR/Resources/NumenBridge.entitlements" \
    "$APP_DIR"
/usr/bin/codesign --verify --deep --strict --verbose=2 "$APP_DIR"

ARCH=$(/usr/bin/lipo -archs "$CONTENTS_DIR/MacOS/NumenBridge")
if [[ "$ARCH" != *arm64* ]]; then
    print -u2 "Numen Bridge binary is not arm64: $ARCH"
    exit 1
fi

print "$APP_DIR"
