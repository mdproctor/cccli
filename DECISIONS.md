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
