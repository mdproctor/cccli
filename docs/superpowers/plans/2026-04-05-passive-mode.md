# Passive Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While Claude is generating a response the input field is disabled and a Stop button appears; clicking Stop sends SIGINT to claude and immediately re-enables input; when claude finishes the quiet timer re-enables input automatically.

**Architecture:** `InteractionDetector` in `app-core` tracks mode using a timer — PTY output → PASSIVE, 800ms of silence → FREE_TEXT, Stop clicked → FREE_TEXT immediately. The ObjC bridge gains `myui_set_passive_mode(int)` (disables NSTextField + shows Stop button) and a `StopClickedCallback` 7th parameter to `myui_start()`. Java calls `bridge.setPassiveMode(bool)` from the detector's callback thread (thread-safe via `performSelectorOnMainThread:`). `PtyProcess.sendSigInt()` sends SIGINT to the claude subprocess.

**Tech Stack:** Java 22+, Quarkus Native, Panama FFM, jextract, Objective-C / AppKit, GraalVM 25

---

## Interaction model

| Event | From state | To state | UI effect |
|-------|-----------|----------|-----------|
| User presses Enter | FREE_TEXT | PASSIVE | Input disabled, Stop button appears |
| PTY output arrives | any | PASSIVE | Timer resets; state change fires only on first output |
| 800ms quiet | PASSIVE | FREE_TEXT | Input enabled, Stop button hidden, focus restored |
| Stop button clicked | PASSIVE | FREE_TEXT | SIGINT sent, input enabled immediately |
| Window closed | any | FREE_TEXT | SIGINT + PTY closed |

---

## File Map

```
app-core/
└── src/
    ├── main/java/dev/mproctor/cccli/
    │   ├── ClaudeState.java               CREATE — enum: FREE_TEXT, PASSIVE
    │   ├── InteractionDetector.java        CREATE — timer-based state machine
    │   └── pty/PtyProcess.java            MODIFY — add sendSigInt()
    └── test/java/dev/mproctor/cccli/
        └── InteractionDetectorTest.java    CREATE — 5 tests

mac-ui-bridge/
├── include/MyMacUI.h                       MODIFY — StopClickedCallback, myui_set_passive_mode, myui_start 7th param
└── src/MyMacUI.m                           MODIFY — Stop button, passive mode logic, applyPassiveMode:

app-macos/src/main/
├── java/dev/mproctor/cccli/
│   ├── Main.java                           MODIFY — wire detector, stop callback
│   └── bridge/
│       ├── MacUIBridge.java                MODIFY — add setPassiveMode(), extend start() with stop callback
│       └── Callbacks.java                  MODIFY — add createStopClickedCallback()
│       └── gen/                            REGENERATE — jextract after header changes
└── resources/META-INF/native-image/dev.mproctor.cccli/app-macos/
    └── reachability-metadata.json          MODIFY — new upcall + updated myui_start downcall + myui_set_passive_mode downcall
```

---

## Task 1: ClaudeState + InteractionDetector + PtyProcess.sendSigInt

**Files:**
- Create: `app-core/src/main/java/dev/mproctor/cccli/ClaudeState.java`
- Create: `app-core/src/main/java/dev/mproctor/cccli/InteractionDetector.java`
- Modify: `app-core/src/main/java/dev/mproctor/cccli/pty/PtyProcess.java`
- Create: `app-core/src/test/java/dev/mproctor/cccli/InteractionDetectorTest.java`

- [ ] **Step 1: Write the failing tests**

Create `app-core/src/test/java/dev/mproctor/cccli/InteractionDetectorTest.java`:

```java
package dev.mproctor.cccli;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class InteractionDetectorTest {

    /** Short quiet timeout so tests run fast. */
    private static final long TEST_QUIET_MS = 100;

    private List<ClaudeState> stateChanges;
    private InteractionDetector detector;

    @BeforeEach
    void setUp() {
        stateChanges = new CopyOnWriteArrayList<>();
        detector = new InteractionDetector(stateChanges::add, TEST_QUIET_MS);
    }

    @AfterEach
    void tearDown() {
        detector.close();
        stateChanges.clear();
    }

    @Test
    void initialStateIsFreeText() {
        assertEquals(ClaudeState.FREE_TEXT, detector.getState());
        assertTrue(stateChanges.isEmpty());
    }

    @Test
    void onSubmitSwitchesToPassive() {
        detector.onSubmit();
        assertEquals(ClaudeState.PASSIVE, detector.getState());
        assertEquals(List.of(ClaudeState.PASSIVE), stateChanges);
    }

    @Test
    void multipleOutputCallsFireOnlyOnePassiveTransition() {
        detector.onOutput();
        detector.onOutput();
        detector.onOutput();
        assertEquals(1, stateChanges.size());
        assertEquals(ClaudeState.PASSIVE, stateChanges.get(0));
    }

    @Test
    void quietTimerSwitchesBackToFreeText() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2); // PASSIVE + FREE_TEXT
        detector.close();
        detector = new InteractionDetector(state -> {
            stateChanges.add(state);
            latch.countDown();
        }, TEST_QUIET_MS);

        detector.onOutput();
        assertTrue(latch.await(1, TimeUnit.SECONDS), "timeout waiting for state transitions");
        assertEquals(List.of(ClaudeState.PASSIVE, ClaudeState.FREE_TEXT), stateChanges);
    }

    @Test
    void forceIdleSwitchesImmediately() {
        detector.onSubmit(); // → PASSIVE
        detector.forceIdle(); // → FREE_TEXT immediately
        assertEquals(ClaudeState.FREE_TEXT, detector.getState());
        assertEquals(List.of(ClaudeState.PASSIVE, ClaudeState.FREE_TEXT), stateChanges);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.0.2-graalce
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q 2>&1 | head -10
```

Expected: compilation error — `ClaudeState`, `InteractionDetector` do not exist.

- [ ] **Step 3: Create ClaudeState.java**

Create `app-core/src/main/java/dev/mproctor/cccli/ClaudeState.java`:

```java
package dev.mproctor.cccli;

/** The current interaction mode — determines UI state. */
public enum ClaudeState {
    /** Claude is waiting for user input. Input field is enabled. */
    FREE_TEXT,
    /** Claude is generating a response. Input field is disabled; Stop button visible. */
    PASSIVE
}
```

- [ ] **Step 4: Create InteractionDetector.java**

Create `app-core/src/main/java/dev/mproctor/cccli/InteractionDetector.java`:

```java
package dev.mproctor.cccli;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Infers Claude's interaction mode from PTY output timing.
 *
 * Rules:
 *   onSubmit()  → PASSIVE immediately (user sent input, waiting for claude)
 *   onOutput()  → PASSIVE + reset quiet timer (claude is responding)
 *   quiet timer → FREE_TEXT (claude has finished)
 *   forceIdle() → FREE_TEXT immediately (Stop clicked, window closed)
 *
 * onStateChanged is called on the scheduler thread — callers must be thread-safe.
 */
public class InteractionDetector implements AutoCloseable {

    /** Quiet timeout used in production. */
    public static final long DEFAULT_QUIET_MS = 800;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "interaction-detector");
                t.setDaemon(true);
                return t;
            });

    private final Consumer<ClaudeState> onStateChanged;
    private final long quietMs;
    private volatile ClaudeState state = ClaudeState.FREE_TEXT;
    private volatile ScheduledFuture<?> quietTimer;

    public InteractionDetector(Consumer<ClaudeState> onStateChanged) {
        this(onStateChanged, DEFAULT_QUIET_MS);
    }

    /** Package-private constructor for tests — allows shorter quiet timeout. */
    InteractionDetector(Consumer<ClaudeState> onStateChanged, long quietMs) {
        this.onStateChanged = onStateChanged;
        this.quietMs = quietMs;
    }

    /**
     * Call when the user submits text. Enters PASSIVE immediately.
     * The quiet timer does NOT start here — it starts when PTY output arrives.
     * This means if claude never responds, the app stays PASSIVE until forceIdle() is called.
     */
    public void onSubmit() {
        enterPassive();
        cancelQuietTimer();
    }

    /**
     * Call on every PTY output chunk. Enters PASSIVE (if not already) and resets the
     * quiet timer. After quietMs of silence the detector transitions to FREE_TEXT.
     */
    public void onOutput() {
        enterPassive();
        rescheduleQuietTimer();
    }

    /**
     * Immediately transitions to FREE_TEXT, cancelling any pending quiet timer.
     * Call when Stop is clicked or the window closes.
     */
    public void forceIdle() {
        cancelQuietTimer();
        if (state != ClaudeState.FREE_TEXT) {
            state = ClaudeState.FREE_TEXT;
            onStateChanged.accept(ClaudeState.FREE_TEXT);
        }
    }

    public ClaudeState getState() { return state; }

    @Override
    public void close() {
        cancelQuietTimer();
        scheduler.shutdownNow();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void enterPassive() {
        if (state != ClaudeState.PASSIVE) {
            state = ClaudeState.PASSIVE;
            onStateChanged.accept(ClaudeState.PASSIVE);
        }
    }

    private void rescheduleQuietTimer() {
        cancelQuietTimer();
        quietTimer = scheduler.schedule(this::onQuiet, quietMs, TimeUnit.MILLISECONDS);
    }

    private void cancelQuietTimer() {
        ScheduledFuture<?> t = quietTimer;
        if (t != null) { t.cancel(false); quietTimer = null; }
    }

    private void onQuiet() {
        state = ClaudeState.FREE_TEXT;
        onStateChanged.accept(ClaudeState.FREE_TEXT);
    }
}
```

- [ ] **Step 5: Add SIGINT constant and sendSigInt() to PtyProcess**

Add `SIGINT = 2` to `PosixLibrary.java` constants section (after `SIGKILL = 9`):

```java
/** Signal numbers */
public static final int SIGTERM = 15;
public static final int SIGKILL = 9;
public static final int SIGINT  = 2;
```

Add `sendSigInt()` to `PtyProcess.java` (in the Close section, before `close()`):

```java
/**
 * Sends SIGINT to the subprocess (equivalent to Ctrl+C in a terminal).
 * Safe to call if the subprocess has already exited — kill() returns -1 harmlessly.
 */
public void sendSigInt() {
    if (pid > 0) PosixLibrary.kill(pid, PosixLibrary.SIGINT);
}
```

- [ ] **Step 6: Run all tests**

```bash
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q
```

Expected: BUILD SUCCESS. 26 total tests (21 prior + 5 new InteractionDetectorTest).

- [ ] **Step 7: Commit**

```bash
git add app-core/src/main/java/dev/mproctor/cccli/ClaudeState.java \
        app-core/src/main/java/dev/mproctor/cccli/InteractionDetector.java \
        app-core/src/main/java/dev/mproctor/cccli/pty/PtyProcess.java \
        app-core/src/test/java/dev/mproctor/cccli/InteractionDetectorTest.java
git commit -m "feat(core): InteractionDetector, ClaudeState, PtyProcess.sendSigInt"
```

---

## Task 2: ObjC bridge — Stop button + passive mode

**Files:**
- Modify: `mac-ui-bridge/include/MyMacUI.h`
- Modify: `mac-ui-bridge/src/MyMacUI.m`

The Stop button overlays the right side of the NSTextField (which is disabled in PASSIVE mode, so no input conflict). Both views use `NSViewWidthSizable` / `NSViewMinXMargin` for window resize.

- [ ] **Step 1: Update MyMacUI.h**

Replace the ENTIRE content of `mac-ui-bridge/include/MyMacUI.h` with:

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

/** Fired on the AppKit main thread when the user clicks the Stop button. */
typedef void (*StopClickedCallback)(void);

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
 * Enter or exit passive mode:
 *   passive=1 → disable input field, show Stop button
 *   passive=0 → enable input field, hide Stop button, restore focus
 * Thread-safe — dispatches to AppKit main thread internally.
 */
void myui_set_passive_mode(int passive);

/**
 * Full entry point. Creates the window with a split pane, loads initial text,
 * starts the AppKit event loop, and blocks until the application terminates.
 *
 * onStop is fired when the user clicks the Stop button (PASSIVE mode only).
 * Safe to call from any thread.
 */
intptr_t myui_start(const char* title,
                    int width,
                    int height,
                    const char* initialHtml,
                    WindowClosedCallback onClosed,
                    TextSubmittedCallback onTextSubmitted,
                    StopClickedCallback onStop);

/** Append plain text to the output pane. Thread-safe. */
void myui_append_output(const char* text);

/** Retained for ABI compatibility — no-op in current implementation. */
void myui_load_html(const char* html);

/** Retained for ABI compatibility — no-op in current implementation. */
void myui_evaluate_javascript(const char* script);

#ifdef __cplusplus
}
#endif

#endif /* MyMacUI_h */
```

- [ ] **Step 2: Update MyMacUI.m**

Replace the ENTIRE content of `mac-ui-bridge/src/MyMacUI.m` with:

```objc
/* mac-ui-bridge/src/MyMacUI.m */
#import <Cocoa/Cocoa.h>
#include <string.h>
#include "MyMacUI.h"

/* ── Forward declarations ────────────────────────────────────────────────── */

static void doAppend(NSString *str);

/* ── AppDelegate ─────────────────────────────────────────────────────────── */

@interface CCCAppDelegate : NSObject <NSApplicationDelegate, NSWindowDelegate>
@property (nonatomic, assign) WindowClosedCallback   onClosed;
@property (nonatomic, assign) TextSubmittedCallback  onTextSubmitted;
@property (nonatomic, assign) StopClickedCallback    onStop;
@property (nonatomic, weak)   NSTextField           *inputField;
- (void)appendToOutput:(NSString *)str;
- (void)applyPassiveMode:(NSNumber *)value;
@end

@implementation CCCAppDelegate

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return YES;
}

- (void)windowDidBecomeKey:(NSNotification *)notification {
    /* Apply the empty-field cursor-blink fix exactly once, at the moment the
     * window becomes key and the run loop is live. GCD dispatch blocks cannot
     * fire while [NSApp run] is itself executing inside a dispatch_async block,
     * so AppKit delegate methods are the only safe hook for post-run-loop work. */
    if (self.inputField) {
        NSWindow *w = notification.object;
        [w makeFirstResponder:self.inputField];
        [self.inputField setStringValue:@" "];
        [self.inputField setStringValue:@""];
        self.inputField = nil; /* clear so this only runs once */
    }
}

- (void)appendToOutput:(NSString *)str {
    doAppend(str);
}

- (void)applyPassiveMode:(NSNumber *)value {
    extern NSTextField *theInputField;
    extern NSButton    *theStopButton;
    BOOL passive = value.boolValue;
    theInputField.enabled = !passive;
    theStopButton.hidden  = !passive;
    /* Restore keyboard focus to input when becoming interactive */
    if (!passive) {
        NSWindow *w = theInputField.window;
        if (w) [w makeFirstResponder:theInputField];
    }
}

- (void)windowWillClose:(NSNotification *)notification {
    if (self.onClosed) {
        WindowClosedCallback cb = self.onClosed;
        self.onClosed = NULL;
        cb();
    }
}

/* NSTextField action — fired when user presses Enter */
- (void)textFieldSubmit:(NSTextField *)sender {
    NSString *text = [sender.stringValue
                      stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
    if (text.length > 0 && self.onTextSubmitted) {
        self.onTextSubmitted(text.UTF8String);
    }
    sender.stringValue = @"";
}

/* NSButton action — fired when user clicks the Stop button */
- (void)stopButtonClicked:(NSButton *)sender {
    if (self.onStop) self.onStop();
}

@end

/* ── Module-level state ───────────────────────────────────────────────────── */

static CCCAppDelegate *appDelegate    = nil;
static NSTextView     *theOutputView  = nil;
static NSTextField    *theInputField  = nil;
static NSButton       *theStopButton  = nil;

/* ── Internal helpers ─────────────────────────────────────────────────────── */

static void setupUI(NSWindow *window,
                    const char *initialText,
                    TextSubmittedCallback onTextSubmitted,
                    StopClickedCallback   onStop) {
    /* KEY: add views to the EXISTING contentView — never replace it.
     * Replacing contentView breaks keyboard event routing.             */

    NSView  *root   = window.contentView;
    CGFloat  w      = root.bounds.size.width;
    CGFloat  h      = root.bounds.size.height;
    CGFloat  inputH = 36.0;
    CGFloat  outH   = h - inputH - 1;

    root.wantsLayer = YES;
    root.layer.backgroundColor =
        [NSColor colorWithRed:0.12 green:0.12 blue:0.12 alpha:1.0].CGColor;

    /* ── Output pane (top): NSScrollView + NSTextView ────────────────────── */
    NSScrollView *outputScroll = [[NSScrollView alloc]
        initWithFrame:NSMakeRect(0, inputH + 1, w, outH)];
    outputScroll.autoresizingMask    = NSViewWidthSizable | NSViewHeightSizable;
    outputScroll.hasVerticalScroller = YES;
    outputScroll.drawsBackground     = YES;
    outputScroll.backgroundColor     = [NSColor colorWithRed:0.12 green:0.12
                                                        blue:0.12 alpha:1.0];

    NSTextView *outputText = [[NSTextView alloc]
        initWithFrame:NSMakeRect(0, 0, outputScroll.contentSize.width,
                                       outputScroll.contentSize.height)];
    outputText.minSize            = NSMakeSize(0, outputScroll.contentSize.height);
    outputText.maxSize            = NSMakeSize(FLT_MAX, FLT_MAX);
    outputText.verticallyResizable   = YES;
    outputText.horizontallyResizable = NO;
    outputText.autoresizingMask   = NSViewWidthSizable;
    outputText.textContainer.containerSize    = NSMakeSize(outputScroll.contentSize.width, FLT_MAX);
    outputText.textContainer.widthTracksTextView = YES;
    outputText.editable           = NO;
    outputText.selectable         = YES;
    outputText.richText           = NO;
    outputText.font               = [NSFont monospacedSystemFontOfSize:13
                                                                weight:NSFontWeightRegular];
    outputText.textColor          = [NSColor colorWithRed:0.84 green:0.84
                                                     blue:0.84 alpha:1.0];
    outputText.backgroundColor    = [NSColor colorWithRed:0.12 green:0.12
                                                     blue:0.12 alpha:1.0];
    outputText.automaticSpellingCorrectionEnabled = NO;
    theOutputView = outputText;
    outputScroll.documentView = outputText;
    [root addSubview:outputScroll];

    if (initialText) {
        NSString *s = [NSString stringWithUTF8String:initialText];
        [outputText setString:s];
    }

    /* ── Input pane (bottom): NSTextField ───────────────────────────────── */
    NSTextField *inputField = [[NSTextField alloc]
        initWithFrame:NSMakeRect(0, 0, w, inputH)];
    inputField.autoresizingMask  = NSViewWidthSizable | NSViewMaxYMargin;
    inputField.font              = [NSFont monospacedSystemFontOfSize:13
                                                               weight:NSFontWeightRegular];
    inputField.textColor         = [NSColor colorWithRed:0.84 green:0.84
                                                    blue:0.84 alpha:1.0];
    inputField.backgroundColor   = [NSColor colorWithRed:0.12 green:0.12
                                                    blue:0.12 alpha:1.0];
    inputField.drawsBackground   = YES;
    inputField.bezeled           = NO;
    inputField.editable          = YES;
    inputField.selectable        = YES;
    /* Explicit placeholder colour — system default is near-invisible on dark bg */
    inputField.placeholderAttributedString = [[NSAttributedString alloc]
        initWithString:@"Type a message and press Enter…"
            attributes:@{ NSForegroundColorAttributeName:
                [NSColor colorWithRed:0.50 green:0.50 blue:0.50 alpha:1.0] }];
    inputField.target = appDelegate;
    inputField.action = @selector(textFieldSubmit:);
    [root addSubview:inputField];
    theInputField = inputField;

    /* ── Stop button (overlaid right of input, hidden by default) ──────── */
    CGFloat btnW = 60.0;
    NSButton *stopBtn = [[NSButton alloc]
        initWithFrame:NSMakeRect(w - btnW - 8, 4, btnW, 28)];
    stopBtn.autoresizingMask = NSViewMinXMargin | NSViewMaxYMargin;
    stopBtn.title            = @"Stop";
    stopBtn.bezelStyle       = NSBezelStyleRounded;
    stopBtn.target           = appDelegate;
    stopBtn.action           = @selector(stopButtonClicked:);
    stopBtn.hidden           = YES;
    [root addSubview:stopBtn];
    theStopButton = stopBtn;

    /* Store references for delegate callbacks */
    appDelegate.inputField      = inputField;  /* cursor-blink fix, then cleared */
    appDelegate.onTextSubmitted = onTextSubmitted;
    appDelegate.onStop          = onStop;
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

void myui_set_passive_mode(int passive) {
    /* Cannot use dispatch_async — GCD main queue is serialised when [NSApp run]
     * executes inside a dispatch_async block (AppKit pitfall #1).
     * performSelectorOnMainThread: schedules on NSRunLoop instead.         */
    [appDelegate performSelectorOnMainThread:@selector(applyPassiveMode:)
                                 withObject:@((BOOL)passive)
                              waitUntilDone:NO];
}

static void doAppend(NSString *str) {
    if (!theOutputView) return;
    NSString *updated = [(theOutputView.string ?: @"") stringByAppendingString:str];
    [theOutputView setString:updated];
    [theOutputView scrollToEndOfDocument:nil];
}

void myui_append_output(const char *text) {
    if (!text) return;
    NSString *str = [NSString stringWithUTF8String:text];
    if ([NSThread isMainThread]) {
        doAppend(str);
    } else {
        /* performSelectorOnMainThread: instead of dispatch_async — see AppKit pitfall #1. */
        [appDelegate performSelectorOnMainThread:@selector(appendToOutput:)
                                     withObject:str
                                  waitUntilDone:NO];
    }
}

/* myui_load_html and myui_evaluate_javascript are retained in the ABI for
 * future WKWebView integration (Plan 5b, inside a proper .app bundle).    */
void myui_load_html(const char *html) { (void)html; }
void myui_evaluate_javascript(const char *script) { (void)script; }

intptr_t myui_start(const char *title,
                    int width,
                    int height,
                    const char *initialHtml,
                    WindowClosedCallback onClosed,
                    TextSubmittedCallback onTextSubmitted,
                    StopClickedCallback onStop) {

    __block intptr_t windowHandle = 0;
    char *titleCopy = strdup(title       ? title       : "");
    char *htmlCopy  = strdup(initialHtml ? initialHtml : "");

    if ([NSThread isMainThread]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            myui_init_application();
            windowHandle = myui_create_window(titleCopy, width, height, onClosed);
            free(titleCopy);
            NSWindow *window = (__bridge NSWindow *)(void *)windowHandle;
            setupUI(window, htmlCopy, onTextSubmitted, onStop);
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
            setupUI(window, htmlCopy, onTextSubmitted, onStop);
            free(htmlCopy);
            [NSApp run];
            dispatch_semaphore_signal(done);
        });
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    }

    return windowHandle;
}
```

- [ ] **Step 3: Rebuild the dylib**

```bash
cd /Users/mdproctor/claude/cccli/mac-ui-bridge
make clean && make
```

Expected: `build/libMyMacUI.dylib` rebuilt with no errors.

- [ ] **Step 4: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add mac-ui-bridge/include/MyMacUI.h mac-ui-bridge/src/MyMacUI.m
git commit -m "feat(bridge): Stop button, passive mode, StopClickedCallback in myui_start"
```

---

## Task 3: Regenerate Panama bindings + update Java bridge

**Files:**
- Regenerate: `app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/`
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java`
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java`
- Modify: `app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json`

- [ ] **Step 1: Regenerate jextract bindings**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.0.2-graalce
export PATH="$HOME/tools/jextract-22/bin:$PATH"
cd /Users/mdproctor/claude/cccli

rm -rf app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/
jextract --output app-macos/src/main/java \
         --target-package dev.mproctor.cccli.bridge.gen \
         mac-ui-bridge/include/MyMacUI.h
```

Expected: `gen/` directory recreated with updated bindings that include `StopClickedCallback` and the 7-parameter `myui_start`.

- [ ] **Step 2: Add onStopClicked to Callbacks.java**

Open `app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java`.

Add after `textSubmittedHandler` field:
```java
private static volatile Runnable stopClickedHandler;
```

Add after `createTextSubmittedCallback()` method:
```java
/** Creates a void(*)(void) upcall stub that calls handler when the Stop button is clicked. */
public static MemorySegment createStopClickedCallback(Arena arena, Runnable handler) {
    stopClickedHandler = handler;
    try {
        MethodHandle mh = MethodHandles.lookup()
                .findStatic(Callbacks.class, "onStopClicked",
                        MethodType.methodType(void.class));
        return Linker.nativeLinker().upcallStub(mh, VOID_VOID, arena);
    } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException("Failed to create stop-clicked upcall stub", e);
    }
}
```

Add after `onTextSubmitted()` method:
```java
/** Called from Objective-C when the user clicks the Stop button. */
public static void onStopClicked() {
    Runnable handler = stopClickedHandler;
    if (handler != null) handler.run();
}
```

- [ ] **Step 3: Update MacUIBridge.java**

Add `setPassiveMode(boolean)` method and extend `start()` with the stop callback parameter.

Replace the existing `start()` signature and body with:
```java
public long start(String title, int width, int height,
                  String initialHtml,
                  Runnable onClosed,
                  Consumer<String> onTextSubmitted,
                  Runnable onStop) {
    try (Arena temp = Arena.ofConfined()) {
        MemorySegment titleSeg      = temp.allocateFrom(title != null ? title : "");
        MemorySegment htmlSeg       = temp.allocateFrom(initialHtml != null ? initialHtml : "");
        MemorySegment closedCb      = Callbacks.createWindowClosedCallback(arena, onClosed);
        MemorySegment submittedCb   = Callbacks.createTextSubmittedCallback(arena, onTextSubmitted);
        MemorySegment stopCb        = Callbacks.createStopClickedCallback(arena, onStop);
        return MyMacUI_h.myui_start(titleSeg, width, height,
                                    htmlSeg, closedCb, submittedCb, stopCb);
    }
}
```

Add `setPassiveMode()` method after `appendOutput()`:
```java
/**
 * Enable or disable passive mode. Thread-safe — the ObjC bridge dispatches
 * to the AppKit main thread via performSelectorOnMainThread:.
 *   passive=true  → input field disabled, Stop button visible
 *   passive=false → input field enabled, Stop button hidden
 */
public void setPassiveMode(boolean passive) {
    MyMacUI_h.myui_set_passive_mode(passive ? 1 : 0);
}
```

- [ ] **Step 4: Update reachability-metadata.json**

Replace `reachability-metadata.json` with:

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
      },
      {
        "class": "dev.mproctor.cccli.bridge.Callbacks",
        "method": "onStopClicked",
        "returnType": "void",
        "parameterTypes": []
      }
    ],
    "downcalls": [
      {"returnType": "jlong", "parameterTypes": ["void*", "jint", "jint", "void*", "void*", "void*", "void*"]},
      {"returnType": "void",  "parameterTypes": ["void*"]},
      {"returnType": "void",  "parameterTypes": []},
      {"returnType": "void",  "parameterTypes": ["jint"]},
      {"returnType": "jint",  "parameterTypes": ["jint"]},
      {"returnType": "jint",  "parameterTypes": ["void*"]},
      {"returnType": "void*", "parameterTypes": ["jint"]},
      {"returnType": "jint",  "parameterTypes": ["void*", "jint"]},
      {"returnType": "jint",  "parameterTypes": ["void*", "jint", "jint"]},
      {"returnType": "jlong", "parameterTypes": ["jint", "void*", "jlong"]},
      {"returnType": "jint",  "parameterTypes": ["jint", "jint"]},
      {"returnType": "jint",  "parameterTypes": ["jint", "void*", "jint"]},
      {"returnType": "jint",  "parameterTypes": ["jint", "jlong", "void*"]},
      {"returnType": "jint",  "parameterTypes": ["void*", "void*", "void*", "void*", "void*", "void*"]},
      {"returnType": "jint",  "parameterTypes": ["jint", "void*"]},
      {"returnType": "jint",  "parameterTypes": ["jint", "jint", "void*"]}
    ]
  }
}
```

Changes from prior version:
- `myui_start` downcall updated from 6 → 7 void* params
- `myui_set_passive_mode` downcall added: `void(jint)`
- `onStopClicked` added to `directUpcalls`

- [ ] **Step 5: Update reflect-config.json**

Add `onStopClicked` to `app-macos/src/main/resources/META-INF/native-image/reflect-config.json`:

```json
[
  {
    "name": "dev.mproctor.cccli.bridge.Callbacks",
    "methods": [
      { "name": "onWindowClosed",  "parameterTypes": [] },
      { "name": "onTextSubmitted", "parameterTypes": ["java.lang.foreign.MemorySegment"] },
      { "name": "onStopClicked",   "parameterTypes": [] }
    ]
  }
]
```

- [ ] **Step 6: Compile to verify bindings**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.0.2-graalce
cd /Users/mdproctor/claude/cccli
mvn install -q
```

Expected: BUILD SUCCESS. Any compilation errors reveal mismatches between the generated `gen/` and the Java code — fix them before continuing.

- [ ] **Step 7: Commit**

```bash
git add app-macos/src/main/java/dev/mproctor/cccli/bridge/ \
        app-macos/src/main/resources/META-INF/native-image/
git commit -m "feat(macos): Panama bindings for StopClickedCallback and setPassiveMode"
```

---

## Task 4: Wire InteractionDetector into Main.java + manual test

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/Main.java`

- [ ] **Step 1: Replace Main.java with the wired version**

```java
package dev.mproctor.cccli;

import dev.mproctor.cccli.bridge.MacUIBridge;
import dev.mproctor.cccli.pty.PtyProcess;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import java.nio.file.Path;

@QuarkusMain
public class Main implements QuarkusApplication {

    @Inject
    MacUIBridge bridge;

    public static void main(String... args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) {
        Path claudePath = ClaudeLocator.locate();
        if (claudePath == null) {
            Log.error("claude binary not found in PATH");
            System.err.println("""
                    claude not found. Install it with:
                      npm install -g @anthropic-ai/claude-code
                    Then relaunch the app.
                    """);
            return 1;
        }
        Log.infof("Found claude at: %s", claudePath);

        PtyProcess pty = new PtyProcess();
        pty.open();
        pty.spawn(new String[]{claudePath.toString()});

        // Detector: bridge.setPassiveMode() is called from the detector's
        // scheduler thread — safe because myui_set_passive_mode() dispatches
        // to the AppKit main thread via performSelectorOnMainThread:.
        InteractionDetector detector = new InteractionDetector(
                state -> bridge.setPassiveMode(state == ClaudeState.PASSIVE));

        pty.startReader(text -> {
            detector.onOutput();
            bridge.appendOutput(AnsiStripper.strip(text));
        });

        // Plan 5 wires resize to actual window dimensions.
        pty.resize(24, 120);

        Log.info("Starting Claude Desktop CLI...");
        bridge.start("Claude Desktop CLI", 900, 600,
                "Connecting to Claude...\n",
                () -> {
                    Log.info("Window closed — terminating");
                    detector.forceIdle();
                    detector.close();
                    pty.close();
                    bridge.terminate();
                },
                text -> {
                    if (detector.getState() == ClaudeState.FREE_TEXT) {
                        Log.infof("Sending to claude: %s", text);
                        detector.onSubmit();
                        pty.write(text + "\n");
                    }
                    // In PASSIVE: input field is disabled so this shouldn't fire;
                    // the state check is belt-and-suspenders.
                },
                () -> {
                    Log.info("Stop clicked — sending SIGINT");
                    pty.sendSigInt();
                    detector.forceIdle();
                });

        Log.info("Application terminated");
        return 0;
    }
}
```

- [ ] **Step 2: Run in JVM dev mode**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.0.2-graalce
cd /Users/mdproctor/claude/cccli
mvn install -q
cd app-macos
mvn quarkus:dev -Dcccli.dylib.path="$(pwd)/../mac-ui-bridge/build/libMyMacUI.dylib"
```

- [ ] **Step 3: Manual integration test**

1. Window opens — claude starts. Input field should be enabled.
2. Type `say hello in one sentence` and press Enter.
3. **Expected:** Input field becomes disabled immediately (or within ~100ms). Stop button appears.
4. Claude responds. When response finishes (800ms quiet), input field re-enables, Stop button hides.
5. Type another prompt. Repeat to confirm mode cycling works.
6. Type a prompt that will take time (e.g. `write a 10 sentence paragraph about cats`). While claude is generating, click **Stop**.
7. **Expected:** SIGINT sent, claude stops, input re-enables immediately, Stop button hides.
8. Close window — app exits cleanly.

- [ ] **Step 4: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add app-macos/src/main/java/dev/mproctor/cccli/Main.java
git commit -m "feat(macos): wire InteractionDetector — PASSIVE mode, Stop button"
```

---

## Task 5: Native image build and validation

**Files:** No changes expected.

- [ ] **Step 1: Build native image**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.0.2-graalce
cd /Users/mdproctor/claude/cccli
mvn package -pl app-macos -am -Pnative -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

**If build fails:**
- `directUpcalls` error for `onStopClicked` → check `reflect-config.json` and `reachability-metadata.json`
- `myui_set_passive_mode` downcall error → verify `{"returnType": "void", "parameterTypes": ["jint"]}` is in the downcalls list
- `myui_start` downcall error → verify it now has 7 void* params (not 6)

- [ ] **Step 2: Run the native binary**

```bash
./app-macos/target/app-macos-1.0.0-SNAPSHOT-runner \
  -Dcccli.dylib.path=/Users/mdproctor/claude/cccli/mac-ui-bridge/build/libMyMacUI.dylib
```

- [ ] **Step 3: Manual test (same as Task 4 Step 3)**

Repeat the interactive test from Task 4:
1. Input disabled while claude generates → Stop button visible
2. Stop button sends SIGINT → input re-enables immediately
3. App exits cleanly on window close

- [ ] **Step 4: Commit**

If `reachability-metadata.json` or `native-image.properties` needed changes:
```bash
git add app-macos/src/main/resources/META-INF/native-image/
git commit -m "build: native image config for passive mode and stop callback"
```

If no changes needed:
```bash
git commit --allow-empty -m "chore: native image validates passive mode"
```

---

## Self-Review

### Spec coverage

| Requirement | Task |
|-------------|------|
| `InteractionDetector` — pattern match PTY → state | Task 1 |
| `ClaudeState` — FREE_TEXT / PASSIVE | Task 1 |
| PASSIVE mode — block Enter while Claude works | Task 2 (ObjC disable) + Task 4 (Java guard) |
| Stop button — visible in PASSIVE | Task 2 |
| Stop button → SIGINT | Task 4 |
| `PtyProcess.sendSigInt()` | Task 1 |
| Native image | Task 5 |

LIST_SELECTION and other advanced modes are **explicitly deferred** — the spec says "FREE_TEXT, PASSIVE, LIST_SELECTION, etc." but LIST_SELECTION requires pattern matching on claude's actual output format, which is better tackled when we understand how claude structures its prompts. Plan 5 delivers the most impactful part: PASSIVE mode.

### Placeholder scan

No TBD, TODO, or "similar to Task N" patterns. All code is complete.

### Type consistency

- `Callbacks.onStopClicked()` is `void()` — matches `VOID_VOID` descriptor used for `onWindowClosed` ✓
- `MacUIBridge.start()` new 7th param `Runnable onStop` — used in `Main.java` as `() -> { pty.sendSigInt(); detector.forceIdle(); }` ✓
- `MyMacUI_h.myui_start()` gains a 7th `MemorySegment stopCb` — from jextract regen ✓
- `InteractionDetector.onSubmit/onOutput/forceIdle()` — all consistent with state in `Main.java` ✓
- `PosixLibrary.SIGINT = 2` — used in `PtyProcess.sendSigInt()` as `kill(pid, PosixLibrary.SIGINT)` ✓
