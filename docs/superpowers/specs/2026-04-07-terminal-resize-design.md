# Design: Terminal Resize
**Date:** 2026-04-07
**Status:** Approved

## Problem

The PTY and xterm.js terminal are initialised at a hardcoded 120×24. When the user resizes the window, neither the PTY (kernel) nor xterm.js updates. Subprocesses format output for the wrong width; xterm.js may clip or reflow incorrectly.

## Goals

- PTY window size (`TIOCSWINSZ`) tracks the actual xterm.js grid on every window resize.
- xterm.js grid tracks the actual WKWebView pixel size (via FitAddon).
- Hardcoded `resize(24, 120)` in `Main.java` and `term.resize(120, 24)` in `index.html` are removed; FitAddon drives the initial size at page-ready time.
- Lots of integration tests that verify kernel-level correctness.

## Non-Goals

- Dev mode (NSTextView path). Resize only applies to the WKWebView / bundle path.
- Pixel-perfect character-cell computation in Java or Obj-C. xterm.js / FitAddon owns that.

## Data Flow

```
Window resize (AppKit)
  → windowDidResize: (CCCAppDelegate)
    → evaluateJS: "fitAddon.fit()"
      → FitAddon measures rendered char cell, computes exact cols/rows
        → term.onResize fires
          → JS: webkit.messageHandlers.termSize.postMessage({cols, rows})
            → userContentController:didReceiveScriptMessage: (CCCAppDelegate)
              → registered WindowResizedCallback(cols, rows)   ← C function pointer
                → Java pty.resize(rows, cols)
                   (ioctl TIOCSWINSZ → kernel → subprocess receives SIGWINCH)
```

xterm.js both computes and consumes the resize in one JS call. Java receives the resulting (cols, rows) and tells the PTY. No pixel math in Java or Obj-C.

## Why FitAddon (not manual math)

- **vs manual JS math** — FitAddon measures the actual rendered character cell at runtime. Manual math using `_core._renderService.dimensions` is an internal xterm.js API that can break on any upgrade.
- **vs Java pixel math** — requires hardcoding font metrics (Menlo 13px ≈ 7.8×15px). Breaks silently if font or DPI ever changes.

## Components

### New files

| File | Purpose |
|------|---------|
| `mac-ui-bridge/resources/xterm/xterm-addon-fit.js` | FitAddon bundle, downloaded from npm (`xterm-addon-fit`) at same version as xterm.js |

### Modified: `index.html`

- Load `xterm-addon-fit.js` after `xterm.js`.
- Instantiate `window.fitAddon = new FitAddon.FitAddon()` and `term.loadAddon(window.fitAddon)`.
- Assign `window.fitAddon = new FitAddon.FitAddon()` (window scope so Obj-C can reach it) and `term.loadAddon(window.fitAddon)`.
- After `term.open(...)`: call `window.fitAddon.fit()` (sets initial grid from container size).
- Remove hardcoded `term.resize(120, 24)`.
- Add `term.onResize(({cols, rows}) => window.webkit.messageHandlers.termSize.postMessage({cols: cols, rows: rows}))`.
- Obj-C evaluates `"window.fitAddon.fit()"` to trigger a refit.

### Modified: `MyMacUI.h`

```c
/** Fired when the terminal grid dimensions change (window resize or initial fit). */
typedef void (*WindowResizedCallback)(int cols, int rows);

/** Register the callback invoked when xterm.js reports a new terminal size.
 *  Thread-safe — must be called before myui_start(). */
void myui_set_resize_callback(WindowResizedCallback cb);
```

### Modified: `MyMacUI.m`

**`setupUI`** (WKWebView branch only):
- Create `WKUserContentController`, add `CCCAppDelegate` as script message handler for name `"termSize"`.
- Attach controller to `WKWebViewConfiguration` before creating `WKWebView`.

**`CCCAppDelegate`**:
- Adopt `WKScriptMessageHandler` protocol.
- `userContentController:didReceiveScriptMessage:` — extract `cols`/`rows` from message body dict, call registered `WindowResizedCallback`.
- `windowDidResize:` — if `pageReady && theWebView`, evaluate JS `"fitAddon.fit()"` via `[theWebView evaluateJavaScript:...]` directly (already on main thread from AppKit).

**`myui_set_resize_callback`**:
- Store callback in a module-level static `WindowResizedCallback resizedCallback = NULL`.

**`didFinishNavigation:`** (existing):
- After flushing pending output, evaluate `"fitAddon.fit()"` to set the initial size (triggers `term.onResize` → `WKScriptMessageHandler` → Java → PTY).

### New: `WindowResizedCallback.java`

Panama FFM upcall descriptor for `void (*)(int cols, int rows)` — follows the same pattern as `WindowClosedCallback.java` and `StopClickedCallback.java`.

### Modified: `Callbacks.java`

Add `createWindowResizedCallback(Arena, BiConsumer<Integer,Integer>)` factory — same pattern as `createWindowClosedCallback`.

### Modified: `MacUIBridge.java`

```java
public void setResizeCallback(BiConsumer<Integer, Integer> onResized) {
    MemorySegment cb = Callbacks.createWindowResizedCallback(arena, onResized);
    MyMacUI_h.myui_set_resize_callback(cb);
}
```

### Modified: `Main.java`

- Remove `pty.resize(24, 120)` (FitAddon drives the initial size).
- After `pty.open()` / `pty.spawn()`, before `bridge.start()`:
  ```java
  bridge.setResizeCallback((cols, rows) -> pty.resize(rows, cols));
  ```

## Testing

### `PosixLibrary` additions

Add `TIOCGWINSZ = 0x40087468L` constant and `ioctlGetWinsize(int masterFd)` wrapper that reads back the kernel's `struct winsize` and returns an `int[2]` where `[0]` = rows and `[1]` = cols. Used by tests to verify kernel state without spawning subprocesses.

### New integration tests in `PtyProcessTest`

All tests follow existing patterns (BeforeEach/AfterEach, CompletableFuture for async output).

| # | Test | What it proves |
|---|------|---------------|
| 1 | `tputColsReflectsResizeDimensions` | `resize(24,100)` before spawn; spawn `tput cols`; output contains `"100"`. Proves TIOCSWINSZ is visible to subprocesses via the slave. |
| 2 | `tputLinesReflectsResizeDimensions` | Same with `resize(30,80)`; spawn `tput lines`; output contains `"30"`. |
| 3 | `tiocgwinszReadsBackAfterResize` | Spawn cat; `resize(42,137)`; call `TIOCGWINSZ` via PosixLibrary; assert rows=42, cols=137. Verifies kernel state directly, no subprocess output parsing. |
| 4 | `resizeCanBeCalledMultipleTimes` | `resize(24,80)` then `resize(42,137)`; `TIOCGWINSZ` reads second values. Verifies no state corruption on repeated resizes. |
| 5 | `resizeBeforeOpenIsNoOp` | `resize(24,80)` without `open()` must not throw. Guards on `masterFd < 0`. |
| 6 | `tputColsAfterMultipleResizes` | Resize twice; spawn `tput cols`; verify final value. End-to-end: does the subprocess see the last resize? |

### New test in `MacUIBridgeTest`

| # | Test | What it proves |
|---|------|---------------|
| 7 | `setResizeCallback_smokesWithDylib` | Load dylib (assumed present); call `bridge.setResizeCallback(...)` without throwing. Gated with `assumeTrue(Files.exists(dylib))` — same pattern as `isInBundle_returnsFalse_inJvmTestMode`. |

### Existing tests preserved

`resizeDoesNotThrow` and `resizeIoctlSucceeds` remain; the new TIOCGWINSZ tests complement (not replace) them.

## Open Questions / Risks

- **FitAddon version** — must match the bundled xterm.js version. Confirm compatible version before downloading.
- **`windowDidResize:` timing** — fires before the WKWebView layout pass completes. FitAddon reads `offsetWidth/Height`, which may still reflect the old size if the WebView hasn't reflowed. Mitigation: evaluate `fitAddon.fit()` in a `requestAnimationFrame` callback from JS side, or accept that one resize event may be slightly stale (AppKit will fire again if the user is still dragging).
- **Dev mode** — `windowDidResize:` guard (`if pageReady && theWebView`) ensures no attempt to fit in NSTextView mode.
