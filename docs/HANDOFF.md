# Claude Desktop CLI — Handoff Document

> **For future Claude instances.** Also see the central knowledge garden at `~/claude/knowledge-garden/` for cross-project technical gotchas.
> Read this before doing anything else. It captures the full journey, current state, known issues, pitfalls, and what comes next. This project has significant context that is not obvious from the code alone.

---

## What We Are Building

A native macOS desktop application that wraps the Claude Code CLI (`claude`) with a better input/output experience. The core idea:

- **Top pane:** displays Claude's output (terminal/text area)
- **Bottom pane:** a native text input field for composing prompts
- **Intelligence:** the app watches Claude's output and upgrades the input surface — list picker when Claude shows choices, confirmation widget for yes/no, slash command browser when the user types `/`
- **Toggle:** `Cmd+\` drops back to raw passthrough mode at any time

The user is a **Java developer who cannot code Swift**. All business logic must be in Java/Quarkus. The UI must be truly native macOS (AppKit), not JavaFX or SWT.

---

## The Journey: How We Got Here

### Phase 1 — Architecture R&D (No code written)

Pivots made: JavaFX rejected → Swift rejected → SWT rejected → JNI rejected → **Objective-C bridge + Panama FFM + Quarkus Native** chosen. Full story in `blog/2026-04-04-01-architecture-evolution.md`.

### Phase 2 — Bridge Foundation ✅

Built and validated: ObjC bridge → Panama FFM → Quarkus Native → NSWindow. 0.017s startup. Key pitfalls: Panama upcalls in native image require `directUpcalls` format (not `upcalls`), `Arena.ofAuto()` throws on close(), jextract's `allocate()` fails in native image.

### Phase 3 — Split Pane UI ✅

Built and validated: NSTextField input + NSTextView output, full Java ↔ ObjC communication pipeline. 0.020s startup. Key pitfalls: NSSplitView as contentView blocks events, WKWebView fails in JVM process, GCD serialisation (the big one), NSTextField cursor bug, upcall → dispatch deadlock.

### Phase 4 — PTY Management ✅

Built `PosixLibrary` (17 POSIX functions via Panama FFM) and `PtyProcess` (open/spawn/read/write/resize/close/sendSigInt). Tested with `/bin/cat`. Two bugs found:
- **dispatch_async from reader thread silently dropped** — GCD Bug 3 again, in a new context (PTY reader thread). Fixed with `performSelectorOnMainThread:` throughout.
- **PTY double echo** — line discipline ECHO flag echoes master writes back to the reader. Fixed with `tcgetattr`/`tcsetattr` clearing `ECHO` bit.

### Phase 5 — Wire to Claude ✅

- `ClaudeLocator`: resolves `claude` binary via `/bin/zsh -l -c 'which claude'` (login shell, works in native binary)
- `AnsiStripper`: strips VT100 escape codes for NSTextView display (dev workaround; removed in Plan 5b)
- `Main.java`: spawns real `claude` CLI via PTY, ANSI-strips output, routes to NSTextView

### Phase 6 — Passive Mode ✅

- `ClaudeState` enum (FREE_TEXT, PASSIVE)
- `InteractionDetector`: timer-based — PTY output → PASSIVE, 800ms quiet → FREE_TEXT, `forceIdle()` → immediate FREE_TEXT
- ObjC bridge: Stop button (overlaid right of input field), `myui_set_passive_mode(int)`, `StopClickedCallback` as 7th param to `myui_start()`
- `PtyProcess.sendSigInt()` sends SIGINT to claude subprocess
- Native image: all three plans built first try (Plan 4 needed `--initialize-at-run-time` for `PosixLibrary` and a posix_spawn downcall param count fix)

---

## Current State of the Codebase

### Project Structure

```
/Users/mdproctor/claude/cccli/
├── pom.xml                          Parent Maven POM (Java 22)
├── VISION.md                        Product vision and rationale
├── DESIGN.md                        Current implemented architecture
├── DECISIONS.md                     All architectural decision records (ADR-001 to ADR-011)
├── mac-ui-bridge/                   Objective-C bridge
│   ├── include/MyMacUI.h            C ABI (source of truth for jextract)
│   └── src/MyMacUI.m                ObjC implementation
├── app-core/                        Java: pure business logic
│   └── src/main/java/dev/mproctor/cccli/
│       ├── ClaudeState.java         Enum: FREE_TEXT, PASSIVE
│       ├── ClaudeLocator.java       Resolves claude binary via login shell
│       ├── AnsiStripper.java        Strips VT100 escape codes (dev only)
│       ├── InteractionDetector.java Timer-based mode state machine
│       └── pty/
│           ├── PosixLibrary.java    Panama FFM downcalls for POSIX functions
│           └── PtyProcess.java      PTY lifecycle management
├── app-macos/                       Java: Quarkus, Panama bindings, wiring
│   └── src/main/java/dev/mproctor/cccli/
│       ├── Main.java                @QuarkusMain — wires everything
│       └── bridge/
│           ├── MacUIBridge.java     Java facade over ObjC bridge
│           ├── Callbacks.java       Panama upcall stubs
│           └── gen/                 jextract-generated bindings (committed)
├── docs/
│   ├── APPKIT_PITFALLS.md           ⚠️ READ THIS — pitfalls with solutions
│   ├── HANDOFF.md                   This document
│   └── superpowers/plans/           Implementation plans (all complete)
└── blog/
    ├── 2026-04-04-01-architecture-evolution.md
    ├── 2026-04-04-02-first-window.md
    ├── 2026-04-04-03-split-pane-ui.md
    ├── 2026-04-04-04-appkit-seven-bugs.md
    └── 2026-04-05-01-pty-claude-passive.md
```

### What Currently Works

| Feature | Status | Notes |
|---------|--------|-------|
| Native NSWindow | ✅ | Appears centred, resizable |
| NSTextField input | ✅ | Enter submits, cursor visible |
| NSTextView output | ✅ | Shows output, scrolls |
| PTY subprocess management | ✅ | POSIX calls via Panama FFM |
| Spawning `claude` binary | ✅ | Via ClaudeLocator (login shell) |
| PTY output to display | ✅ | ANSI-stripped → NSTextView |
| PASSIVE mode (input blocking) | ✅ | 800ms quiet timer |
| Stop button → SIGINT | ✅ | forceIdle() immediate |
| Panama upcalls (ObjC→Java) | ✅ | Window close, text submit, stop |
| Panama downcalls (Java→ObjC) | ✅ | Append output, passive mode |
| JVM dev mode | ✅ | `mvn quarkus:dev` |
| Native image (GraalVM) | ✅ | ~0.020s startup |

### What Is NOT Yet Built

| Feature | Plan | Notes |
|---------|------|-------|
| .app bundle packaging | Plan 6 | **IMMEDIATE NEXT STEP** |
| WKWebView + xterm.js | Plan 5b | Requires .app bundle (Plan 6 first) |
| PTY resize (wire to window) | Plan 5 | Currently fixed 24×120 |
| InteractionDetector pattern matching | Future | Currently timer-only; no LIST_SELECTION |
| Slash command UI | Deferred | Not-v1 per VISION |
| Input history | Deferred | Not-v1 per VISION |
| Linux support | Future | GTK bridge needed |

---

## Critical Technical Details

### Development Commands

```bash
# Environment setup (always run first)
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk use java 25.0.2-graalce
export PATH="$HOME/tools/jextract-22/bin:$PATH"

# Run in JVM dev mode
cd /Users/mdproctor/claude/cccli
mvn install -q   # must install app-core first
cd app-macos
mvn quarkus:dev -Dcccli.dylib.path="$(pwd)/../mac-ui-bridge/build/libMyMacUI.dylib"

# Build native image
cd /Users/mdproctor/claude/cccli
mvn package -pl app-macos -am -Pnative

# Run native binary
./app-macos/target/app-macos-1.0.0-SNAPSHOT-runner \
  -Dcccli.dylib.path=/Users/mdproctor/claude/cccli/mac-ui-bridge/build/libMyMacUI.dylib

# Rebuild dylib after ObjC changes
cd mac-ui-bridge && make clean && make

# Regenerate Panama bindings after header changes
rm -rf app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/
jextract --output app-macos/src/main/java \
         --target-package dev.mproctor.cccli.bridge.gen \
         mac-ui-bridge/include/MyMacUI.h
```

### Native Image Configuration — Lessons Learned

- `--initialize-at-run-time=dev.mproctor.cccli.bridge.gen` — jextract-generated code (calls `findOrThrow()` at init)
- `--initialize-at-run-time=dev.mproctor.cccli.pty.PosixLibrary` — hand-written Panama FFM class; static final MethodHandle fields cause `linkToNative parsing failure` at analysis time if build-time init
- `reachability-metadata.json` uses `directUpcalls` (not `upcalls`) — GraalVM 25 format
- `MissingForeignRegistrationError` names the function but not what's wrong — cross-check param counts in FunctionDescriptor one by one
- `posix_spawn` downcall takes **6** `void*` params: pid_t*, path, file_actions, attrp, argv[], envp[]

### Panama Binding Conventions

- Upcalls: use `MethodHandles.lookup().findStatic()` on your own class — NOT jextract's `allocate()` (uses `privateLookupIn()` — fails in native image)
- `Arena.ofShared()` for application-lifetime arenas, `Arena.ofConfined()` in try-with-resources for temporaries
- Jextract output is **committed** — only regenerate when header changes

### PTY Notes

- `posix_spawn` not `fork()` — fork() unsafe in GraalVM native image
- Clear `ECHO` flag via `tcgetattr`/`tcsetattr` after opening master fd — otherwise every write to master is echoed back to the reader (text appears twice)
- `c_lflag` in macOS AArch64 `struct termios` is at byte offset 24 (`ECHO = 0x00000008L`)
- Stop button sends `SIGINT = 2` to the claude subprocess PID

### Threading

- `myui_append_output()` uses `performSelectorOnMainThread:waitUntilDone:NO` (NOT `dispatch_async`) — GCD main queue is serialised while `[NSApp run]` is inside a `dispatch_async` block; `performSelectorOnMainThread:` schedules on NSRunLoop instead
- `InteractionDetector` callback fires on its scheduler thread — safe because `bridge.setPassiveMode()` → `myui_set_passive_mode()` → `performSelectorOnMainThread:`

---

## The AppKit Pitfalls — Read Before Debugging

**Full reference: `docs/APPKIT_PITFALLS.md`**. Brief summary:

1. **GCD main queue blocks don't drain** when `[NSApp run]` is inside a `dispatch_async` block. Use `performSelectorOnMainThread:` instead — it schedules on NSRunLoop.
2. **Never replace `window.contentView`** — add views to it.
3. **WKWebView fails silently** in JVM process (no `.app` bundle). Use NSTextView for development.
4. **PTY reader thread → `dispatch_async` to main queue** also silently drops (same root cause as #1). Use `performSelectorOnMainThread:`.
5. **PTY double echo** — line discipline ECHO flag is on by default. Clear it with `tcgetattr`/`tcsetattr`.
6. **NSTextField cursor** — apply empty-field blink timer fix in `windowDidBecomeKey:`.
7. **Placeholder colour** — use `placeholderAttributedString` with explicit colour on dark backgrounds.
8. **Thread model** — `myui_start()` branches on `[NSThread isMainThread]` for JVM (worker) vs native image (main thread).

---

## What Comes Next

### Plan 6 — .app Bundle and Packaging (IMMEDIATE NEXT STEP)

Build a proper macOS `.app` bundle so that:
1. WKWebView's subprocess model works (requires `.app` bundle)
2. The dylib is found without `-Dcccli.dylib.path` system property
3. The app can be launched from Finder

Key work:
- `Contents/MacOS/` + `Contents/Frameworks/` directory structure
- `libMyMacUI.dylib` copied to `Contents/Frameworks/`
- `@rpath` so the binary finds the dylib at `@executable_path/../Frameworks/libMyMacUI.dylib`
- `Info.plist` with bundle identifier, `LSUIElement` (no Dock icon), version
- Codesigning (required for modern macOS, even for local use)
- Maven exec plugin or shell script to assemble the bundle after native-image

### Plan 5b — WKWebView + xterm.js (After Plan 6)

- Re-enable WKWebView in the terminal pane (replace NSTextView)
- Bundle xterm.js as a resource in the `.app`
- Route PTY bytes via `evaluateJavaScript("window.term.write(...)")`
- `myui_evaluate_javascript()` is already in the ABI as a no-op — implement it
- Remove `AnsiStripper` (xterm.js handles ANSI natively)

---

## Working Style Notes

- The user writes plans before implementation (`docs/superpowers/plans/`)
- Subagent-driven development is used to execute plans (one subagent per task, spec + quality review)
- DECISIONS.md is kept current — add an ADR for significant architectural choices
- `docs/APPKIT_PITFALLS.md` should be updated as new pitfalls are discovered
- The user prefers concise responses
- `mvn install -q` from root is required before `mvn quarkus:dev` in app-macos
- The blog (`blog/`) is a public R&D diary — update after significant milestones

---

## Before You Do Anything

1. **Read `docs/APPKIT_PITFALLS.md`** — 10 minutes now saves hours later
2. **Read `DECISIONS.md`** — understand why we're here
3. **Check `VISION.md`** — long-term direction
4. **`git log --oneline -10`** — understand current state
5. **Next plan to write: Plan 6 (.app Bundle)**
