# Terminal Resize Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire window resize events through FitAddon → WKScriptMessageHandler → Java → `ioctl(TIOCSWINSZ)` so the PTY and xterm.js terminal always match the window size.

**Architecture:** AppKit `windowDidResize:` evaluates `window.fitAddon.fit()` in the WKWebView; FitAddon computes exact col/row counts from the rendered character cell and fires `term.onResize`; the JS handler posts `{cols, rows}` via `WKScriptMessageHandler`; the registered `WindowResizedCallback` C function pointer calls `pty.resize(rows, cols)` on the Java side.

**Tech Stack:** Obj-C/AppKit, WKWebView, xterm.js FitAddon (0.8.x), Panama FFM upcall stubs, GraalVM native image reachability metadata.

---

## File Map

| File | Action | What changes |
|------|--------|-------------|
| `mac-ui-bridge/resources/xterm/xterm-addon-fit.js` | **Create** | FitAddon UMD bundle (downloaded) |
| `mac-ui-bridge/resources/xterm/index.html` | **Modify** | Load FitAddon, window.fitAddon, term.onResize handler, remove hardcoded resize |
| `mac-ui-bridge/include/MyMacUI.h` | **Modify** | Add `WindowResizedCallback` typedef, `myui_set_resize_callback()` declaration |
| `mac-ui-bridge/src/MyMacUI.m` | **Modify** | WKScriptMessageHandler setup, `windowDidResize:`, `userContentController:didReceiveScriptMessage:`, `myui_set_resize_callback()` impl |
| `app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java` | **Modify** | Add `myui_set_resize_callback` downcall binding |
| `app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java` | **Modify** | Add `createWindowResizedCallback` factory + `onTerminalResized` static handler |
| `app-macos/src/main/resources/META-INF/native-image/reflect-config.json` | **Modify** | Add `onTerminalResized` method entry |
| `app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json` | **Modify** | Add directUpcalls entry for `onTerminalResized` |
| `app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java` | **Modify** | Add `setResizeCallback(BiConsumer<Integer,Integer>)` |
| `app-macos/src/main/java/dev/mproctor/cccli/Main.java` | **Modify** | Register resize callback, remove hardcoded `pty.resize(24, 120)` |
| `app-core/src/main/java/dev/mproctor/cccli/pty/PosixLibrary.java` | **Modify** | Add `TIOCGWINSZ` constant, `ioctlGetWinsize()` method |
| `app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java` | **Modify** | Add 6 integration tests (tput + TIOCGWINSZ read-back) |
| `app-macos/src/test/java/dev/mproctor/cccli/bridge/MacUIBridgeTest.java` | **Modify** | Add `setResizeCallback_smokesWithDylib` test |

---

## Task 0: Create GitHub Issue

- [ ] **Create tracking issue**

```bash
gh issue create \
  --title "feat: terminal resize — wire window resize to PTY and xterm.js" \
  --body "Implement terminal resize: FitAddon + WKScriptMessageHandler + TIOCSWINSZ.

## Scope
- Bundle xterm-addon-fit.js
- Obj-C: windowDidResize: → fitAddon.fit() → WKScriptMessageHandler → WindowResizedCallback
- Java: MacUIBridge.setResizeCallback() → pty.resize(rows, cols)
- Remove hardcoded 120×24

## Tests
6 new PTY integration tests (tput + TIOCGWINSZ), 1 bridge smoke test.

Design spec: docs/superpowers/specs/2026-04-07-terminal-resize-design.md"
```

Note the issue number — every commit in this plan must end with `Refs #N` (using that number).

---

## Task 1: Bundle FitAddon

**Files:**
- Create: `mac-ui-bridge/resources/xterm/xterm-addon-fit.js`

xterm.js 5.x (see git log: `git log --oneline -- 'mac-ui-bridge/resources/xterm/xterm.js'`) is compatible with `xterm-addon-fit@0.8.x`.

- [ ] **Download FitAddon UMD bundle**

```bash
curl -L \
  "https://cdn.jsdelivr.net/npm/xterm-addon-fit@0.8.0/lib/xterm-addon-fit.js" \
  -o mac-ui-bridge/resources/xterm/xterm-addon-fit.js
```

- [ ] **Verify the file downloaded and exposes FitAddon global**

```bash
wc -c mac-ui-bridge/resources/xterm/xterm-addon-fit.js
head -c 200 mac-ui-bridge/resources/xterm/xterm-addon-fit.js
```

Expected: file exists, size > 5000 bytes, first line contains `FitAddon` somewhere.

- [ ] **Commit**

```bash
git add mac-ui-bridge/resources/xterm/xterm-addon-fit.js
git commit -m "feat: vendor xterm-addon-fit 0.8.0

Refs #N"
```

---

## Task 2: Add TIOCGWINSZ to PosixLibrary (TDD)

**Files:**
- Modify: `app-core/src/main/java/dev/mproctor/cccli/pty/PosixLibrary.java`
- Modify: `app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java`

Write the tests first — they will fail to compile until the constant and method are added.

- [ ] **Write the failing tests** in `app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java`

Add in the `// ── resize() ──` section (after existing resize tests):

```java
@Test
void tiocgwinszReadsBackAfterResize() {
    pty.open();
    pty.spawn(new String[]{"/bin/cat"});
    pty.resize(42, 137);

    int[] dims = PosixLibrary.ioctlGetWinsize(pty.getMasterFd());
    assertNotNull(dims, "TIOCGWINSZ should not return null with active subprocess");
    assertEquals(42,  dims[0], "rows should be 42");
    assertEquals(137, dims[1], "cols should be 137");
}

@Test
void resizeCanBeCalledMultipleTimes() {
    pty.open();
    pty.spawn(new String[]{"/bin/cat"});
    pty.resize(24, 80);
    pty.resize(42, 137);

    int[] dims = PosixLibrary.ioctlGetWinsize(pty.getMasterFd());
    assertNotNull(dims);
    assertEquals(42,  dims[0], "rows should reflect last resize");
    assertEquals(137, dims[1], "cols should reflect last resize");
}

@Test
void resizeBeforeOpenIsNoOp() {
    assertDoesNotThrow(() -> pty.resize(24, 80),
            "resize() before open() should be a safe no-op — guards on masterFd < 0");
}
```

- [ ] **Verify tests fail to compile** (ioctlGetWinsize doesn't exist yet)

```bash
cd app-core && mvn test -pl . 2>&1 | grep -E "ERROR|cannot find symbol" | head -10
```

Expected: compilation errors mentioning `ioctlGetWinsize`.

- [ ] **Add TIOCGWINSZ constant and ioctlGetWinsize() to PosixLibrary.java**

In the `// ── Constants ──` section of `app-core/src/main/java/dev/mproctor/cccli/pty/PosixLibrary.java`, add after `TIOCSWINSZ`:

```java
/** ioctl(2) request for reading terminal window size — macOS AArch64 */
public static final long TIOCGWINSZ = 0x40087468L;
```

In the `// ── Public API ──` section, add after the existing `ioctl()` method:

```java
/**
 * Reads the terminal window size via TIOCGWINSZ.
 * Returns int[]{rows, cols} where [0]=rows and [1]=cols, or null if ioctl fails.
 * Requires an active subprocess holding the slave open — macOS returns zeroes
 * for disconnected PTYs (no subprocess).
 */
public static int[] ioctlGetWinsize(int fd) {
    try (Arena temp = Arena.ofConfined()) {
        // struct winsize { unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel; }
        MemorySegment winsize = temp.allocate(8);
        int ret = ioctl(fd, TIOCGWINSZ, winsize);
        if (ret != 0) return null;
        int rows = Short.toUnsignedInt(winsize.get(ValueLayout.JAVA_SHORT, 0));
        int cols = Short.toUnsignedInt(winsize.get(ValueLayout.JAVA_SHORT, 2));
        return new int[]{rows, cols};
    }
}
```

- [ ] **Run the three new tests and verify they pass**

```bash
cd app-core && mvn test -Dtest="PtyProcessTest#tiocgwinszReadsBackAfterResize+resizeCanBeCalledMultipleTimes+resizeBeforeOpenIsNoOp" -pl .
```

Expected: 3 tests PASS.

- [ ] **Commit**

```bash
git add app-core/src/main/java/dev/mproctor/cccli/pty/PosixLibrary.java \
        app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java
git commit -m "test: add TIOCGWINSZ read-back integration tests

Adds ioctlGetWinsize() to PosixLibrary and three tests:
tiocgwinszReadsBackAfterResize, resizeCanBeCalledMultipleTimes,
resizeBeforeOpenIsNoOp.

Refs #N"
```

---

## Task 3: Add tput Integration Tests

**Files:**
- Modify: `app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java`

These tests verify that a subprocess spawned on the PTY sees the terminal size set by `resize()`. They exercise the full kernel path: TIOCSWINSZ → slave device → subprocess reads TIOCGWINSZ. They should pass immediately since `PtyProcess.resize()` already calls TIOCSWINSZ.

- [ ] **Add tput tests** in the `// ── resize() ──` section of `PtyProcessTest.java`

```java
@Test
void tputColsReflectsResizeDimensions() throws Exception {
    pty.open();
    pty.resize(24, 100);

    CompletableFuture<String> received = new CompletableFuture<>();
    StringBuilder output = new StringBuilder();
    pty.startReader(text -> {
        output.append(text);
        if (!received.isDone()) received.complete(output.toString());
    });
    pty.spawn(new String[]{"/usr/bin/tput", "cols"});

    String result = received.get(3, TimeUnit.SECONDS);
    assertTrue(result.trim().contains("100"),
            "tput cols should report 100, got: " + result);
}

@Test
void tputLinesReflectsResizeDimensions() throws Exception {
    pty.open();
    pty.resize(30, 80);

    CompletableFuture<String> received = new CompletableFuture<>();
    StringBuilder output = new StringBuilder();
    pty.startReader(text -> {
        output.append(text);
        if (!received.isDone()) received.complete(output.toString());
    });
    pty.spawn(new String[]{"/usr/bin/tput", "lines"});

    String result = received.get(3, TimeUnit.SECONDS);
    assertTrue(result.trim().contains("30"),
            "tput lines should report 30, got: " + result);
}

@Test
void tputColsAfterMultipleResizes() throws Exception {
    pty.open();
    pty.resize(24, 80);
    pty.resize(50, 200);

    CompletableFuture<String> received = new CompletableFuture<>();
    StringBuilder output = new StringBuilder();
    pty.startReader(text -> {
        output.append(text);
        if (!received.isDone()) received.complete(output.toString());
    });
    pty.spawn(new String[]{"/usr/bin/tput", "cols"});

    String result = received.get(3, TimeUnit.SECONDS);
    assertTrue(result.trim().contains("200"),
            "tput cols should report last resize value 200, got: " + result);
}
```

- [ ] **Run all new tput tests**

```bash
cd app-core && mvn test -Dtest="PtyProcessTest#tputColsReflectsResizeDimensions+tputLinesReflectsResizeDimensions+tputColsAfterMultipleResizes" -pl .
```

Expected: 3 tests PASS. If any fail, the existing `PtyProcess.resize()` has a bug — debug before continuing.

- [ ] **Run the full app-core test suite to verify no regressions**

```bash
cd app-core && mvn test
```

Expected: all tests PASS (currently 51 total + 6 new = 57).

- [ ] **Commit**

```bash
git add app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java
git commit -m "test: add tput-based PTY resize integration tests

Verifies subprocesses see correct terminal size via tput cols/lines.
Exercises the full TIOCSWINSZ → slave → subprocess TIOCGWINSZ path.

Refs #N"
```

---

## Task 4: Update index.html

**Files:**
- Modify: `mac-ui-bridge/resources/xterm/index.html`

- [ ] **Replace the entire contents of index.html**

Write `mac-ui-bridge/resources/xterm/index.html`:

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    html, body { width: 100%; height: 100%; background: #1e1e1e; overflow: hidden; }
    #terminal { width: 100%; height: 100%; }
  </style>
  <link rel="stylesheet" href="xterm.css">
</head>
<body>
  <div id="terminal"></div>
  <script src="xterm.js"></script>
  <script src="xterm-addon-fit.js"></script>
  <script>
    window.term = new Terminal({
      theme: {
        background:    '#1e1e1e',
        foreground:    '#d4d4d4',
        cursor:        '#d4d4d4',
        black:         '#000000', red:         '#cd3131',
        green:         '#0dbc79', yellow:      '#e5e510',
        blue:          '#2472c8', magenta:     '#bc3fbc',
        cyan:          '#11a8cd', white:       '#e5e5e5',
        brightBlack:   '#666666', brightRed:   '#f14c4c',
        brightGreen:   '#23d18b', brightYellow:'#f5f543',
        brightBlue:    '#3b8eea', brightMagenta:'#d670d6',
        brightCyan:    '#29b8db', brightWhite: '#e5e5e5'
      },
      fontFamily: 'Menlo, Monaco, "Courier New", monospace',
      fontSize: 13,
      cursorBlink: true,
      scrollback: 5000
    });
    window.fitAddon = new FitAddon.FitAddon();
    window.term.loadAddon(window.fitAddon);
    window.term.open(document.getElementById('terminal'));
    window.term.onResize(function(evt) {
      if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.termSize) {
        window.webkit.messageHandlers.termSize.postMessage({ cols: evt.cols, rows: evt.rows });
      }
    });
    window.fitAddon.fit();
  </script>
</body>
</html>
```

Key changes from the old version:
- Added `<script src="xterm-addon-fit.js"></script>`
- `window.fitAddon = new FitAddon.FitAddon()` (window scope, needed by Obj-C)
- `term.loadAddon(window.fitAddon)` before open
- `term.onResize(...)` posts `{cols, rows}` to WKScriptMessageHandler
- Removed hardcoded `window.term.resize(120, 24)`
- `window.fitAddon.fit()` instead

- [ ] **Commit**

```bash
git add mac-ui-bridge/resources/xterm/index.html
git commit -m "feat: integrate FitAddon into xterm.js page

Adds term.onResize handler that posts {cols, rows} via
WKScriptMessageHandler. Removes hardcoded 120x24 resize.
fitAddon.fit() sets initial size from container.

Refs #N"
```

---

## Task 5: Obj-C Changes — MyMacUI.h + MyMacUI.m

**Files:**
- Modify: `mac-ui-bridge/include/MyMacUI.h`
- Modify: `mac-ui-bridge/src/MyMacUI.m`

- [ ] **Add WindowResizedCallback to MyMacUI.h**

In `mac-ui-bridge/include/MyMacUI.h`, add after the `StopClickedCallback` typedef (line ~19) and before `myui_init_application`:

```c
/** Fired when xterm.js reports a new terminal grid size (after window resize or initial fit).
 *  cols and rows are the new character grid dimensions. */
typedef void (*WindowResizedCallback)(int cols, int rows);

/** Register the callback invoked when xterm.js posts a termSize message via WKScriptMessageHandler.
 *  Thread-safe. Call before myui_start(). NULL to unregister. */
void myui_set_resize_callback(WindowResizedCallback cb);
```

- [ ] **Update MyMacUI.m** — add module-level static and adopt WKScriptMessageHandler

Near the top of `mac-ui-bridge/src/MyMacUI.m`, in the `// ── Module-level state ──` section (after `pendingInitialText`), add:

```objc
static WindowResizedCallback resizedCallback = NULL;  /* registered via myui_set_resize_callback */
```

- [ ] **Update CCCAppDelegate @interface** to adopt WKScriptMessageHandler

Change the existing `@interface CCCAppDelegate` line from:

```objc
@interface CCCAppDelegate : NSObject
    <NSApplicationDelegate, NSWindowDelegate, WKNavigationDelegate>
```

to:

```objc
@interface CCCAppDelegate : NSObject
    <NSApplicationDelegate, NSWindowDelegate, WKNavigationDelegate, WKScriptMessageHandler>
```

- [ ] **Add windowDidResize: to CCCAppDelegate @implementation**

In `MyMacUI.m`, inside `@implementation CCCAppDelegate`, add after `windowWillClose:`:

```objc
- (void)windowDidResize:(NSNotification *)notification {
    /* Already on AppKit main thread. Guard: only when WKWebView is ready.
     * requestAnimationFrame defers fit() until after WebView reflows,
     * avoiding a stale offsetWidth/Height read. */
    if (pageReady && theWebView) {
        [theWebView evaluateJavaScript:@"requestAnimationFrame(()=>window.fitAddon.fit())"
                     completionHandler:nil];
    }
}
```

- [ ] **Add userContentController:didReceiveScriptMessage: to CCCAppDelegate @implementation**

In `MyMacUI.m`, inside `@implementation CCCAppDelegate`, add after `windowDidResize:`:

```objc
/* WKScriptMessageHandler — receives {cols, rows} from term.onResize in JS */
- (void)userContentController:(WKUserContentController *)userContentController
      didReceiveScriptMessage:(WKScriptMessage *)message {
    if ([message.name isEqualToString:@"termSize"]) {
        NSDictionary *body = message.body;
        int cols = [body[@"cols"] intValue];
        int rows = [body[@"rows"] intValue];
        WindowResizedCallback cb = resizedCallback;
        if (cb) cb(cols, rows);
    }
}
```

- [ ] **Update setupUI to wire WKUserContentController**

In `setupUI`, find the existing WKWebView branch (the `if (myui_is_bundle())` block). Replace the `WKWebViewConfiguration *config = ...` and `WKWebView *webView = ...` block with:

```objc
/* Production: WKWebView + xterm.js */
WKUserContentController *controller = [[WKUserContentController alloc] init];
[controller addScriptMessageHandler:appDelegate name:@"termSize"];

WKWebViewConfiguration *config = [[WKWebViewConfiguration alloc] init];
config.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];
config.userContentController = controller;

WKWebView *webView = [[WKWebView alloc] initWithFrame:outputRect
                                        configuration:config];
webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
webView.navigationDelegate = appDelegate;
[root addSubview:webView];
theWebView = webView;
```

- [ ] **Update didFinishNavigation: to trigger initial fit**

In `CCCAppDelegate.webView:didFinishNavigation:`, add a `fitAddon.fit()` call after flushing pending output. The updated method should look like:

```objc
- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    pageReady = YES;
    if (pendingInitialText) {
        doWebViewWrite(pendingInitialText);
        pendingInitialText = nil;
    }
    for (NSString *str in pendingOutput) {
        doWebViewWrite(str);
    }
    pendingOutput = nil;
    /* Initial fit — sets PTY size to match actual window via term.onResize → WindowResizedCallback */
    [theWebView evaluateJavaScript:@"window.fitAddon.fit()" completionHandler:nil];
}
```

- [ ] **Add myui_set_resize_callback implementation** at the bottom of the `// ── C ABI implementation ──` section in `MyMacUI.m`, after `myui_evaluate_javascript`:

```objc
void myui_set_resize_callback(WindowResizedCallback cb) {
    resizedCallback = cb;
}
```

- [ ] **Build the dylib to verify Obj-C compiles**

```bash
cd mac-ui-bridge && make clean && make
```

Expected: `build/libMyMacUI.dylib` created with no errors. If you see errors about `WKScriptMessageHandler` — ensure `<WebKit/WebKit.h>` is imported (it already is in the existing file).

- [ ] **Commit**

```bash
git add mac-ui-bridge/include/MyMacUI.h mac-ui-bridge/src/MyMacUI.m
git commit -m "feat: add WKScriptMessageHandler resize callback to Obj-C bridge

windowDidResize: evaluates fitAddon.fit() via requestAnimationFrame.
userContentController:didReceiveScriptMessage: fires WindowResizedCallback.
myui_set_resize_callback() registers the C callback.
didFinishNavigation: triggers initial fit after page loads.

Refs #N"
```

---

## Task 6: Add myui_set_resize_callback Downcall to MyMacUI_h.java

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java`

This is jextract-generated code. Add the binding by hand following the exact pattern of the existing functions (e.g. `myui_init_application` for void→void, `myui_evaluate_javascript` for void→void* for reference).

- [ ] **Add the downcall binding** at the end of `MyMacUI_h.java`, just before the final closing `}`

```java
private static class myui_set_resize_callback {
    public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(
        MyMacUI_h.C_POINTER   /* WindowResizedCallback cb */
    );

    public static final MemorySegment ADDR =
        MyMacUI_h.findOrThrow("myui_set_resize_callback");

    public static final MethodHandle HANDLE =
        Linker.nativeLinker().downcallHandle(ADDR, DESC);
}

/**
 * {@snippet lang=c :
 * void myui_set_resize_callback(WindowResizedCallback cb)
 * }
 */
public static FunctionDescriptor myui_set_resize_callback$descriptor() {
    return myui_set_resize_callback.DESC;
}

/**
 * {@snippet lang=c :
 * void myui_set_resize_callback(WindowResizedCallback cb)
 * }
 */
public static MethodHandle myui_set_resize_callback$handle() {
    return myui_set_resize_callback.HANDLE;
}

/**
 * {@snippet lang=c :
 * void myui_set_resize_callback(WindowResizedCallback cb)
 * }
 */
public static void myui_set_resize_callback(MemorySegment cb) {
    var mh$ = myui_set_resize_callback.HANDLE;
    try {
        if (TRACE_DOWNCALLS) {
            traceDowncall("myui_set_resize_callback", cb);
        }
        mh$.invokeExact(cb);
    } catch (Throwable ex$) {
        throw new AssertionError("should not reach here", ex$);
    }
}
```

- [ ] **Verify it compiles**

```bash
cd app-macos && mvn compile -q 2>&1 | grep -E "ERROR|error" | head -10
```

Expected: no errors.

- [ ] **Commit**

```bash
git add app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java
git commit -m "feat: add myui_set_resize_callback downcall binding

Follows existing jextract-generated pattern.

Refs #N"
```

---

## Task 7: Java Upcall Infrastructure (TDD)

**Files:**
- Modify: `app-macos/src/test/java/dev/mproctor/cccli/bridge/MacUIBridgeTest.java` (test first)
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java`
- Modify: `app-macos/src/main/resources/META-INF/native-image/reflect-config.json`
- Modify: `app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json`
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java`

- [ ] **Write the failing test** in `MacUIBridgeTest.java`

Add this test to `MacUIBridgeTest.java` after `isInBundle_returnsFalse_inJvmTestMode`:

```java
// ── setResizeCallback ──────────────────────────────────────────────────────────

@Test
void setResizeCallback_smokesWithDylib() {
    Path dylib = Path.of("../mac-ui-bridge/build/libMyMacUI.dylib").toAbsolutePath();
    assumeTrue(Files.exists(dylib), "dylib not built — skipping native binding test");
    System.load(dylib.toString());

    // Registering a no-op handler must not throw.
    // Verifies: Panama binding resolves myui_set_resize_callback symbol,
    //           upcall stub is allocated and passed to the dylib without error.
    assertDoesNotThrow(() -> bridge.setResizeCallback((cols, rows) -> { /* no-op */ }),
            "setResizeCallback() should not throw when dylib is loaded");
}
```

Also add the required import at the top of `MacUIBridgeTest.java`:

```java
import java.util.function.BiConsumer;
```

- [ ] **Verify test fails to compile** (setResizeCallback doesn't exist yet)

```bash
cd app-macos && mvn test-compile 2>&1 | grep "cannot find symbol\|error:" | head -5
```

Expected: error mentioning `setResizeCallback`.

- [ ] **Add createWindowResizedCallback to Callbacks.java**

In `Callbacks.java`:

1. Add import at the top: `import java.util.function.BiConsumer;`

2. Add the FunctionDescriptor constant in the constants block:

```java
private static final FunctionDescriptor VOID_INT_INT =
        FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
```

3. Add volatile handler field with the other handler fields:

```java
private static volatile BiConsumer<Integer, Integer> windowResizedHandler;
```

4. Add the factory method after `createStopClickedCallback`:

```java
/** Creates a void(*)(int cols, int rows) upcall stub for terminal resize notifications. */
public static MemorySegment createWindowResizedCallback(Arena arena,
                                                         BiConsumer<Integer, Integer> handler) {
    windowResizedHandler = handler;
    try {
        MethodHandle mh = MethodHandles.lookup()
                .findStatic(Callbacks.class, "onTerminalResized",
                        MethodType.methodType(void.class, int.class, int.class));
        return Linker.nativeLinker().upcallStub(mh, VOID_INT_INT, arena);
    } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException("Failed to create window-resized upcall stub", e);
    }
}
```

5. Add the static handler method after `onStopClicked`:

```java
/** Called from Objective-C when xterm.js reports a new terminal size. Registered in reflect-config.json. */
public static void onTerminalResized(int cols, int rows) {
    BiConsumer<Integer, Integer> handler = windowResizedHandler;
    if (handler != null) handler.accept(cols, rows);
}
```

- [ ] **Update reflect-config.json**

Replace the entire content of `app-macos/src/main/resources/META-INF/native-image/reflect-config.json`:

```json
[
  {
    "name": "dev.mproctor.cccli.bridge.Callbacks",
    "methods": [
      { "name": "onWindowClosed",    "parameterTypes": [] },
      { "name": "onTextSubmitted",   "parameterTypes": ["java.lang.foreign.MemorySegment"] },
      { "name": "onStopClicked",     "parameterTypes": [] },
      { "name": "onTerminalResized", "parameterTypes": ["int", "int"] }
    ]
  }
]
```

- [ ] **Update reachability-metadata.json**

In `app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json`, add to the `directUpcalls` array (after the `onStopClicked` entry):

```json
{
  "class": "dev.mproctor.cccli.bridge.Callbacks",
  "method": "onTerminalResized",
  "returnType": "void",
  "parameterTypes": ["jint", "jint"]
}
```

The `downcalls` array already contains `{"returnType": "void", "parameterTypes": ["void*"]}` which covers `myui_set_resize_callback` — no new downcall entry needed.

- [ ] **Add setResizeCallback to MacUIBridge.java**

In `MacUIBridge.java`, add the import at the top:

```java
import java.util.function.BiConsumer;
```

Add the method after `evaluateJavaScript`:

```java
/**
 * Register a callback invoked whenever xterm.js reports a terminal resize.
 * The callback receives (cols, rows) — note cols first, rows second.
 * Call before bridge.start() so the initial fit is captured.
 * Thread-safe — the ObjC bridge stores the C function pointer statically.
 */
public void setResizeCallback(BiConsumer<Integer, Integer> onResized) {
    MemorySegment cb = Callbacks.createWindowResizedCallback(arena, onResized);
    MyMacUI_h.myui_set_resize_callback(cb);
}
```

- [ ] **Run the smoke test**

```bash
cd app-macos && mvn test -Dtest="MacUIBridgeTest#setResizeCallback_smokesWithDylib" -pl .
```

Expected: PASS (or SKIPPED if dylib not built — build it first with `cd mac-ui-bridge && make`).

- [ ] **Run the full app-macos test suite**

```bash
cd app-macos && mvn test
```

Expected: all tests PASS.

- [ ] **Commit**

```bash
git add \
  app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java \
  app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java \
  app-macos/src/main/resources/META-INF/native-image/reflect-config.json \
  app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json \
  app-macos/src/test/java/dev/mproctor/cccli/bridge/MacUIBridgeTest.java
git commit -m "feat: add WindowResizedCallback upcall infrastructure

Callbacks.createWindowResizedCallback + onTerminalResized static handler.
MacUIBridge.setResizeCallback() wires the C callback.
GraalVM metadata updated for native image.

Refs #N"
```

---

## Task 8: Wire Main.java and Run Full Test Suite

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/Main.java`

- [ ] **Update Main.java**

In `app-macos/src/main/java/dev/mproctor/cccli/Main.java`:

1. Remove the line `pty.resize(24, 120);` (FitAddon drives the initial size now via `didFinishNavigation:` → `fitAddon.fit()` → `term.onResize` → callback).

2. After `pty.spawn(...)` and before `bridge.start(...)`, add:

```java
// Resize callback: fired by WKScriptMessageHandler when xterm.js reports a new grid size.
// Note: FitAddon reports (cols, rows), but pty.resize takes (rows, cols).
bridge.setResizeCallback((cols, rows) -> {
    Log.debugf("Terminal resized: %d cols × %d rows", cols, rows);
    pty.resize(rows, cols);
});
```

The `run()` method body should now look like:

```java
PtyProcess pty = new PtyProcess();
pty.open();
pty.spawn(new String[]{claudePath.toString()});

InteractionDetector detector = new InteractionDetector(
        state -> bridge.setPassiveMode(state == ClaudeState.PASSIVE));

pty.startReader(text -> {
    detector.onOutput();
    bridge.appendOutput(text);
});

bridge.setResizeCallback((cols, rows) -> {
    Log.debugf("Terminal resized: %d cols × %d rows", cols, rows);
    pty.resize(rows, cols);
});

Log.info("Starting Claude Desktop CLI...");
bridge.start("Claude Desktop CLI", 900, 600,
        "Connecting to Claude...\n",
        () -> { ... },
        text -> { ... },
        () -> { ... });
```

- [ ] **Verify app-macos compiles**

```bash
cd app-macos && mvn compile -q 2>&1 | grep -E "ERROR|error" | head -10
```

Expected: no errors.

- [ ] **Run the complete test suite from the root**

```bash
mvn test
```

Expected: all tests PASS. The current baseline is 51 tests; after this plan the total should be 58 (51 + 6 PTY tests + 1 bridge smoke test).

- [ ] **Commit**

```bash
git add app-macos/src/main/java/dev/mproctor/cccli/Main.java
git commit -m "feat: wire resize callback in Main — PTY tracks xterm.js grid

Removes hardcoded pty.resize(24,120). FitAddon drives initial size
via didFinishNavigation: fit(). Every window resize now updates both
xterm.js (via fitAddon.fit) and the PTY kernel state (TIOCSWINSZ).

Closes #N"
```

---

## Self-Review Checklist

After all tasks are complete, verify:

- [ ] `mvn test` from root — all 58 tests pass
- [ ] `cd mac-ui-bridge && make clean && make` — dylib builds cleanly
- [ ] `mac-ui-bridge/resources/xterm/xterm-addon-fit.js` exists
- [ ] `index.html` has no `term.resize(120, 24)` (removed)
- [ ] `Main.java` has no `pty.resize(24, 120)` (removed)
- [ ] Every commit references `#N`
