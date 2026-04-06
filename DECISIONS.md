# Architectural Decisions Log

A running record of significant architectural choices, what was considered, and why each decision was made. Updated as the project evolves.

---

## ADR-001: UI Framework — Objective-C Bridge over JavaFX / SWT / Swift

**Date:** 2026-04-03
**Status:** Decided

### Context
The app requires a native macOS UI. The developer codes in Java.

### Options Considered

| Option | Verdict | Reason |
|--------|---------|--------|
| JavaFX | Rejected | Renders its own widgets; not AppKit. Text input is not NSTextView. Users notice. |
| SWT | Rejected | Wraps native controls (AppKit on macOS) but uses JNA internally. JNA's runtime reflection is incompatible with GraalVM native image. |
| Swift + AppKit | Rejected | Truly native, but developer cannot code in Swift. |
| Java + hand-written JNI to AppKit | Rejected | Possible but produces unmaintainable C/ObjC glue code. |
| Objective-C bridge (MyMacUI.dylib) via Panama FFM | **Chosen** | Clean C ABI callable from Java via Panama. Minimal ObjC to maintain. GraalVM native-image-safe. |

### Decision
A minimal, purpose-built Objective-C bridge (`MyMacUI.dylib`) exposing only the AppKit primitives this app needs. Java calls it via Panama FFM API. Objective-C chosen over Swift because Panama's `jextract` generates bindings from C headers cleanly; Swift's ABI is unstable and name-mangled.

---

## ADR-002: Terminal Pane — WKWebView + xterm.js

**Date:** 2026-04-03
**Status:** Decided

### Context
AppKit has no built-in terminal emulator widget. The app needs full VT100/xterm rendering of Claude Code output.

### Options Considered

| Option | Verdict | Reason |
|--------|---------|--------|
| Custom NSView VT100 implementation | Rejected | Enormous scope; contradicts minimal bridge principle. |
| SwiftTerm (Swift terminal emulator) | Rejected | Swift only; can't use from Java/ObjC bridge. |
| JediTerm in SwingNode (JavaFX) | Rejected | Requires JavaFX; Retina/focus issues with SwingNode. |
| WKWebView + xterm.js | **Chosen** | WebKit is native-quality rendering on macOS. xterm.js has near-perfect ANSI/VT100 compatibility. Used by VS Code, GitHub Codespaces. Bridge exposes WKWebView creation + JS evaluation only — stays minimal. |

### Decision
Terminal pane is a WKWebView hosting xterm.js. Java writes PTY bytes to xterm.js via `evaluateJavaScript("window.term.write(...)")`. The Objective-C bridge exposes WKWebView creation and a JS evaluation method.

---

## ADR-003: PTY Management — Panama FFM over pty4j

**Date:** 2026-04-03
**Status:** Decided

### Context
The app spawns `claude` as a PTY subprocess and reads/writes its byte stream. PTY management requires native POSIX system calls.

### Options Considered

| Option | Verdict | Reason |
|--------|---------|--------|
| pty4j (JetBrains) | Rejected | Uses JNA under the hood. JNA's runtime reflection is incompatible with GraalVM native image. Creates a dependency that would be hard to remove later. |
| Panama FFM → POSIX libc | **Chosen** | Same mechanism already used for the AppKit bridge. All calls visible to GraalVM static analyser at build time. No additional dependencies. |

### Decision
PTY management implemented in `app-core` using Panama FFM downcalls to POSIX libc directly: `posix_openpt`, `grantpt`, `unlockpt`, `ptsname`, `posix_spawn`, `ioctl(TIOCSWINSZ)`, `read`, `write`.

`posix_spawn` used instead of `fork()` for process creation — calling `fork()` directly inside a GraalVM native image is unsafe as the child inherits parent heap state mid-initialisation.

---

## ADR-004: Java Runtime — Quarkus Native (GraalVM/Mandrel)

**Date:** 2026-04-03
**Status:** Decided

### Context
The app needs to ship as a fast, self-contained macOS `.app` bundle. No bundled JRE.

### Options Considered

| Option | Verdict | Reason |
|--------|---------|--------|
| JavaFX + GraalVM + Gluon Client | Rejected | Rejected JavaFX as UI framework (ADR-001). |
| Plain GraalVM native image | Considered | Valid, but more manual build toolchain configuration. |
| Quarkus Native (GraalVM/Mandrel) | **Chosen** | Simplifies native image build toolchain. CDI for dependency injection. Developer already knows Quarkus. REST/service features unused but no cost to ignoring them. |

### Decision
Quarkus Native compiles the Java application to a standalone native binary. `app-macos` is the Quarkus module that wires everything together and produces the `.app` bundle.

---

## ADR-005: Objective-C over Swift for the Bridge

**Date:** 2026-04-03
**Status:** Decided

### Context
The native bridge dylib must be callable from Java via Panama FFM. Both Objective-C and Swift can call AppKit.

### Options Considered

| Option | Verdict | Reason |
|--------|---------|--------|
| Swift | Rejected | Swift ABI is unstable and name-mangled. `jextract` generates bindings from C headers; Swift headers require extra tooling and produce fragile bindings. Higher risk with GraalVM native image upcalls. |
| Objective-C | **Chosen** | Exposes a clean C ABI naturally. `jextract` works directly from the `.h` header. Stable, reliable upcall support under GraalVM. |

### Decision
`MyMacUI.dylib` written in Objective-C. C ABI exposed via standard `extern "C"` function signatures. Swift can be introduced later for specific components if desired, but the bridge ABI stays C-compatible.

---

## Open Architectural Questions (not yet decided)

1. **PTY bytes → xterm.js encoding** — define chunking and encoding contract (UTF-8 string vs Uint8Array) between Java read loop and WKWebView JS evaluation.
2. **posix_spawn + controlling terminal** — validate that `TIOCSCTTY` is correctly established for the `claude` child process on macOS without `fork()`.
3. **Upcall thread model** — AppKit callbacks on main thread vs Quarkus worker threads; define handoff contract at bridge boundary.
4. **Slash command overlay** — transparent NSView over split pane vs popup anchored in NSSplitView. Deferred to implementation.
5. **`claude` binary resolution** — login-shell resolution (`/bin/zsh -l -c 'which claude'`) with file picker fallback. Validate on non-Homebrew installs.

---

## Known Limitation: Single-window delegate callback

**Date:** 2026-04-03
**Status:** Accepted for Phase 1

`CCCAppDelegate` stores `onClosed` as a single property. If `myui_create_window()` is called more than once, the second call overwrites the first window's callback. Acceptable for Phase 1 (single window). Fix in a later phase by storing callbacks per-window handle (e.g., an NSDictionary keyed by NSWindow pointer).

---

## ADR-006: Main Thread Dispatch — myui_start() via GCD

**Date:** 2026-04-03
**Status:** Decided (forced by runtime failure)

### Context

`@QuarkusMain.run()` is called on a Quarkus worker thread, not the OS main thread. AppKit requires all UI operations — including NSWindow creation — on the main thread. First run crashed with:
`NSWindow should only be instantiated on the main thread!`

### Decision

Added `myui_start()` to the Objective-C bridge. It:
1. Copies the title string to the heap (caller's stack may be freed before the block runs)
2. Dispatches init + window creation + `[NSApp run]` to the main thread via `dispatch_async(dispatch_get_main_queue(), ...)`
3. Blocks the calling (Quarkus) thread on a `dispatch_semaphore_t` until `[NSApp run]` returns

`Main.java` now calls `bridge.start()` (single call) instead of the previous three-call sequence (`initApplication` + `createWindow` + `run`).

### Why not fix it on the Java side?

The Java side has no access to GCD. The ObjC bridge is the right place to own threading concerns — Java callers should not need to know AppKit's threading rules.

---

## ADR-007: myui_start thread detection — CFRunLoopRun vs semaphore

**Date:** 2026-04-04
**Status:** Decided (forced by native image behaviour)

### Context

In JVM mode, Quarkus calls `@QuarkusMain.run()` on a **worker thread** — the OS main thread is free to drain the GCD main queue. The `dispatch_async` + semaphore approach worked.

In GraalVM native image, Quarkus calls `run()` **synchronously on the OS main thread**. Calling `dispatch_async(main_queue)` then `dispatch_semaphore_wait` on the main thread is a deadlock: the semaphore blocks the main thread, so the queued block never executes.

### Decision

`myui_start()` checks `[NSThread isMainThread]` at runtime and branches:

- **Main thread (native image):** queue AppKit setup via `dispatch_async`, then call `CFRunLoopRun()` to drain the queue. `CFRunLoopStop` is called after `[NSApp run]` returns.
- **Worker thread (JVM mode):** original `dispatch_async` + `dispatch_semaphore_wait` path.

### Validated

Both modes confirmed working:
- JVM: window + upcall ✅
- Native image: window + upcall ✅, startup in **0.017s**

---

## ADR-008: GCD main queue blocked by [NSApp run] inside dispatch_async

**Date:** 2026-04-04
**Status:** Confirmed (discovered during Plan 2)

### Finding

`dispatch_async(dispatch_get_main_queue(), block)` and `dispatch_after(...)` blocks **cannot execute** while `[NSApp run]` is itself running inside a `dispatch_async` block. GCD serializes the main queue — it will not dispatch new blocks onto it while a block is still executing (and `[NSApp run]` never returns until the app terminates, so from GCD's view the outer block is still running).

AppKit events (user input, window actions) ARE processed normally because `[NSApp run]` pumps the CFRunLoop directly, bypassing GCD.

### Consequences

- `myui_append_output` must update NSTextView **synchronously** when called from the main thread (AppKit upcall context), not via `dispatch_async`.
- Post-startup initialization that needs to run after the run loop starts must use **AppKit delegate methods** (`windowDidBecomeKey:`, `applicationDidFinishLaunching:`, etc.) — not `dispatch_async` or `dispatch_after`.
- For background-thread callers, `dispatch_async(main_queue, ...)` works correctly.

---

## ADR-009: NSTextView in NSSplitView (as contentView) blocks keyboard events

**Date:** 2026-04-04
**Status:** Confirmed (Plan 2)

Replacing `window.contentView` with an `NSSplitView` breaks keyboard event routing to all subviews. Clicks also fail to reach NSTextView/NSTextField inside the split view. The fix: **never replace contentView** — add all views directly to the existing `window.contentView` using `[contentView addSubview:]`.

---

## ADR-010: WKWebView subprocess fails in non-bundle JVM process

**Date:** 2026-04-04
**Status:** Confirmed (Plan 2)

WKWebView spawns a separate `WebContent` subprocess. In a JVM process launched from terminal (without a proper `.app` bundle), the IPC between the host process and `WebContent` silently fails. `loadHTMLString:` and `evaluateJavaScript:` appear to succeed but no content renders and the `WKNavigationDelegate` never fires.

**Decision:** Use NSTextView for the output pane in development (JVM) mode. WKWebView + xterm.js will be used in production inside a proper `.app` bundle (Plan 3+).

---

## ADR-011: NSTextField empty-field cursor blink bug (AppKit)

**Date:** 2026-04-04
**Status:** Confirmed (Plan 2)

AppKit does not start the insertion-point blink timer for an empty NSTextField that gains first responder status programmatically. The cursor is invisible until text is typed and Enter is pressed (which transitions through a non-empty state). 

**Fix:** In `windowDidBecomeKey:`, after `makeFirstResponder:`, perform a no-op string assignment:
```objc
[inputField setStringValue:@" "];
[inputField setStringValue:@""];
```
This must be done inside an AppKit delegate method (see ADR-008 — GCD dispatch is not available in this setup).

---

## ADR-012: performSelectorOnMainThread: for all non-main-thread UI updates

**Date:** 2026-04-05
**Status:** Decided (Plan 3 — PTY integration)

### Context

ADR-008 established that `dispatch_async(main_queue, ...)` silently drops while `[NSApp run]` is inside a `dispatch_async` block, and that AppKit upcalls on the main thread must update synchronously. Plan 3 introduced a new non-main-thread caller: the PTY reader daemon thread, which calls `myui_append_output()` to stream Claude's output to the display.

### Problem

The PTY reader thread is not an AppKit upcall — it's a plain Java daemon thread. The existing `myui_append_output()` implementation checked `[NSThread isMainThread]` and used `dispatch_async` for the non-main-thread path. Output from the PTY reader silently disappeared.

### Decision

Replace `dispatch_async(dispatch_get_main_queue(), ...)` with `performSelectorOnMainThread:withObject:waitUntilDone:NO` throughout `MyMacUI.m` for all non-main-thread callers. `performSelectorOnMainThread:` schedules on NSRunLoop directly — it is not affected by GCD main queue serialisation.

The same approach applies to `myui_set_passive_mode()` which is called from the `InteractionDetector` scheduler thread.

### Rule

Any function in `MyMacUI.m` that may be called from a non-main thread must use `performSelectorOnMainThread:withObject:waitUntilDone:NO`, never `dispatch_async(main_queue, ...)`.

---

## ADR-013: PTY line discipline ECHO disabled via tcgetattr/tcsetattr

**Date:** 2026-04-05
**Status:** Decided (Plan 3 — PTY integration)

### Context

After opening a PTY master/slave pair and spawning the `claude` subprocess, the PTY reader thread received every line of output twice.

### Root Cause

The PTY line discipline has `ECHO` enabled by default. When the parent writes bytes to the master fd (simulating keyboard input), the line discipline echoes those bytes back to the master — before the subprocess even reads them. The subprocess then writes its response to the slave stdout, which also arrives at the master. Result: two copies of every line.

### Decision

After `posix_openpt`, call `tcgetattr(masterFd, &termios)`, clear the `ECHO` bit in `c_lflag` (`0x00000008`), and call `tcsetattr(masterFd, TCSANOW, &termios)`.

On macOS AArch64: `tcflag_t` is `unsigned long` (8 bytes). `c_lflag` is the 4th field in `struct termios`, at byte offset 24.

### Why Not Filter in Software

Filtering duplicate lines in the reader would require maintaining state across chunks and would be fragile (timing-dependent, prone to false positives with repeated content). Disabling ECHO at the PTY level is the canonical POSIX solution.

---

## ADR-014: InteractionDetector — timer-based mode transitions

**Date:** 2026-04-05
**Status:** Decided (Plan 5 — passive mode)

### Context

The app needs to know when Claude is generating (PASSIVE mode: block input) vs waiting for input (FREE_TEXT mode: enable input). The PTY byte stream provides no explicit signal — Claude Code's TUI output is rich with ANSI sequences and no clean "done" marker.

### Options Considered

| Option | Verdict | Reason |
|--------|---------|--------|
| Pattern-match on Claude's prompt text | Rejected | Claude Code's output format changes across versions; brittle to maintain |
| Semantic parsing of ANSI sequences | Rejected | Significant complexity; xterm.js handles this in production — premature here |
| Timeout-based: quiet period → FREE_TEXT | **Chosen** | Simple, reliable, format-agnostic; works regardless of Claude's TUI implementation |

### Decision

`InteractionDetector` in `app-core`:
- Any PTY output → PASSIVE (disable input, show Stop button)
- 800ms of silence → FREE_TEXT (enable input, hide Stop button)
- User submit → PASSIVE immediately (before PTY output arrives)
- Stop button / window close → FREE_TEXT immediately via `forceIdle()`

The 800ms threshold balances responsiveness (re-enable promptly) against stability (don't flicker during Claude's mid-response pauses).

---

## ADR-015: AnsiStripper as dev-only NSTextView bridge to xterm.js

**Date:** 2026-04-05
**Status:** Decided (Plan 4 — wire to claude)

### Context

Claude Code outputs extensive ANSI/VT100 escape sequences — colours, cursor movement, progress spinners, OSC sequences. NSTextView has no terminal emulation; these sequences render as literal characters, making output unreadable.

### Options Considered

| Option | Verdict | Reason |
|--------|---------|--------|
| Display raw bytes | Rejected | Completely unreadable in NSTextView |
| Full VT100 parser in Java | Rejected | Premature — xterm.js handles this in production; implementing it in Java duplicates work |
| Regex stripping (AnsiStripper) | **Chosen** | Minimal, removes the visible garbage, readable text; explicitly dev-only |

### Decision

`AnsiStripper` strips four escape sequence types (CSI, OSC, single-char VT100, bare CR) before passing text to `bridge.appendOutput()`. It is explicitly marked as a development workaround — it is removed when Plan 5b replaces NSTextView with WKWebView + xterm.js.

The `AnsiStripper` pattern (`[A-Za-z0-9=>?]` for single-char escapes) is intentionally narrow to avoid stripping valid non-ANSI content.

---

## ADR-016: WKWebView works without entitlements under ad-hoc signing

**Date:** 2026-04-06

**Context:** Plan 5b adds a WKWebView terminal pane rendering xterm.js. WKWebView spawns a
separate `WebContent` renderer process via XPC IPC. On macOS this can require specific
entitlements in the code signature, particularly when using hardened runtime
(`--options runtime`).

**Decision:** No entitlements file is needed when signing with `codesign --sign -` (ad-hoc)
without `--options runtime`. WKWebView's WebContent process spawns and renders correctly.
`bundle.sh` keeps its current `codesign --sign - --force --deep` invocation unchanged.

**Evidence:** Tested on macOS 15.x (Darwin 25.4.0), Apple Silicon (AArch64), native
Quarkus image. App launched in bundle mode, WebContent XPC process spawned (confirmed via
`ps`), no WebKit or sandbox errors in system log, app stable for 10+ seconds.

**Consequences:** If hardened runtime is added in future (e.g. for notarisation), add
`com.apple.security.cs.allow-jit` to an entitlements plist and pass it to `codesign`
via `--entitlements`. Without this flag, WebKit's JIT compiler is blocked under hardened
runtime and xterm.js will run in interpreter mode or fail entirely.
