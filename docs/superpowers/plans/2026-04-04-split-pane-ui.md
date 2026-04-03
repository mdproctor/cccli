# Split Pane UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the bare NSWindow with a split pane containing a WKWebView terminal pane (top) and NSTextView input pane (bottom), wired end-to-end so text submitted in the input pane echoes back to the terminal pane via Java.

**Architecture:** The Objective-C bridge gains NSSplitView, WKWebView, and NSTextView primitives. A new `TextSubmittedCallback` upcall fires when the user presses Enter. `myui_start()` is extended to accept initial HTML and the text callback — all UI setup happens inside the existing GCD dispatch block. Java calls `bridge.evaluateJavaScript()` to write to the terminal pane.

**Tech Stack:** Objective-C, AppKit (NSSplitView, NSTextView), WebKit (WKWebView), Panama FFM API, jextract, Quarkus Native, GraalVM 25

---

## Why no xterm.js yet

Plan 2 validates the WKWebView ↔ Java JavaScript bridge and the NSTextView ↔ Java upcall. xterm.js (full VT100 terminal) replaces the basic HTML in Plan 3 when PTY output is wired in. There is no point loading a terminal emulator before we have terminal output.

---

## Threading notes (carry-forward from Plan 1)

- In **native image**, `@QuarkusMain.run()` is on the OS main thread. `myui_start()` uses `CFRunLoopRun()`.
- In **JVM dev mode**, `run()` is on a worker thread. `myui_start()` uses `dispatch_async` + semaphore.
- `myui_evaluate_javascript()` and `myui_load_html()` always `dispatch_async` to the main thread — safe to call from any thread.
- The `TextSubmittedCallback` upcall fires on the AppKit main thread. Java can safely call `bridge.evaluateJavaScript()` from inside it (the dispatch is queued for after the callback returns).

---

## File Map

```
mac-ui-bridge/
├── include/MyMacUI.h          MODIFY — add TextSubmittedCallback, myui_load_html,
│                                        myui_evaluate_javascript; extend myui_start
└── src/MyMacUI.m              MODIFY — full rewrite of UI setup; NSSplitView,
                                        WKWebView, NSTextView, Enter key delegate

app-macos/src/main/
├── java/dev/mproctor/cccli/
│   ├── Main.java              MODIFY — pass initialHtml + text handler to bridge.start()
│   └── bridge/
│       ├── Callbacks.java     MODIFY — add TextSubmittedCallback upcall
│       ├── MacUIBridge.java   MODIFY — extend start(), add loadHtml(), evaluateJavaScript()
│       └── gen/               REGENERATE — run jextract after header changes
└── resources/META-INF/native-image/dev.mproctor.cccli/app-macos/
    └── reachability-metadata.json  MODIFY — new upcall + downcall descriptors
```

---

## Task 1: Update MyMacUI.h

**Files:**
- Modify: `mac-ui-bridge/include/MyMacUI.h`

- [ ] **Step 1: Replace the header with the updated version**

```c
/* mac-ui-bridge/include/MyMacUI.h */
#ifndef MyMacUI_h
#define MyMacUI_h

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Fired on the AppKit main thread when the window is closed. */
typedef void (*WindowClosedCallback)(void);

/** Fired on the AppKit main thread when the user presses Enter in the input pane.
 *  text is a null-terminated UTF-8 string; valid only for the duration of the call. */
typedef void (*TextSubmittedCallback)(const char* text);

/** Initialize NSApplication. Must be called first, on the main thread. */
void myui_init_application(void);

/** Create and show a titled, resizable window. Returns an opaque window handle. */
intptr_t myui_create_window(const char* title,
                             int width,
                             int height,
                             WindowClosedCallback onClosed);

/** Start the AppKit event loop. Blocks until myui_terminate() is called. */
void myui_run(void);

/** Terminate the application cleanly. */
void myui_terminate(void);

/**
 * Full entry point. Dispatches to the main thread, creates the window with a
 * split pane (WKWebView terminal top, NSTextView input bottom), loads initialHtml
 * into the terminal pane, then starts the AppKit event loop.
 *
 * Blocks the calling thread until the application terminates.
 * Safe to call from any thread.
 *
 * initialHtml  — HTML string loaded into the terminal WKWebView at startup.
 * onClosed     — called when the user closes the window.
 * onTextSubmitted — called when the user presses Enter in the input pane.
 */
intptr_t myui_start(const char* title,
                    int width,
                    int height,
                    const char* initialHtml,
                    WindowClosedCallback onClosed,
                    TextSubmittedCallback onTextSubmitted);

/**
 * Load HTML into the terminal WKWebView. Dispatches to the main thread.
 * Safe to call from any thread after myui_start() has returned from setup.
 */
void myui_load_html(const char* html);

/**
 * Evaluate a JavaScript string in the terminal WKWebView. Dispatches to
 * the main thread. Safe to call from any thread, including upcall handlers.
 */
void myui_evaluate_javascript(const char* script);

#ifdef __cplusplus
}
#endif

#endif /* MyMacUI_h */
```

- [ ] **Step 2: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add mac-ui-bridge/include/MyMacUI.h
git commit -m "feat: add TextSubmittedCallback, myui_load_html, myui_evaluate_javascript to bridge header"
```

---

## Task 2: Implement split pane in MyMacUI.m

**Files:**
- Modify: `mac-ui-bridge/src/MyMacUI.m`

- [ ] **Step 1: Replace MyMacUI.m with the full updated implementation**

```objc
/* mac-ui-bridge/src/MyMacUI.m */
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#include <string.h>
#include "MyMacUI.h"

/* ── AppDelegate ─────────────────────────────────────────────────────────── */

@interface CCCAppDelegate : NSObject <NSApplicationDelegate,
                                      NSWindowDelegate,
                                      NSTextViewDelegate>
@property (nonatomic, assign) WindowClosedCallback onClosed;
@property (nonatomic, assign) TextSubmittedCallback onTextSubmitted;
@end

@implementation CCCAppDelegate

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return YES;
}

- (void)windowWillClose:(NSNotification *)notification {
    if (self.onClosed) {
        WindowClosedCallback cb = self.onClosed;
        self.onClosed = NULL; /* clear before invoking — prevents double-fire */
        cb();
    }
}

/* NSTextViewDelegate — intercept Enter key in the input pane */
- (BOOL)textView:(NSTextView *)textView doCommandBySelector:(SEL)commandSelector {
    if (commandSelector == @selector(insertNewline:)) {
        NSString *raw = textView.string;
        NSString *text = [raw stringByTrimmingCharactersInSet:
                          NSCharacterSet.whitespaceCharacterSet];
        if (text.length > 0 && self.onTextSubmitted) {
            self.onTextSubmitted(text.UTF8String);
        }
        [textView setString:@""];
        return YES; /* handled — do not insert a newline */
    }
    return NO;
}

@end

/* ── Module-level state ───────────────────────────────────────────────────── */

static CCCAppDelegate *appDelegate = nil;
static WKWebView      *theWebView  = nil;

/* ── Internal helpers ─────────────────────────────────────────────────────── */

static void setupSplitPane(NSWindow *window,
                            const char *initialHtml,
                            TextSubmittedCallback onTextSubmitted) {
    CGFloat w = window.contentView.bounds.size.width;
    CGFloat h = window.contentView.bounds.size.height;

    /* ── Terminal pane (top): WKWebView ──────────────────────────────────── */
    WKWebViewConfiguration *wkConfig = [[WKWebViewConfiguration alloc] init];
    WKWebView *webView = [[WKWebView alloc]
        initWithFrame:NSMakeRect(0, 0, w, h * 0.72)
        configuration:wkConfig];
    webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    theWebView = webView;

    /* ── Input pane (bottom): NSScrollView + NSTextView ─────────────────── */
    NSScrollView *inputScroll = [[NSScrollView alloc]
        initWithFrame:NSMakeRect(0, 0, w, h * 0.28)];
    inputScroll.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    inputScroll.hasVerticalScroller = YES;
    inputScroll.hasHorizontalScroller = NO;
    inputScroll.drawsBackground = YES;
    inputScroll.backgroundColor = [NSColor colorWithRed:0.12 green:0.12
                                                   blue:0.12 alpha:1.0];

    NSTextView *inputText = [[NSTextView alloc]
        initWithFrame:inputScroll.bounds];
    inputText.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    inputText.richText = NO;
    inputText.font = [NSFont monospacedSystemFontOfSize:13
                                                 weight:NSFontWeightRegular];
    inputText.textColor = [NSColor colorWithRed:0.84 green:0.84
                                           blue:0.84 alpha:1.0];
    inputText.backgroundColor = [NSColor colorWithRed:0.12 green:0.12
                                                 blue:0.12 alpha:1.0];
    inputText.insertionPointColor = [NSColor colorWithRed:0.84 green:0.84
                                                     blue:0.84 alpha:1.0];
    inputText.automaticSpellingCorrectionEnabled = NO;
    inputText.automaticQuoteSubstitutionEnabled = NO;
    inputText.automaticDashSubstitutionEnabled = NO;
    inputText.delegate = appDelegate;
    inputScroll.documentView = inputText;

    /* ── Split view ──────────────────────────────────────────────────────── */
    NSSplitView *split = [[NSSplitView alloc]
        initWithFrame:window.contentView.bounds];
    split.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    split.vertical = NO; /* horizontal divider — top/bottom split */
    split.dividerStyle = NSSplitViewDividerStyleThin;

    [split addSubview:webView];       /* top */
    [split addSubview:inputScroll];   /* bottom */
    window.contentView = split;

    [split setPosition:h * 0.72 ofDividerAtIndex:0];

    /* ── Load initial HTML ───────────────────────────────────────────────── */
    if (initialHtml) {
        NSString *htmlStr = [NSString stringWithUTF8String:initialHtml];
        [theWebView loadHTMLString:htmlStr baseURL:nil];
    }

    appDelegate.onTextSubmitted = onTextSubmitted;
}

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

void myui_run(void) { [NSApp run]; }

void myui_terminate(void) { [NSApp terminate:nil]; }

void myui_load_html(const char *html) {
    if (!html) return;
    char *copy = strdup(html);
    dispatch_async(dispatch_get_main_queue(), ^{
        NSString *htmlStr = [NSString stringWithUTF8String:copy];
        [theWebView loadHTMLString:htmlStr baseURL:nil];
        free(copy);
    });
}

void myui_evaluate_javascript(const char *script) {
    if (!script) return;
    char *copy = strdup(script);
    dispatch_async(dispatch_get_main_queue(), ^{
        NSString *scriptStr = [NSString stringWithUTF8String:copy];
        [theWebView evaluateJavaScript:scriptStr completionHandler:nil];
        free(copy);
    });
}

intptr_t myui_start(const char *title,
                    int width,
                    int height,
                    const char *initialHtml,
                    WindowClosedCallback onClosed,
                    TextSubmittedCallback onTextSubmitted) {

    __block intptr_t windowHandle = 0;
    char *titleCopy   = strdup(title       ? title       : "");
    char *htmlCopy    = strdup(initialHtml ? initialHtml : "");

    if ([NSThread isMainThread]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            myui_init_application();
            windowHandle = myui_create_window(titleCopy, width, height, onClosed);
            free(titleCopy);
            NSWindow *window = (__bridge NSWindow *)(void *)windowHandle;
            setupSplitPane(window, htmlCopy, onTextSubmitted);
            free(htmlCopy);
            [NSApp run];
            CFRunLoopStop(CFRunLoopGetCurrent());
        });
        CFRunLoopRun();
    } else {
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        dispatch_async(dispatch_get_main_queue(), ^{
            myui_init_application();
            windowHandle = myui_create_window(titleCopy, width, height, onClosed);
            free(titleCopy);
            NSWindow *window = (__bridge NSWindow *)(void *)windowHandle;
            setupSplitPane(window, htmlCopy, onTextSubmitted);
            free(htmlCopy);
            [NSApp run];
            dispatch_semaphore_signal(done);
        });
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    }

    return windowHandle;
}
```

- [ ] **Step 2: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add mac-ui-bridge/src/MyMacUI.m
git commit -m "feat: split pane UI — NSSplitView + WKWebView + NSTextView + Enter key callback"
```

---

## Task 3: Update Makefile and build dylib

**Files:**
- Modify: `mac-ui-bridge/Makefile`

- [ ] **Step 1: Add WebKit framework to the Makefile**

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
	      -framework WebKit \
	      -I$(INCLUDE) \
	      -install_name @rpath/$(DYLIB) \
	      -target arm64-apple-macos13.0 \
	      -o $@ $<

clean:
	rm -rf $(BUILD_DIR)
```

- [ ] **Step 2: Build the dylib**

```bash
cd /Users/mdproctor/claude/cccli/mac-ui-bridge && make clean && make
```

Expected: compiles without errors.

- [ ] **Step 3: Verify new symbols are exported**

```bash
nm -g /Users/mdproctor/claude/cccli/mac-ui-bridge/build/libMyMacUI.dylib | grep myui_
```

Expected — 7 symbols:
```
_myui_create_window
_myui_evaluate_javascript
_myui_init_application
_myui_load_html
_myui_run
_myui_start
_myui_terminate
```

- [ ] **Step 4: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add mac-ui-bridge/Makefile
git commit -m "feat: add WebKit framework to dylib build"
```

---

## Task 4: Regenerate Panama bindings

**Files:**
- Regenerate: `app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/`

**Environment required:**
```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
export PATH="$HOME/tools/jextract-22/bin:$PATH"
```

- [ ] **Step 1: Delete old generated files and regenerate**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
export PATH="$HOME/tools/jextract-22/bin:$PATH"
cd /Users/mdproctor/claude/cccli
rm -rf app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/
jextract \
  --output app-macos/src/main/java \
  --target-package dev.mproctor.cccli.bridge.gen \
  mac-ui-bridge/include/MyMacUI.h
```

- [ ] **Step 2: Verify new bindings**

```bash
grep -l "myui_evaluate_javascript\|myui_load_html\|TextSubmittedCallback" \
  app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/*.java
```

Expected: `MyMacUI_h.java` and `TextSubmittedCallback.java` listed.

```bash
grep "public static" app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java \
  | grep -E "myui_start|myui_load_html|myui_evaluate"
```

Expected: methods for `myui_start`, `myui_load_html`, `myui_evaluate_javascript` present.

Note the exact method signature for `myui_start` — it should now take 6 parameters (title, width, height, initialHtml, onClosed, onTextSubmitted). Record the parameter order from the generated code; it must match the header.

- [ ] **Step 3: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/
git commit -m "feat: regenerate Panama bindings — new split pane API"
```

---

## Task 5: Update Callbacks.java

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java`

- [ ] **Step 1: Replace Callbacks.java**

```java
package dev.mproctor.cccli.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;

/**
 * Creates Panama upcall stubs via direct static MethodHandle lookup.
 *
 * Uses findStatic() on our own class — reliable in GraalVM native image.
 * All handler methods must be registered in reachability-metadata.json.
 */
public final class Callbacks {

    private static final FunctionDescriptor VOID_VOID =
            FunctionDescriptor.ofVoid();
    private static final FunctionDescriptor VOID_PTR =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

    private static volatile Runnable         windowClosedHandler;
    private static volatile Consumer<String> textSubmittedHandler;

    /** Creates a void(*)(void) upcall stub that calls handler when the window closes. */
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

    /** Creates a void(*)(const char*) upcall stub that calls handler with submitted text. */
    public static MemorySegment createTextSubmittedCallback(Arena arena,
                                                             Consumer<String> handler) {
        textSubmittedHandler = handler;
        try {
            MethodHandle mh = MethodHandles.lookup()
                    .findStatic(Callbacks.class, "onTextSubmitted",
                            MethodType.methodType(void.class, MemorySegment.class));
            return Linker.nativeLinker().upcallStub(mh, VOID_PTR, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to create text-submitted upcall stub", e);
        }
    }

    /** Called from Objective-C when the window closes. Registered in reflect-config.json. */
    public static void onWindowClosed() {
        Runnable handler = windowClosedHandler;
        if (handler != null) handler.run();
    }

    /**
     * Called from Objective-C when the user presses Enter.
     * textPtr is a pointer to a null-terminated UTF-8 C string.
     * Registered in reachability-metadata.json for native image.
     */
    public static void onTextSubmitted(MemorySegment textPtr) {
        Consumer<String> handler = textSubmittedHandler;
        if (handler != null && textPtr != null
                && !MemorySegment.NULL.equals(textPtr)) {
            String text = textPtr.reinterpret(Long.MAX_VALUE).getString(0);
            handler.accept(text);
        }
    }

    private Callbacks() {}
}
```

- [ ] **Step 2: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java
git commit -m "feat: add TextSubmittedCallback upcall to Callbacks"
```

---

## Task 6: Update MacUIBridge.java

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java`

- [ ] **Step 1: Replace MacUIBridge.java**

```java
package dev.mproctor.cccli.bridge;

import dev.mproctor.cccli.bridge.gen.MyMacUI_h;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Thin Java facade over the Objective-C MyMacUI bridge.
 *
 * Loads libMyMacUI.dylib at startup. All AppKit threading is handled
 * internally by the dylib — callers need not worry about thread affinity.
 */
@ApplicationScoped
public class MacUIBridge {

    private static final String DYLIB_PATH_PROP    = "cccli.dylib.path";
    private static final String DYLIB_PATH_DEFAULT =
            "../mac-ui-bridge/build/libMyMacUI.dylib";

    private final Arena arena = Arena.ofShared();

    @PostConstruct
    void loadDylib() {
        String pathStr = System.getProperty(DYLIB_PATH_PROP, DYLIB_PATH_DEFAULT);
        Path path = Path.of(pathStr).toAbsolutePath();
        Log.infof("Loading dylib from: %s", path);
        System.load(path.toString());
        Log.info("libMyMacUI.dylib loaded successfully");
    }

    /**
     * Launch the application window with a split pane UI.
     * Blocks until the user closes the window or terminate() is called.
     *
     * @param title           window title bar text
     * @param width           initial window width in points
     * @param height          initial window height in points
     * @param initialHtml     HTML loaded into the terminal WKWebView at startup
     * @param onClosed        called when the user closes the window
     * @param onTextSubmitted called when the user presses Enter in the input pane
     */
    public long start(String title, int width, int height,
                      String initialHtml,
                      Runnable onClosed,
                      Consumer<String> onTextSubmitted) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment titleSeg    = temp.allocateFrom(title);
            MemorySegment htmlSeg     = temp.allocateFrom(initialHtml != null
                                                          ? initialHtml : "");
            MemorySegment closedCb    = Callbacks.createWindowClosedCallback(arena, onClosed);
            MemorySegment submittedCb = Callbacks.createTextSubmittedCallback(arena, onTextSubmitted);
            return MyMacUI_h.myui_start(titleSeg, width, height,
                                        htmlSeg, closedCb, submittedCb);
        }
    }

    /**
     * Load HTML into the terminal pane. Dispatches to the main thread internally.
     * Safe to call from any thread.
     */
    public void loadHtml(String html) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment htmlSeg = temp.allocateFrom(html != null ? html : "");
            MyMacUI_h.myui_load_html(htmlSeg);
        }
    }

    /**
     * Evaluate JavaScript in the terminal pane. Dispatches to the main thread.
     * Safe to call from any thread, including upcall handlers.
     */
    public void evaluateJavaScript(String script) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment scriptSeg = temp.allocateFrom(script != null ? script : "");
            MyMacUI_h.myui_evaluate_javascript(scriptSeg);
        }
    }

    /** Terminate the application cleanly. */
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
cd /Users/mdproctor/claude/cccli
git add app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java
git commit -m "feat: MacUIBridge — extend start() with html/text params, add loadHtml/evaluateJavaScript"
```

---

## Task 7: Update reachability-metadata.json

**Files:**
- Modify: `app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json`

- [ ] **Step 1: Replace reachability-metadata.json**

```json
{
  "foreign": {
    "directUpcalls": [
      {
        "class": "dev.mproctor.cccli.bridge.Callbacks",
        "method": "onWindowClosed",
        "returnType": "void",
        "parameterTypes": []
      },
      {
        "class": "dev.mproctor.cccli.bridge.Callbacks",
        "method": "onTextSubmitted",
        "returnType": "void",
        "parameterTypes": ["void*"]
      }
    ],
    "downcalls": [
      {
        "returnType": "jlong",
        "parameterTypes": ["void*", "jint", "jint", "void*", "void*", "void*"]
      },
      {
        "returnType": "void",
        "parameterTypes": ["void*"]
      },
      {
        "returnType": "void",
        "parameterTypes": []
      }
    ]
  }
}
```

The three downcall descriptors cover:
- `myui_start(char*, int, int, char*, fn*, fn*)` → `jlong` — the 6-param entry point
- `myui_load_html(char*)` / `myui_evaluate_javascript(char*)` / `myui_terminate` wait — actually `myui_terminate` has no params, and `myui_load_html`/`myui_evaluate_javascript` each take one `char*`. Hence `(void*) → void` and `() → void`.

Also update `reflect-config.json` to add `onTextSubmitted`:

- [ ] **Step 2: Replace reflect-config.json**

```json
[
  {
    "name": "dev.mproctor.cccli.bridge.Callbacks",
    "methods": [
      { "name": "onWindowClosed",  "parameterTypes": [] },
      { "name": "onTextSubmitted", "parameterTypes": ["java.lang.foreign.MemorySegment"] }
    ]
  }
]
```

- [ ] **Step 3: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add app-macos/src/main/resources/META-INF/native-image/
git commit -m "feat: update native image metadata for new upcall and downcall signatures"
```

---

## Task 8: Update Main.java

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/Main.java`

- [ ] **Step 1: Replace Main.java**

```java
package dev.mproctor.cccli;

import dev.mproctor.cccli.bridge.MacUIBridge;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

@QuarkusMain
public class Main implements QuarkusApplication {

    @Inject
    MacUIBridge bridge;

    public static void main(String... args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) {
        String initialHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <style>
                  * { box-sizing: border-box; margin: 0; padding: 0; }
                  body {
                    background: #1e1e1e;
                    color: #d4d4d4;
                    font-family: 'SF Mono', Menlo, 'Courier New', monospace;
                    font-size: 13px;
                    padding: 8px;
                    white-space: pre-wrap;
                    word-break: break-all;
                  }
                </style>
                </head>
                <body id="out">Claude Desktop CLI — ready\n</body>
                <script>
                function write(text) {
                  document.getElementById('out').textContent += text;
                }
                </script>
                </html>
                """;

        Log.info("Starting Claude Desktop CLI...");
        bridge.start("Claude Desktop CLI", 900, 600, initialHtml,
                () -> {
                    Log.info("Window closed via upcall — terminating");
                    bridge.terminate();
                },
                text -> {
                    Log.infof("Input received: %s", text);
                    // Echo input back to the terminal pane (JS string escaping)
                    String escaped = text
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\n", "\\n");
                    bridge.evaluateJavaScript("write('> " + escaped + "\\n')");
                });

        Log.info("Application terminated");
        return 0;
    }
}
```

- [ ] **Step 2: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add app-macos/src/main/java/dev/mproctor/cccli/Main.java
git commit -m "feat: Main — split pane start with initial HTML and echo input handler"
```

---

## Task 9: Validate in JVM mode

- [ ] **Step 1: Build**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd /Users/mdproctor/claude/cccli
mvn compile -pl app-macos -am -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS (warnings about Unsafe are fine).

- [ ] **Step 2: Run**

```bash
cd /Users/mdproctor/claude/cccli/app-macos
mvn quarkus:dev -Dcccli.dylib.path="$(pwd)/../mac-ui-bridge/build/libMyMacUI.dylib"
```

- [ ] **Step 3: Validate visually**

Confirm all of the following:

1. Window opens with two panes (terminal top, input bottom)
2. Terminal pane shows: `Claude Desktop CLI — ready`
3. Input pane is an editable text field with dark background
4. Type something in the input pane and press **Enter**
5. Log shows: `INFO  Input received: <your text>`
6. Terminal pane shows: `> <your text>`
7. Close the window — log shows: `INFO  Window closed via upcall — terminating`

**If terminal pane is blank (HTML not loaded):** The WKWebView may need a moment to render. Wait 1 second — the HTML loads asynchronously after window setup.

**If Enter key inserts a newline instead of submitting:** The `NSTextViewDelegate` is not wired. Check that `inputText.delegate = appDelegate` is set in `setupSplitPane`.

- [ ] **Step 4: Commit validation note**

```bash
cd /Users/mdproctor/claude/cccli
git commit --allow-empty -m "validated: JVM mode split pane — WKWebView + NSTextView + echo upcall"
```

---

## Task 10: Rebuild native image and validate

- [ ] **Step 1: Build native image**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd /Users/mdproctor/claude/cccli
mvn package -pl app-macos -am -Pnative 2>&1 | grep -E "BUILD|Error:|GraalVM|Generating|========"
```

Expected: `BUILD SUCCESS`

- [ ] **Step 2: Run native binary**

```bash
/Users/mdproctor/claude/cccli/app-macos/target/app-macos-1.0.0-SNAPSHOT-runner \
  -Dcccli.dylib.path=/Users/mdproctor/claude/cccli/mac-ui-bridge/build/libMyMacUI.dylib
```

- [ ] **Step 3: Validate — same as JVM checklist**

1. Window opens with split pane ✅
2. Terminal pane shows `Claude Desktop CLI — ready` ✅
3. Type text → Enter → echoes in terminal pane ✅
4. Startup time logged (should be ~0.020s) ✅
5. Close window → clean exit ✅

**If `MissingForeignRegistrationError` for new upcall:** The `reachability-metadata.json` entry for `onTextSubmitted` with `parameterTypes: ["void*"]` is missing or wrong. Check Task 7.

**If `NoSuchMethodException` for `onTextSubmitted`:** The `reflect-config.json` entry is missing `java.lang.foreign.MemorySegment` in the parameterTypes. Check Task 7 Step 2.

- [ ] **Step 4: Update DECISIONS.md**

Add a brief note confirming the split pane architecture works end-to-end in native image.

- [ ] **Step 5: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add DECISIONS.md
git commit -m "validated: native image split pane — WKWebView JS eval + NSTextView upcall"
```

---

## Self-Review

**Spec coverage:**
- ✅ NSSplitView — top/bottom split
- ✅ WKWebView in top pane
- ✅ NSTextView in bottom pane
- ✅ Enter key submits text via upcall into Java
- ✅ Java writes to terminal pane via `evaluateJavaScript`
- ✅ Initial HTML loaded at startup
- ✅ JVM mode validation
- ✅ Native image validation

**What this plan does NOT cover (future plans):**
- xterm.js (Plan 3 — needs PTY output to be meaningful)
- PTY subprocess management
- InteractionDetector / state machine
- Slash command overlay
- `.app` bundle packaging

**Placeholder scan:** None.

**Type consistency:**
- `Callbacks.onTextSubmitted(MemorySegment)` ← matches `reflect-config.json` parameterType `java.lang.foreign.MemorySegment` ✅
- `Callbacks.createTextSubmittedCallback(Arena, Consumer<String>)` ← called in `MacUIBridge.start()` ✅
- `MyMacUI_h.myui_start(seg, int, int, seg, seg, seg)` ← 6 params matching updated header ✅
- `reachability-metadata.json` downcall `(void*, jint, jint, void*, void*, void*) → jlong` ← matches `myui_start` ✅
