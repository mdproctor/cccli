# Bridge Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An NSWindow appears on screen when Quarkus starts, closing it triggers a Java log message via Panama upcall, and the same works as a Quarkus Native binary on Apple Silicon.

**Architecture:** A minimal Objective-C bridge (MyMacUI.dylib) exposes AppKit primitives via a clean C ABI. Java calls it via Panama FFM downcalls. AppKit calls back into Java via Panama upcalls. Quarkus Native (GraalVM) compiles everything to a standalone native binary.

**Tech Stack:** Java 22+, Quarkus 3.15+, GraalVM/Mandrel 25+, Panama FFM API, jextract, Objective-C, AppKit, Cocoa, clang, macOS/AArch64

---

## Why This Phase Exists

Two architectural risks must be validated before building anything else:

1. **Panama upcalls in GraalVM native image** — if ObjC cannot call back into compiled Java code, the entire architecture breaks. Every button click, input submit, and window event depends on this working.
2. **AppKit main thread + Quarkus thread model** — AppKit requires all UI operations on the main thread. Quarkus starts its container on the main thread for native image; `@QuarkusMain.run()` is called synchronously from `main()`. If AppKit detects it is not on the main thread, it will crash with a `pthread_main_np()` assertion. This plan validates the threading model works.

Discover these failures at Task 1, not Task 10.

---

## Prerequisites

Before starting, verify these are installed:

```bash
# Java 22+ with Panama support
java --version          # must be 22+

# GraalVM / Mandrel with native image
native-image --version

# jextract (separate download from https://jdk.java.net/jextract/)
jextract --version

# clang (comes with Xcode Command Line Tools)
clang --version

# Quarkus CLI (optional but useful)
quarkus --version
```

Install jextract if missing:
```bash
# Download from https://jdk.java.net/jextract/ for macOS/AArch64
# Extract and add to PATH, e.g.:
export PATH=$HOME/tools/jextract-22/bin:$PATH
```

---

## File Map

Files created in this plan:

```
claude-desktop/
├── pom.xml                                                    # Parent Maven POM
├── .mvn/
│   └── jvm.config                                            # JVM args for dev mode
├── mac-ui-bridge/
│   ├── Makefile                                              # Builds libMyMacUI.dylib
│   ├── include/
│   │   └── MyMacUI.h                                         # C ABI header (jextract input)
│   └── src/
│       └── MyMacUI.m                                         # Objective-C implementation
├── app-core/
│   └── pom.xml                                               # Core module (empty for now)
└── app-macos/
    ├── pom.xml                                               # Quarkus module
    └── src/
        ├── main/
        │   ├── java/dev/mproctor/cccli/
        │   │   ├── Main.java                                  # @QuarkusMain entry point
        │   │   └── bridge/
        │   │       ├── gen/                                   # jextract output (committed)
        │   │       │   └── MyMacUI_h.java                    # Generated — do not edit
        │   │       ├── Callbacks.java                         # Panama upcall stubs
        │   │       └── MacUIBridge.java                       # Facade over generated bindings
        │   └── resources/
        │       ├── application.properties
        │       └── META-INF/native-image/
        │           ├── native-image.properties                # --enable-native-access flag
        │           └── reflect-config.json                    # Upcall method reflection
        └── test/
            └── java/dev/mproctor/cccli/
                └── bridge/
                    └── MacUIBridgeTest.java                   # Integration smoke test
```

---

## Task 1: Maven Project Skeleton

**Files:**
- Create: `pom.xml`
- Create: `app-core/pom.xml`
- Create: `app-macos/pom.xml`
- Create: `.mvn/jvm.config`

- [ ] **Step 1: Create the parent POM**

```xml
<!-- pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>dev.mproctor.cccli</groupId>
    <artifactId>claude-desktop</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>app-core</module>
        <module>app-macos</module>
    </modules>

    <properties>
        <maven.compiler.source>22</maven.compiler.source>
        <maven.compiler.target>22</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
        <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
        <quarkus.platform.version>3.15.0</quarkus.platform.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>${quarkus.platform.group-id}</groupId>
                <artifactId>${quarkus.platform.artifact-id}</artifactId>
                <version>${quarkus.platform.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 2: Create app-core POM**

```xml
<!-- app-core/pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dev.mproctor.cccli</groupId>
        <artifactId>claude-desktop</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>app-core</artifactId>

    <!-- No dependencies yet — pure Java, no UI -->
</project>
```

- [ ] **Step 3: Create app-macos POM**

```xml
<!-- app-macos/pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dev.mproctor.cccli</groupId>
        <artifactId>claude-desktop</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>app-macos</artifactId>

    <dependencies>
        <dependency>
            <groupId>dev.mproctor.cccli</groupId>
            <artifactId>app-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-core</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit5</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.quarkus</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
                <version>${quarkus.platform.version}</version>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <goals>
                            <goal>build</goal>
                            <goal>generate-code</goal>
                            <goal>generate-code-tests</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <!-- Build the dylib before compiling Java -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
                <executions>
                    <execution>
                        <id>build-dylib</id>
                        <phase>generate-sources</phase>
                        <goals><goal>exec</goal></goals>
                        <configuration>
                            <executable>make</executable>
                            <workingDirectory>${project.parent.basedir}/mac-ui-bridge</workingDirectory>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>native</id>
            <properties>
                <quarkus.package.type>native</quarkus.package.type>
            </properties>
        </profile>
    </profiles>
</project>
```

- [ ] **Step 4: Create JVM args config for dev mode**

Panama FFM requires `--enable-native-access` at the JVM level. This file applies to `mvn quarkus:dev`.

```
# .mvn/jvm.config
--enable-native-access=ALL-UNNAMED
```

- [ ] **Step 5: Create source directories**

```bash
mkdir -p app-core/src/main/java/dev/mproctor/cccli
mkdir -p app-macos/src/main/java/dev/mproctor/cccli/bridge/gen
mkdir -p app-macos/src/main/resources/META-INF/native-image
mkdir -p app-macos/src/test/java/dev/mproctor/cccli/bridge
mkdir -p mac-ui-bridge/include
mkdir -p mac-ui-bridge/src
```

- [ ] **Step 6: Verify Maven resolves modules**

```bash
mvn validate
```

Expected: `BUILD SUCCESS` with both modules listed.

- [ ] **Step 7: Commit**

```bash
git init
git add pom.xml app-core/pom.xml app-macos/pom.xml .mvn/jvm.config
git commit -m "feat: Maven multi-module skeleton (app-core, app-macos)"
```

---

## Task 2: Objective-C Bridge Header

**Files:**
- Create: `mac-ui-bridge/include/MyMacUI.h`

The header defines the complete C ABI for Phase 1. This is what `jextract` will read to generate Java bindings.

- [ ] **Step 1: Write the header**

```c
/* mac-ui-bridge/include/MyMacUI.h */
#ifndef MyMacUI_h
#define MyMacUI_h

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Callback fired on the AppKit main thread when the window is closed.
 */
typedef void (*WindowClosedCallback)(void);

/**
 * Initialize NSApplication. Must be called first, on the main thread.
 */
void myui_init_application(void);

/**
 * Create and show a titled, resizable window centred on screen.
 * Returns an opaque window handle (cast of NSWindow pointer).
 * onClosed is called when the user closes the window.
 */
intptr_t myui_create_window(const char* title,
                             int width,
                             int height,
                             WindowClosedCallback onClosed);

/**
 * Start the AppKit event loop. Blocks until myui_terminate() is called.
 * Must be called on the main thread.
 */
void myui_run(void);

/**
 * Terminate the application cleanly.
 */
void myui_terminate(void);

#ifdef __cplusplus
}
#endif

#endif /* MyMacUI_h */
```

- [ ] **Step 2: Commit**

```bash
git add mac-ui-bridge/include/MyMacUI.h
git commit -m "feat: Objective-C bridge C ABI header (Phase 1 primitives)"
```

---

## Task 3: Objective-C Bridge Implementation

**Files:**
- Create: `mac-ui-bridge/src/MyMacUI.m`

- [ ] **Step 1: Write the implementation**

```objc
/* mac-ui-bridge/src/MyMacUI.m */
#import <Cocoa/Cocoa.h>
#include "MyMacUI.h"

/* ── AppDelegate ─────────────────────────────────────────────────────────── */

@interface CCCAppDelegate : NSObject <NSApplicationDelegate, NSWindowDelegate>
@property (nonatomic, assign) WindowClosedCallback onClosed;
@end

@implementation CCCAppDelegate

- (void)applicationDidFinishLaunching:(NSNotification *)notification {
    /* Nothing needed — window is created via myui_create_window */
}

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return YES;
}

- (void)windowWillClose:(NSNotification *)notification {
    if (self.onClosed) {
        self.onClosed();
    }
}

@end

/* ── Module-level state ───────────────────────────────────────────────────── */

static CCCAppDelegate *appDelegate = nil;

/* ── C ABI implementation ─────────────────────────────────────────────────── */

void myui_init_application(void) {
    [NSApplication sharedApplication];
    appDelegate = [[CCCAppDelegate alloc] init];
    [NSApp setDelegate:appDelegate];
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
}

intptr_t myui_create_window(const char *title,
                             int width,
                             int height,
                             WindowClosedCallback onClosed) {
    NSRect frame = NSMakeRect(0, 0, width, height);
    NSWindowStyleMask style = NSWindowStyleMaskTitled
                            | NSWindowStyleMaskClosable
                            | NSWindowStyleMaskMiniaturizable
                            | NSWindowStyleMaskResizable;

    NSWindow *window = [[NSWindow alloc] initWithContentRect:frame
                                                   styleMask:style
                                                     backing:NSBackingStoreBuffered
                                                       defer:NO];

    NSString *titleStr = [NSString stringWithUTF8String:(title ? title : "")];
    [window setTitle:titleStr];
    [window center];
    [window setDelegate:appDelegate];
    [window makeKeyAndOrderFront:nil];

    appDelegate.onClosed = onClosed;

    [NSApp activateIgnoringOtherApps:YES];

    return (intptr_t)(__bridge void *)window;
}

void myui_run(void) {
    [NSApp run];
}

void myui_terminate(void) {
    [NSApp terminate:nil];
}
```

- [ ] **Step 2: Commit**

```bash
git add mac-ui-bridge/src/MyMacUI.m
git commit -m "feat: Objective-C bridge implementation (NSWindow + close callback)"
```

---

## Task 4: Build the Dylib

**Files:**
- Create: `mac-ui-bridge/Makefile`

- [ ] **Step 1: Write the Makefile**

```makefile
# mac-ui-bridge/Makefile
DYLIB     = libMyMacUI.dylib
BUILD_DIR = build
SRC       = src/MyMacUI.m
INCLUDE   = include

.PHONY: all clean

all: $(BUILD_DIR)/$(DYLIB)

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

$(BUILD_DIR)/$(DYLIB): $(SRC) $(INCLUDE)/MyMacUI.h | $(BUILD_DIR)
	clang -dynamiclib \
	      -fobjc-arc \
	      -framework Cocoa \
	      -I$(INCLUDE) \
	      -install_name @rpath/$(DYLIB) \
	      -target arm64-apple-macos13.0 \
	      -o $@ $<

clean:
	rm -rf $(BUILD_DIR)
```

- [ ] **Step 2: Build the dylib**

```bash
cd mac-ui-bridge && make
```

Expected output:
```
clang -dynamiclib -fobjc-arc -framework Cocoa -Iinclude \
      -install_name @rpath/libMyMacUI.dylib \
      -target arm64-apple-macos13.0 \
      -o build/libMyMacUI.dylib src/MyMacUI.m
```

- [ ] **Step 3: Verify the dylib**

```bash
file mac-ui-bridge/build/libMyMacUI.dylib
```

Expected: `Mach-O 64-bit dynamically linked shared library arm64`

```bash
nm -g mac-ui-bridge/build/libMyMacUI.dylib | grep myui_
```

Expected: four exported symbols — `myui_init_application`, `myui_create_window`, `myui_run`, `myui_terminate`.

- [ ] **Step 4: Add build output to .gitignore**

```bash
echo "mac-ui-bridge/build/" >> .gitignore
```

- [ ] **Step 5: Commit**

```bash
git add mac-ui-bridge/Makefile .gitignore
git commit -m "feat: Makefile builds libMyMacUI.dylib via clang"
```

---

## Task 5: Generate Panama Bindings with jextract

**Files:**
- Create: `app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java` (generated)

The generated file is committed so the project builds without requiring jextract on every machine. Re-run this command only when `MyMacUI.h` changes.

- [ ] **Step 1: Run jextract**

```bash
jextract \
  --output app-macos/src/main/java \
  --target-package dev.mproctor.cccli.bridge.gen \
  mac-ui-bridge/include/MyMacUI.h
```

Expected: creates `app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java`

- [ ] **Step 2: Inspect the generated file**

```bash
head -60 app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java
```

Verify it contains method handles for `myui_init_application`, `myui_create_window`, `myui_run`, `myui_terminate`.

- [ ] **Step 3: Commit the generated bindings**

```bash
git add app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/
git commit -m "feat: Panama FFM bindings generated from MyMacUI.h via jextract"
```

---

## Task 6: Panama Upcall — Window Close Callback

**Files:**
- Create: `app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java`

An upcall stub is a native function pointer that, when called from C/ObjC, executes a Java method. This is the most critical piece to validate — if upcalls don't work in native image, the architecture breaks.

- [ ] **Step 1: Write the Callbacks class**

```java
// app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java
package dev.mproctor.cccli.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Creates Panama upcall stubs — native function pointers that call back into Java.
 *
 * Each stub must be kept alive for as long as the native code may call it.
 * Pass the owning Arena to createWindowClosedCallback() to control lifetime.
 */
public final class Callbacks {

    private static final FunctionDescriptor VOID_VOID =
            FunctionDescriptor.ofVoid();

    // Static holder — simple for Phase 1; replace with instance approach later
    private static volatile Runnable windowClosedHandler;

    /**
     * Creates a native function pointer (C: void (*)(void)) that calls handler
     * when the window is closed by the user.
     *
     * @param arena   controls the lifetime of the returned stub; must outlive the window
     * @param handler called on the AppKit main thread when the window closes
     * @return a MemorySegment representing the native function pointer
     */
    public static MemorySegment createWindowClosedCallback(Arena arena, Runnable handler) {
        windowClosedHandler = handler;
        try {
            MethodHandle mh = MethodHandles.lookup()
                    .findStatic(Callbacks.class, "onWindowClosed",
                            MethodType.methodType(void.class));
            return Linker.nativeLinker().upcallStub(mh, VOID_VOID, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to create window-closed upcall stub", e);
        }
    }

    /**
     * Called from Objective-C on the AppKit main thread when the window closes.
     * Must be public and static so Panama can locate it via reflection in native image.
     */
    public static void onWindowClosed() {
        Runnable handler = windowClosedHandler;
        if (handler != null) {
            handler.run();
        }
    }

    private Callbacks() {}
}
```

- [ ] **Step 2: Commit**

```bash
git add app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java
git commit -m "feat: Panama upcall stub for window-close callback"
```

---

## Task 7: MacUIBridge Facade

**Files:**
- Create: `app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java`

- [ ] **Step 1: Write the bridge facade**

```java
// app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java
package dev.mproctor.cccli.bridge;

import dev.mproctor.cccli.bridge.gen.MyMacUI_h;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * Thin Java facade over the Objective-C MyMacUI bridge.
 *
 * Loads libMyMacUI.dylib at startup. The dylib path is resolved from
 * the system property "cccli.dylib.path" (default: relative to working dir).
 *
 * All methods that call AppKit must be invoked on the main thread.
 */
@ApplicationScoped
public class MacUIBridge {

    private static final String DYLIB_PATH_PROP = "cccli.dylib.path";
    private static final String DYLIB_PATH_DEFAULT = "../mac-ui-bridge/build/libMyMacUI.dylib";

    // Arena lives for the application lifetime — keeps dylib loaded and upcall stubs alive
    private final Arena arena = Arena.ofAuto();

    @PostConstruct
    void loadDylib() {
        String pathStr = System.getProperty(DYLIB_PATH_PROP, DYLIB_PATH_DEFAULT);
        Path path = Path.of(pathStr).toAbsolutePath();
        Log.infof("Loading dylib from: %s", path);
        System.load(path.toString());
        Log.info("libMyMacUI.dylib loaded successfully");
    }

    /**
     * Initialize NSApplication. Call once before any other method, on the main thread.
     */
    public void initApplication() {
        MyMacUI_h.myui_init_application();
    }

    /**
     * Create and show a window. Returns an opaque window handle.
     *
     * @param title    window title bar text
     * @param width    initial width in points
     * @param height   initial height in points
     * @param onClosed called when the user closes the window (on AppKit main thread)
     * @return opaque window handle
     */
    public long createWindow(String title, int width, int height, Runnable onClosed) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment titleSeg = temp.allocateFrom(title);
            MemorySegment closedCallback = Callbacks.createWindowClosedCallback(arena, onClosed);
            return MyMacUI_h.myui_create_window(titleSeg, width, height, closedCallback);
        }
    }

    /**
     * Start the AppKit event loop. Blocks until terminate() is called.
     * Must be called on the main thread.
     */
    public void run() {
        MyMacUI_h.myui_run();
    }

    /**
     * Terminate the application cleanly.
     */
    public void terminate() {
        MyMacUI_h.myui_terminate();
    }

    @PreDestroy
    void close() {
        arena.close();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java
git commit -m "feat: MacUIBridge facade — loads dylib, wraps Panama downcalls and upcall"
```

---

## Task 8: Quarkus Entry Point

**Files:**
- Create: `app-macos/src/main/java/dev/mproctor/cccli/Main.java`
- Create: `app-macos/src/main/resources/application.properties`

- [ ] **Step 1: Write the QuarkusMain**

```java
// app-macos/src/main/java/dev/mproctor/cccli/Main.java
package dev.mproctor.cccli;

import dev.mproctor.cccli.bridge.MacUIBridge;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/**
 * Application entry point.
 *
 * run() is called on the main thread in both JVM and native image mode.
 * AppKit requires the main thread for all UI operations, including [NSApp run].
 *
 * RISK: if Quarkus calls run() off the main thread, AppKit will crash with
 *       a pthread_main_np() assertion. Validate this at Task 9 Step 2.
 */
@QuarkusMain
public class Main implements QuarkusApplication {

    @Inject
    MacUIBridge bridge;

    public static void main(String... args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) {
        Log.info("Initialising AppKit...");
        bridge.initApplication();

        Log.info("Creating main window...");
        bridge.createWindow("Claude Desktop CLI", 900, 600, () -> {
            // Called from AppKit main thread when window closes
            Log.info("Window closed via upcall — terminating");
            bridge.terminate();
        });

        Log.info("Starting AppKit event loop (blocks main thread)...");
        bridge.run();

        Log.info("AppKit event loop ended");
        return 0;
    }
}
```

- [ ] **Step 2: Write application.properties**

```properties
# app-macos/src/main/resources/application.properties

# Dylib path relative to app-macos/ working directory in dev mode
# Override with -Dcccli.dylib.path=/absolute/path/to/libMyMacUI.dylib
cccli.dylib.path=../mac-ui-bridge/build/libMyMacUI.dylib

quarkus.log.level=INFO
quarkus.log.console.format=%d{HH:mm:ss} %-5p %s%e%n
```

- [ ] **Step 3: Commit**

```bash
git add app-macos/src/main/java/dev/mproctor/cccli/Main.java
git add app-macos/src/main/resources/application.properties
git commit -m "feat: @QuarkusMain entry point — init AppKit, create window, run event loop"
```

---

## Task 9: Validate in JVM Mode

This is the first real integration test. A window should appear on screen.

- [ ] **Step 1: Build the dylib**

```bash
cd mac-ui-bridge && make && cd ..
```

Expected: `build/libMyMacUI.dylib` exists.

- [ ] **Step 2: Run in dev mode**

```bash
cd app-macos && mvn quarkus:dev
```

Expected:
```
HH:mm:ss INFO  Initialising AppKit...
HH:mm:ss INFO  Loading dylib from: /absolute/path/to/libMyMacUI.dylib
HH:mm:ss INFO  libMyMacUI.dylib loaded successfully
HH:mm:ss INFO  Creating main window...
HH:mm:ss INFO  Starting AppKit event loop (blocks main thread)...
```

A titled window "Claude Desktop CLI" (900×600, centred) appears on screen.

- [ ] **Step 3: Validate the upcall**

Close the window by clicking the red traffic light.

Expected log output:
```
HH:mm:ss INFO  Window closed via upcall — terminating
HH:mm:ss INFO  AppKit event loop ended
```

Process exits cleanly.

**If Step 2 crashes with `pthread_main_np()` assertion:** Quarkus is not calling `run()` on the main thread. Fix by replacing `bridge.run()` in Main.java with a dispatch to the main queue:

```java
// Alternative if main thread assertion fires — add to MyMacUI.h first:
//   void myui_dispatch_to_main(void (*block)(void));
// Then in MyMacUI.m:
//   void myui_dispatch_to_main(void (*block)(void)) {
//       dispatch_async(dispatch_get_main_queue(), ^{ block(); });
//       CFRunLoopRun();
//   }
// Then call myui_dispatch_to_main instead of myui_run.
// Document this as a pivot in DECISIONS.md if it occurs.
```

- [ ] **Step 4: Commit**

```bash
# No code changes if it worked — commit the validation result as a note
git commit --allow-empty -m "validated: JVM mode window + upcall working"
```

---

## Task 10: Native Image Configuration

**Files:**
- Create: `app-macos/src/main/resources/META-INF/native-image/native-image.properties`
- Create: `app-macos/src/main/resources/META-INF/native-image/reflect-config.json`

Panama upcalls require the callback method to be accessible in the native image. GraalVM's static analyser cannot see through the `MethodHandles.lookup().findStatic(...)` call in Callbacks.java, so we register it explicitly.

- [ ] **Step 1: Write native-image.properties**

```properties
# app-macos/src/main/resources/META-INF/native-image/native-image.properties
Args = --enable-native-access=ALL-UNNAMED \
       -H:+ReportExceptionStackTraces
```

- [ ] **Step 2: Write reflect-config.json**

```json
[
  {
    "name": "dev.mproctor.cccli.bridge.Callbacks",
    "methods": [
      { "name": "onWindowClosed", "parameterTypes": [] }
    ]
  }
]
```

- [ ] **Step 3: Commit**

```bash
git add app-macos/src/main/resources/META-INF/native-image/
git commit -m "feat: native image config — enable-native-access + reflect upcall method"
```

---

## Task 11: Validate as Quarkus Native Binary

- [ ] **Step 1: Build the native image**

```bash
cd app-macos && mvn package -Pnative
```

This takes several minutes. Expected: `app-macos/target/app-macos-runner` binary exists.

```bash
file app-macos/target/app-macos-runner
```

Expected: `Mach-O 64-bit executable arm64`

- [ ] **Step 2: Run the native binary**

```bash
./app-macos/target/app-macos-runner \
  -Dcccli.dylib.path=$(pwd)/mac-ui-bridge/build/libMyMacUI.dylib
```

Expected: same behaviour as JVM mode — window appears, close triggers upcall log, process exits.

**If the upcall fires but Java code is not reached:** The reflect-config.json is not registering the method correctly. Check for GraalVM output in the build log for missing reflective access warnings and add entries as needed.

**If the window appears but closing silently exits without logging:** The upcall stub is being garbage collected. Verify `arena` in MacUIBridge is an application-scoped field (not a local variable) and that `@PreDestroy` closes it after the event loop ends.

- [ ] **Step 3: Record the validated state**

Update `DECISIONS.md` — add to ADR-003 or as a new note:
```markdown
**Validated 2026-04-03:** Panama FFM upcalls confirmed working in GraalVM native image on macOS/AArch64.
Main thread model confirmed: @QuarkusMain.run() is on the OS main thread in native image.
```

- [ ] **Step 4: Commit**

```bash
git add DECISIONS.md
git commit -m "validated: Quarkus Native — window + upcall working on AArch64"
```

---

## Self-Review

**Spec coverage:**
- ✅ Maven multi-module skeleton (parent, app-core, app-macos)
- ✅ mac-ui-bridge Objective-C directory with Makefile
- ✅ Panama downcalls (myui_init_application, myui_create_window, myui_run, myui_terminate)
- ✅ Panama upcall (window close callback into Java)
- ✅ JVM mode validation
- ✅ Quarkus Native validation on AArch64
- ✅ Main thread risk identified with explicit fix recipe if it fires
- ✅ dylib loading via System.load() with configurable path

**What this plan does NOT cover (future plans):**
- WKWebView + xterm.js terminal pane
- NSTextView input pane
- NSSplitView layout
- PTY management (Panama FFM POSIX calls)
- Claude subprocess integration
- Interaction detection state machine
- .app bundle packaging and code signing

**Placeholder scan:** None. All steps have complete code and exact commands.

**Type consistency:** `Callbacks.onWindowClosed()` (Task 6) → referenced in `reflect-config.json` (Task 10) ✅. `MacUIBridge.bridge` field in Main.java matches the `@ApplicationScoped MacUIBridge` class ✅. `MyMacUI_h` generated class referenced in MacUIBridge matches jextract output package `dev.mproctor.cccli.bridge.gen` ✅.
