# .app Bundle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package the native binary and dylib as a proper macOS `.app` bundle so the app can be launched from Finder without `-Dcccli.dylib.path`, and so WKWebView's subprocess model will work in Plan 5b.

**Architecture:** The dylib is already built with `-install_name @rpath/libMyMacUI.dylib`. We add `@executable_path/../Frameworks` to the native binary's rpath at link time, so it finds the dylib at `Contents/Frameworks/libMyMacUI.dylib`. `MacUIBridge` auto-detects the bundle path via `ProcessHandle` and falls back to the system property for dev mode. A shell script assembles the bundle structure and ad-hoc signs it.

**Tech Stack:** GraalVM native-image linker options, macOS `.app` bundle conventions, `codesign`, Maven exec plugin, Java `ProcessHandle` API

---

## Why This Order

The `@rpath` change (Task 1) must land before the bundle script (Task 4) is tested, because without it the binary won't find the dylib when launched from inside the bundle. The MacUIBridge change (Task 2) and Info.plist (Task 3) are independent but must exist before Task 4 assembles everything.

## .app Structure

```
app-macos/target/Claude Desktop CLI.app/
└── Contents/
    ├── Info.plist
    ├── MacOS/
    │   └── claude-desktop          ← renamed native binary
    └── Frameworks/
        └── libMyMacUI.dylib
```

## File Map

```
app-macos/src/main/resources/
└── Info.plist                              CREATE — bundle metadata

app-macos/src/main/
└── java/dev/mproctor/cccli/bridge/
    └── MacUIBridge.java                    MODIFY — auto-detect bundle dylib path

app-macos/src/main/resources/META-INF/native-image/
└── native-image.properties                 MODIFY — add @rpath linker option

app-macos/pom.xml                           MODIFY — exec bundle.sh in native profile

scripts/
└── bundle.sh                               CREATE — assemble and sign the .app
```

---

## Task 1: Add @rpath to native-image.properties

**Files:**
- Modify: `app-macos/src/main/resources/META-INF/native-image/native-image.properties`

The dylib is built with `-install_name @rpath/libMyMacUI.dylib`. The native binary needs `@executable_path/../Frameworks` in its rpath so it can resolve the dylib when placed at `Contents/MacOS/claude-desktop` and the dylib is at `Contents/Frameworks/libMyMacUI.dylib`.

- [ ] **Step 1: Update native-image.properties**

Replace the current content with:

```properties
Args = --enable-native-access=ALL-UNNAMED \
       -H:+ReportExceptionStackTraces \
       --initialize-at-run-time=dev.mproctor.cccli.bridge.gen \
       --initialize-at-run-time=dev.mproctor.cccli.pty.PosixLibrary \
       -H:NativeLinkerOption=-rpath \
       -H:NativeLinkerOption=@executable_path/../Frameworks
```

The two new lines pass `-rpath @executable_path/../Frameworks` to the macOS linker (`ld`). Each `-H:NativeLinkerOption` becomes a single linker argument — you need two separate entries for `-rpath` and its value.

- [ ] **Step 2: Build native image to confirm it compiles**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.0.2-graalce
cd /Users/mdproctor/claude/cccli
mvn package -pl app-macos -am -Pnative -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`. The rpath flag is passed silently — no output change. If you see a linker error about `-rpath`, the flag syntax is wrong.

- [ ] **Step 3: Verify rpath is embedded in the binary**

```bash
otool -l app-macos/target/app-macos-1.0.0-SNAPSHOT-runner | grep -A 2 RPATH
```

Expected output includes:
```
          cmd LC_RPATH
      cmdsize ...
         path @executable_path/../Frameworks (offset ...)
```

If `LC_RPATH` is not present, the native-image linker option syntax needs adjustment.

- [ ] **Step 4: Commit**

```bash
git add app-macos/src/main/resources/META-INF/native-image/native-image.properties
git commit -m "build: add @rpath to native binary for .app bundle dylib resolution"
```

---

## Task 2: Auto-detect dylib path in MacUIBridge

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java`

Currently `loadDylib()` reads `-Dcccli.dylib.path` or falls back to a hardcoded relative path. When running from a `.app` bundle, the dylib is at `Contents/Frameworks/libMyMacUI.dylib` relative to the binary at `Contents/MacOS/claude-desktop`. We detect this using `ProcessHandle.current().info().command()` which returns the absolute path to the running executable.

- [ ] **Step 1: Update MacUIBridge.java**

Add `java.nio.file.Files` import (if not present). Replace the `loadDylib()` method and add the `resolveDylibPath()` helper:

```java
import java.nio.file.Files;

// ... (rest of existing imports unchanged)

@PostConstruct
void loadDylib() {
    String pathStr = resolveDylibPath();
    Path path = Path.of(pathStr).toAbsolutePath();
    Log.infof("Loading dylib from: %s", path);
    System.load(path.toString());
    Log.info("libMyMacUI.dylib loaded successfully");
}

/**
 * Resolves the path to libMyMacUI.dylib in priority order:
 *
 * 1. Explicit system property (dev mode: -Dcccli.dylib.path=...)
 * 2. .app bundle path: executable is at Contents/MacOS/<name>,
 *    dylib is at Contents/Frameworks/libMyMacUI.dylib
 * 3. Dev mode default: ../mac-ui-bridge/build/libMyMacUI.dylib
 */
private String resolveDylibPath() {
    // 1. Explicit override
    String prop = System.getProperty(DYLIB_PATH_PROP);
    if (prop != null) return prop;

    // 2. .app bundle detection via current executable path
    String cmd = ProcessHandle.current().info().command().orElse("");
    if (!cmd.isEmpty()) {
        Path exe = Path.of(cmd).toAbsolutePath();
        // exe = .../Contents/MacOS/claude-desktop
        // exe.getParent() = .../Contents/MacOS
        // exe.getParent().getParent() = .../Contents
        if (exe.getParent() != null && exe.getParent().getParent() != null) {
            Path bundleDylib = exe.getParent().getParent()
                    .resolve("Frameworks/libMyMacUI.dylib");
            if (Files.exists(bundleDylib)) {
                Log.infof("Detected .app bundle — using bundle dylib at: %s", bundleDylib);
                return bundleDylib.toString();
            }
        }
    }

    // 3. Dev mode default (relative path from app-macos working dir)
    return DYLIB_PATH_DEFAULT;
}
```

- [ ] **Step 2: Verify JVM dev mode still works**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.0.2-graalce
cd /Users/mdproctor/claude/cccli
mvn install -q
cd app-macos
mvn quarkus:dev -Dcccli.dylib.path="$(pwd)/../mac-ui-bridge/build/libMyMacUI.dylib"
```

Expected: app launches as before, log shows `Loading dylib from: .../libMyMacUI.dylib`. Close the window and Ctrl+C.

- [ ] **Step 3: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java
git commit -m "feat(macos): auto-detect dylib path from .app bundle via ProcessHandle"
```

---

## Task 3: Create Info.plist

**Files:**
- Create: `app-macos/src/main/resources/Info.plist`

The `Info.plist` tells macOS the bundle identifier, display name, executable name, and minimum OS version.

- [ ] **Step 1: Create the file**

Create `app-macos/src/main/resources/Info.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key>
    <string>dev.mproctor.cccli</string>
    <key>CFBundleName</key>
    <string>Claude Desktop CLI</string>
    <key>CFBundleDisplayName</key>
    <string>Claude Desktop CLI</string>
    <key>CFBundleExecutable</key>
    <string>claude-desktop</string>
    <key>CFBundleVersion</key>
    <string>1.0.0</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0.0</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSPrincipalClass</key>
    <string>NSApplication</string>
</dict>
</plist>
```

`CFBundleExecutable` must match the binary name inside `Contents/MacOS/` exactly (`claude-desktop` — the name used in the bundle script). `LSMinimumSystemVersion` matches the clang target `arm64-apple-macos13.0` in the dylib Makefile.

- [ ] **Step 2: Validate the plist is well-formed**

```bash
plutil -lint app-macos/src/main/resources/Info.plist
```

Expected: `app-macos/src/main/resources/Info.plist: OK`

- [ ] **Step 3: Commit**

```bash
git add app-macos/src/main/resources/Info.plist
git commit -m "feat(macos): add Info.plist for .app bundle"
```

---

## Task 4: Create bundle assembly script

**Files:**
- Create: `scripts/bundle.sh`

The script creates the `.app` directory structure, copies the native binary (renaming it to `claude-desktop`) and the dylib, places the `Info.plist`, and ad-hoc signs the bundle.

- [ ] **Step 1: Create the script**

Create `scripts/bundle.sh`:

```bash
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
```

- [ ] **Step 2: Make the script executable**

```bash
chmod +x /Users/mdproctor/claude/cccli/scripts/bundle.sh
```

- [ ] **Step 3: Commit**

```bash
git add scripts/bundle.sh
git commit -m "feat: bundle.sh — assembles and signs .app bundle"
```

---

## Task 5: Wire into Maven + build + validate

**Files:**
- Modify: `app-macos/pom.xml`

Add the bundle script as an automatic step in the `native` profile so `mvn install -pl app-macos -am -Pnative` produces both the native binary and the `.app` bundle.

- [ ] **Step 1: Add bundle execution to the native profile in pom.xml**

In `app-macos/pom.xml`, replace the existing `<profiles>` section with:

```xml
<profiles>
    <profile>
        <id>native</id>
        <properties>
            <quarkus.package.type>native</quarkus.package.type>
        </properties>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.codehaus.mojo</groupId>
                    <artifactId>exec-maven-plugin</artifactId>
                    <version>3.1.0</version>
                    <executions>
                        <execution>
                            <id>bundle-app</id>
                            <phase>install</phase>
                            <goals><goal>exec</goal></goals>
                            <configuration>
                                <executable>${project.basedir}/../scripts/bundle.sh</executable>
                            </configuration>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

The `install` phase runs after `package` (which builds the native binary), so the bundle script always has a fresh binary to work with.

- [ ] **Step 2: Build the bundle**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.0.2-graalce
cd /Users/mdproctor/claude/cccli
mvn install -pl app-macos -am -Pnative -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS` followed by:
```
✅ Bundle created: .../app-macos/target/Claude Desktop CLI.app
```

- [ ] **Step 3: Verify the bundle structure**

```bash
find "app-macos/target/Claude Desktop CLI.app" -type f
```

Expected:
```
app-macos/target/Claude Desktop CLI.app/Contents/Info.plist
app-macos/target/Claude Desktop CLI.app/Contents/MacOS/claude-desktop
app-macos/target/Claude Desktop CLI.app/Contents/Frameworks/libMyMacUI.dylib
```

- [ ] **Step 4: Verify the binary finds the dylib via rpath**

```bash
otool -L "app-macos/target/Claude Desktop CLI.app/Contents/MacOS/claude-desktop" | grep MyMacUI
```

Expected:
```
    @rpath/libMyMacUI.dylib (compatibility version 0.0.0, current version 0.0.0)
```

- [ ] **Step 5: Launch the bundle and test**

```bash
open "app-macos/target/Claude Desktop CLI.app"
```

If macOS blocks it: `xattr -dr com.apple.quarantine "app-macos/target/Claude Desktop CLI.app"`

Manual test:
1. Window opens — no `-Dcccli.dylib.path` needed ✅
2. Log shows `Detected .app bundle — using bundle dylib at: .../Frameworks/libMyMacUI.dylib` ✅
3. Type a prompt → Claude responds → passive mode works ✅
4. Close window → clean exit ✅

- [ ] **Step 6: Commit**

```bash
git add app-macos/pom.xml
git commit -m "feat(macos): wire bundle.sh into native Maven profile — mvn install -Pnative produces .app"
```

---

## Self-Review

### Spec coverage

| Requirement | Task |
|-------------|------|
| `Contents/MacOS/` + `Contents/Frameworks/` structure | Task 4 |
| `libMyMacUI.dylib` in `Contents/Frameworks/` | Task 4 |
| `@rpath` resolves dylib from bundle | Task 1 |
| `Info.plist` with bundle ID and metadata | Task 3 |
| App launches without `-Dcccli.dylib.path` | Tasks 1 + 2 + 4 |
| Ad-hoc codesigning for local use | Task 4 |
| Maven integration | Task 5 |
| Validated manually | Task 5 Step 5 |

Notarization (App Store / distribution) is explicitly out of scope — that's Plan N+1.

### Placeholder scan

No TBD or "implement later". All code blocks are complete.

### Type consistency

- `CFBundleExecutable: "claude-desktop"` in Info.plist matches `BINARY_NAME="claude-desktop"` in bundle.sh ✅
- `@rpath/libMyMacUI.dylib` in dylib's install_name matches `libMyMacUI.dylib` copied to `Frameworks/` ✅
- `ProcessHandle` path navigation: `exe.getParent().getParent().resolve("Frameworks/libMyMacUI.dylib")` — `exe` is `Contents/MacOS/claude-desktop`, result is `Contents/Frameworks/libMyMacUI.dylib` ✅
