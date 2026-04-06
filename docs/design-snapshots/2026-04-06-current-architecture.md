# Claude Desktop CLI — Design Snapshot

**Date:** 2026-04-06
**Topic:** Architecture after Plans 1–5 (PTY layer, claude wiring, passive mode)
**Supersedes:** *(none — first snapshot)*
**Superseded by:** [2026-04-07-wkwebview-xterm-complete](2026-04-07-wkwebview-xterm-complete.md)

---

## Where We Are

The app runs as a GraalVM native binary (~0.020s startup). It spawns the `claude`
CLI in a PTY, routes ANSI-stripped output to an NSTextView, and accepts user input
via NSTextField. PASSIVE mode is implemented: input is disabled and a Stop button
appears while Claude is generating, reverting to FREE_TEXT when output has been
quiet for 800ms. The Objective-C bridge + Panama FFM + Quarkus Native architecture
has been validated across five plans with no architectural pivots.

---

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| UI framework | Objective-C bridge (MyMacUI.dylib) via Panama FFM | Clean C ABI, GraalVM native-image-safe, minimal ObjC | JavaFX (not AppKit), SWT (uses JNA), Swift (can't code it), JNI (unmaintainable) — ADR-001 |
| Terminal pane | NSTextView (dev) / WKWebView + xterm.js (prod) | WKWebView requires .app bundle; NSTextView works in JVM dev mode | Custom NSView VT100 (enormous scope) — ADR-002, ADR-010 |
| Input pane | NSTextField (single-line) | Native AppKit text field, spell-check, shortcuts | NSTextView multi-line (planned for v2) |
| PTY management | Panama FFM → POSIX libc directly | Same mechanism as AppKit bridge; GraalVM-safe; no extra deps | pty4j (uses JNA) — ADR-003 |
| Java runtime | Quarkus Native (GraalVM) | Fast startup, native binary, familiar toolchain | Plain GraalVM, Spring Native — ADR-004 |
| Bridge language | Objective-C | Stable C ABI; jextract works from .h headers directly | Swift (unstable ABI, name-mangled) — ADR-005 |
| Main thread dispatch | myui_start() with runtime isMainThread branch | JVM uses worker thread; native image calls run() on main thread | Single fixed strategy (deadlocks in one mode) — ADR-006, ADR-007 |
| Background thread → UI | performSelectorOnMainThread: (not dispatch_async) | dispatch_async silently drops when [NSApp run] is inside dispatch_async | dispatch_async — ADR-008 |
| PTY echo | tcgetattr/tcsetattr to clear ECHO flag | Line discipline ECHO causes every keystroke to appear twice in reader | Filtering doubles in software (fragile) |
| Interaction detection | Timer-based: 800ms quiet → FREE_TEXT | Simple, reliable, format-agnostic | Pattern-matching on Claude's prompt strings (fragile — format varies) |
| ANSI stripping | AnsiStripper regex for NSTextView display | NSTextView has no terminal emulation; readable text beats garbled ANSI | Raw bytes (unreadable), full VT100 parser (premature — xterm.js handles it in prod) |
| Claude binary resolution | ClaudeLocator via /bin/zsh -l -c 'which claude' | Picks up shell profile PATH (Homebrew, nvm, ~/.local/bin) | Hardcoded path (brittle), file picker (complex for v1) |

---

## Where We're Going

**Plan 6 — .app Bundle (immediate next step)**

Package the native binary as a proper macOS `.app` bundle — the prerequisite for
WKWebView and clean dylib discovery:
- `libMyMacUI.dylib` in `Contents/Frameworks/` (removes `-Dcccli.dylib.path` workaround)
- `@rpath` so the binary finds the dylib automatically
- `Info.plist` with bundle ID and `LSUIElement`

**Plan 5b — WKWebView + xterm.js (after Plan 6)**

Replace NSTextView with WKWebView hosting xterm.js. Remove AnsiStripper (xterm.js
handles ANSI natively). `myui_evaluate_javascript()` is already in the ABI as a
no-op — just implement it.

**Open questions:**

- PTY bytes → xterm.js: define chunking and encoding contract (UTF-8 string vs
  Uint8Array) between Java read loop and WKWebView JS evaluation
- Slash command overlay: NSTextField has no overlay buffer — transparent NSView over
  split pane vs popup anchored in NSSplitView; deferred
- NSTextField → NSTextView for input: multi-line prompts eventually need NSTextView
  with scroll; deferred to v2
- WKWebView codesigning: `.app` bundles require signing even for local use on modern
  macOS; validate entitlements needed for WebKit subprocess IPC

---

## Linked ADRs

| ADR | Decision |
|---|---|
| ADR-001 | Objective-C bridge (MyMacUI.dylib) via Panama FFM as UI framework |
| ADR-002 | WKWebView + xterm.js for terminal pane |
| ADR-003 | Panama FFM → POSIX libc for PTY management |
| ADR-004 | Quarkus Native (GraalVM) as Java runtime |
| ADR-005 | Objective-C over Swift for the bridge |
| ADR-006 | myui_start() via GCD dispatch — AppKit main thread constraint |
| ADR-007 | CFRunLoopRun vs semaphore — JVM vs native image thread model |
| ADR-008 | GCD main queue blocked by [NSApp run] inside dispatch_async |
| ADR-009 | NSSplitView as contentView breaks keyboard events |
| ADR-010 | WKWebView subprocess fails in non-bundle JVM process |
| ADR-011 | NSTextField empty-field cursor blink AppKit bug |

## Context Links

- Architecture decisions: `DECISIONS.md`
- Current design: `DESIGN.md`
- Implementation plans: `docs/superpowers/plans/`
- Blog: `blog/` (five entries covering the full journey)
