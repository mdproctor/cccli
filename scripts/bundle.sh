#!/usr/bin/env bash
set -euo pipefail

# Paths relative to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

APP_NAME="Claude Desktop CLI"
BINARY_NAME="claude-desktop"
BUNDLE="$PROJECT_DIR/app-macos/target/$APP_NAME.app"
CONTENTS="$BUNDLE/Contents"
MACOS_DIR="$CONTENTS/MacOS"
FRAMEWORKS_DIR="$CONTENTS/Frameworks"

NATIVE_BINARY="$PROJECT_DIR/app-macos/target/app-macos-1.0.0-SNAPSHOT-runner"
DYLIB="$PROJECT_DIR/mac-ui-bridge/build/libMyMacUI.dylib"
INFO_PLIST="$PROJECT_DIR/app-macos/src/main/resources/Info.plist"

echo "==> Checking prerequisites..."

if [ ! -f "$NATIVE_BINARY" ]; then
    echo "ERROR: Native binary not found: $NATIVE_BINARY"
    echo "Run: mvn package -pl app-macos -am -Pnative"
    exit 1
fi

if [ ! -f "$DYLIB" ]; then
    echo "ERROR: Dylib not found: $DYLIB"
    echo "Run: cd mac-ui-bridge && make"
    exit 1
fi

if [ ! -f "$INFO_PLIST" ]; then
    echo "ERROR: Info.plist not found: $INFO_PLIST"
    exit 1
fi

echo "==> Assembling $APP_NAME.app..."

# Create fresh bundle structure
rm -rf "$BUNDLE"
mkdir -p "$MACOS_DIR" "$FRAMEWORKS_DIR"

# Copy and rename binary
cp "$NATIVE_BINARY" "$MACOS_DIR/$BINARY_NAME"
chmod +x "$MACOS_DIR/$BINARY_NAME"

# Copy dylib and Info.plist
cp "$DYLIB"      "$FRAMEWORKS_DIR/libMyMacUI.dylib"
cp "$INFO_PLIST" "$CONTENTS/Info.plist"

echo "==> Ad-hoc signing bundle..."
codesign --sign - --force --deep "$BUNDLE"

echo ""
echo "✅ Bundle created: $BUNDLE"
echo ""
echo "Launch with:"
echo "  open \"$BUNDLE\""
echo ""
echo "If macOS blocks the app (Gatekeeper):"
echo "  xattr -dr com.apple.quarantine \"$BUNDLE\""
