# Claude Desktop CLI — Handoff Document

> **For future Claude instances.** Read this before doing anything else. It captures the full journey, current state, known issues, pitfalls, and what comes next. This project has significant context that is not obvious from the code alone.

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

### Phase 1 — Architecture R&D (No code written yet)

**Started with:** JavaFX + JediTerm + pty4j + GraalVM/Gluon

**Pivots made (in order):**

1. **JavaFX rejected** — JavaFX renders its own widgets, not AppKit. Text input isn't NSTextView. Not truly native.

2. **Swift rejected** — ideal native answer (SwiftTerm + NSTextView + AppKit), but the user cannot code Swift.

3. **JavaFX + WebView + xterm.js considered** — would fix the terminal rendering issue (WebKit is native) but the input area is still JavaFX, not NSTextView. Not native enough.

4. **SWT considered** — SWT wraps real AppKit controls on macOS. An SWT `Text` widget IS an NSTextView. Eclipse IDE is the proof. But SWT uses JNA internally, and JNA's runtime reflection breaks GraalVM native image. Ruled out.

5. **Java + JNI to AppKit directly** — possible but unmaintainable. Ruled out.

6. **Parallel brainstorm (separate Claude session, no context)** produced the winner: **Objective-C bridge + Panama FFM + Quarkus Native**. Minimal ObjC, all logic in Java, GraalVM native image safe.

7. **WKWebView + xterm.js** added to the bridge primitives for the terminal pane (AppKit has no NSTerminalView).

8. **pty4j rejected** — uses JNA (same problem as SWT). Replaced with **Panama FFM direct POSIX calls** (`posix_openpt`, `posix_spawn`, etc.).

9. **TamboUI (original long-term vision)** — was the planned rich output renderer. After pivoting to ObjC bridge + Panama, TamboUI no longer fits. The new long-term vision for direct API mode is a **web renderer in WKWebView** (structured API events → HTML/CSS/JS display).

**Archive:** `archive/VISION_original_javafx.md` has the complete original vision document. `blog/001-architecture-evolution.md` tells the full story.

### Phase 2 — Bridge Foundation (Plan 1)

Built and validated: Objective-C bridge → Panama FFM → Quarkus Native → NSWindow.

**Key pivots during implementation:**

- `@QuarkusMain.run()` is on a **worker thread** in JVM mode, **main thread** in native image. `myui_start()` detects this and branches.
- `dispatch_async` blocks don't drain when `[NSApp run]` is inside a `dispatch_async` block (GCD serialisation). **Critical insight** — affects everything.
- Panama upcalls work in GraalVM native image but require `reachability-metadata.json` with `directUpcalls` format (NOT `upcalls` — different schema).
- `Arena.ofAuto()` throws on `close()` — use `Arena.ofShared()`.
- jextract's `WindowClosedCallback.allocate()` uses `privateLookupIn()` which fails in native image. Use `findStatic()` on your own class instead.

**Result:** NSWindow appears, closes with a Java upcall, 0.017s startup in native image. ✅

### Phase 3 — Split Pane UI (Plan 2)

Built and validated: NSTextField input + NSTextView output, full Java ↔ ObjC communication pipeline.

**Key pivots during implementation:**

- **NSSplitView as contentView** — silently blocks ALL events to subviews. Never replace `window.contentView`. Add to it.
- **WKWebView silent failure** — WKWebView's `WebContent` subprocess fails in JVM process (no `.app` bundle). Replaced with NSTextView for development. WKWebView returns in production.
- **GCD serialisation (the big one)** — `dispatch_async` and `dispatch_after` blocks targeting the main queue do NOT execute while `[NSApp run]` is inside a `dispatch_async` block. Use AppKit delegates, synchronous updates, or NSTimer instead.
- **NSTextField cursor bug** — empty NSTextField never starts blink timer on programmatic focus. Fix via `windowDidBecomeKey:` with a no-op string assignment.
- **upcall → dispatch deadlock** — calling `dispatch_async(main_queue, ...)` from inside a Panama upcall context (AppKit event handler) doesn't drain. Update NSTextView synchronously instead.

**Result:** Split pane UI working — type, Enter, text echoes to output pane. 0.020s native image startup. ✅

---

## Current State of the Codebase

### Project Structure

```
/Users/mdproctor/claude/cccli/
├── pom.xml                          Parent Maven POM
├── VISION.md                        Product vision and rationale
├── DESIGN.md                        Current implemented architecture
├── DECISIONS.md                     All architectural decision records (ADR-001 to ADR-011)
├── mac-ui-bridge/                   Objective-C bridge
│   ├── include/MyMacUI.h            C ABI (source of truth for jextract)
│   ├── src/MyMacUI.m                ObjC implementation
│   └── Makefile                     Builds libMyMacUI.dylib
├── app-core/                        Java: pure business logic (currently empty)
├── app-macos/                       Java: Quarkus, Panama bindings, wiring
│   ├── pom.xml
│   └── src/main/java/dev/mproctor/cccli/
│       ├── Main.java                @QuarkusMain entry point
│       └── bridge/
│           ├── MacUIBridge.java     Java facade over ObjC bridge
│           ├── Callbacks.java       Panama upcall stubs
│           └── gen/                 jextract-generated bindings (committed)
├── docs/
│   ├── APPKIT_PITFALLS.md           ⚠️ READ THIS — 7 pitfalls with solutions
│   ├── HANDOFF.md                   This document
│   └── superpowers/plans/           Implementation plans
│       ├── 2026-04-03-bridge-foundation.md    Plan 1 (complete)
│       └── 2026-04-04-split-pane-ui.md        Plan 2 (complete)
├── blog/                            Evolving diary blog articles
│   ├── 001-architecture-evolution.md
│   ├── 002-first-window.md
│   ├── 003-split-pane-ui.md
│   └── 004-appkit-seven-bugs.md
└── archive/
    └── VISION_original_javafx.md    Original JavaFX vision (archived)
```

### What Currently Works

| Feature | Status | Notes |
|---------|--------|-------|
| Native NSWindow | ✅ | Appears centred, resizable |
| NSTextField input | ✅ | Enter submits, cursor visible from startup |
| NSTextView output | ✅ | Shows initial text, appends on submit |
| Panama upcall (ObjC→Java) | ✅ | Window close, text submit |
| Panama downcall (Java→ObjC) | ✅ | Append output |
| JVM dev mode | ✅ | `mvn quarkus:dev` |
| Native image (GraalVM) | ✅ | 0.020s startup |
| Thread model (JVM + native) | ✅ | `[NSThread isMainThread]` branch |

### What Is NOT Yet Built

| Feature | Plan | Notes |
|---------|------|-------|
| PTY subprocess management | Plan 3 | POSIX calls via Panama FFM |
| Spawning `claude` binary | Plan 3/4 | Login shell resolution |
| Routing PTY output to display | Plan 3 | Byte stream → NSTextView |
| PTY resize (SIGWINCH/TIOCSWINSZ) | Plan 3 | Wire window resize to PTY |
| WKWebView + xterm.js | Plan 5 | Works in .app bundle, not JVM |
| InteractionDetector | Plan 5 | Pattern match PTY bytes |
| InteractionStateMachine | Plan 5 | Mode switching |
| PASSIVE mode (Stop button) | Plan 5 | Block input while Claude works |
| Slash command UI | Deferred | Listed as not-v1 in VISION |
| Input history | Deferred | Listed as not-v1 in VISION |
| .app bundle packaging | Plan 6+ | Codesigning, notarization |
| Linux support | Future | GTK bridge needed |

---

## Critical Technical Details

### Development Commands

```bash
# Environment setup (always run first)
source "$HOME/.sdkman/bin/sdkman-init.sh"
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

### Tools Installed

| Tool | Version | Location |
|------|---------|----------|
| GraalVM CE | 25.0.2 | SDKMAN: `sdk use java 25.0.2-graalce` |
| native-image | 25.0.2 | Bundled with GraalVM |
| jextract | 22 | `$HOME/tools/jextract-22/bin/` |
| clang | System | Xcode Command Line Tools |
| Maven | 3.9.14 | System |

### Panama Binding Conventions

- Upcalls: use `MethodHandles.lookup().findStatic()` on your own class — NOT `jextract`'s generated `allocate()` method (which uses `privateLookupIn()` — fails in native image)
- `Arena.ofShared()` for application-lifetime arenas, `Arena.ofConfined()` in try-with-resources for temporaries
- Jextract output is **committed** — only regenerate when header changes

### Native Image Configuration

Three files in `app-macos/src/main/resources/META-INF/native-image/`:

- `native-image.properties` — `--enable-native-access=ALL-UNNAMED`, `--initialize-at-run-time=dev.mproctor.cccli.bridge.gen`
- `reflect-config.json` — registers `Callbacks.onWindowClosed` and `Callbacks.onTextSubmitted`
- `dev.mproctor.cccli/app-macos/reachability-metadata.json` — GraalVM 25 foreign metadata format: `directUpcalls` (NOT `upcalls`) and `downcalls`

---

## The AppKit Pitfalls — Read Before Debugging

**Full reference: `docs/APPKIT_PITFALLS.md`**. Brief summary:

1. **GCD main queue blocks don't drain** when `[NSApp run]` is inside a `dispatch_async` block. Use AppKit delegates or synchronous updates.
2. **Never replace `window.contentView`** — add views to it. Replacement breaks the keyboard/mouse responder chain silently.
3. **WKWebView fails silently** in JVM process (no `.app` bundle). Use NSTextView for development.
4. **Synchronous updates from upcalls** — `dispatch_async` from upcall context (main thread inside AppKit event) never drains. Check `[NSThread isMainThread]` and update synchronously.
5. **NSTextField cursor** — apply empty-field blink timer fix in `windowDidBecomeKey:` using no-op string assignment.
6. **Placeholder colour** — use `placeholderAttributedString` with explicit colour on dark backgrounds.
7. **Thread model** — `myui_start()` must branch on `[NSThread isMainThread]` for JVM (worker) vs native image (main thread).

---

## Architecture Decisions (Summary)

Full records in `DECISIONS.md`. Key ones:

| Decision | Choice | Why |
|----------|--------|-----|
| UI layer | Objective-C bridge (MyMacUI.dylib) | Clean C ABI, jextract-compatible, GraalVM safe |
| ObjC vs Swift for bridge | Objective-C | Stable C ABI, jextract works directly from .h |
| Java↔native bridge | Panama FFM | GraalVM native-image safe, no JNA |
| PTY management | Panama FFM → POSIX libc | Same reason — no JNA/pty4j |
| Terminal pane (dev) | NSTextView | WKWebView subprocess fails in JVM process |
| Terminal pane (production) | WKWebView + xterm.js | WebKit subprocess works in .app bundle |
| Long-term output renderer | Web renderer in WKWebView | TamboUI (original plan) doesn't fit ObjC bridge architecture |
| Java runtime | Quarkus Native (GraalVM) | Fast startup, native binary, user knows Quarkus |
| Main thread model | Branch on isMainThread | JVM vs native image call different threads |
| Process spawning | posix_spawn (not fork) | fork() unsafe in GraalVM native image |

---

## Honest Assessment: Pros and Cons

### Strengths

- **Truly native macOS** — real AppKit controls, NSTextView, NSWindow. Feels like a Mac app.
- **Fast** — 0.020s startup in native image. Native binary, no JVM.
- **All logic in Java** — Quarkus CDI, familiar toolchain, user's strongest language.
- **Clean separation** — ObjC bridge is minimal (MyMacUI.h, ~250 lines of .m). Java owns everything else.
- **Panama FFM is the right call** — stable since Java 22, native-image safe, no JNA fragility.
- **Good documentation** — DECISIONS.md, APPKIT_PITFALLS.md, blog, VISION.md all in sync.

### Risks and Weaknesses

- **WKWebView dev/prod gap** — in JVM mode, the output pane is NSTextView. In production `.app` bundle, it'll be WKWebView + xterm.js. There will be a transition point where these diverge further before converging. Test both.
- **NSTextField is single-line** — the current input field is NSTextField. For complex multi-line prompts (the original VISION's intent), it'll need upgrading to NSTextView with a scroll view. That's Plan N+1.
- **app-core is empty** — all Java logic is currently in app-macos. When PTY management, InteractionDetector, and state machine are built, they should live in app-core (no UI dependency). Don't let this boundary collapse.
- **dylib path is a system property** — not bundled yet. Every run requires `-Dcccli.dylib.path=...`. Proper `.app` bundle packaging (Contents/Frameworks/) is Plan 6+.
- **Two languages** — ObjC and Java. The bridge must stay minimal. Every time something seems like it should go in ObjC, question it — Java can almost certainly do it via Panama.
- **GCD/AppKit threading model** is subtle and unforgiving. Every new piece of ObjC code must be evaluated against the pitfalls in `docs/APPKIT_PITFALLS.md`.

---

## What Comes Next

### Plan 3 — PTY Management

Build the PTY layer in `app-core` using Panama FFM direct POSIX calls. No pty4j. No JNA.

Key functions needed:
```
posix_openpt()   → open PTY master fd
grantpt()        → grant slave access
unlockpt()       → unlock slave
ptsname()        → get slave device path
posix_spawn()    → spawn child with slave as stdin/stdout/stderr
ioctl(TIOCSWINSZ) → forward window resize
read() / write() → byte I/O on master fd
```

Test with `/bin/echo hello` or `/bin/cat` before wiring to `claude`. Validate PTY resize. Validate clean subprocess termination.

### Plan 4 — Wire to Claude

- Resolve `claude` binary at startup using login shell: `/bin/zsh -l -c 'which claude'`
- Fall back to file picker dialog if not found
- Replace test subprocess with `claude`
- Route PTY bytes to `bridge.appendOutput()`
- Validate Claude output renders correctly in NSTextView

### Plan 5 — Interaction Detection and State Machine

Build in `app-core`:
- `InteractionDetector` — pattern match PTY byte chunks → `InteractionEvent`
- `InteractionStateMachine` — owns mode transitions (FREE_TEXT, PASSIVE, LIST_SELECTION, etc.)
- `PASSIVE` mode — block Enter key while Claude is working, show Stop button
- Wire Stop button to SIGINT on the claude subprocess

### Plan 5b — WKWebView + xterm.js (Production Terminal)

Build the native image `.app` bundle first (Plan 6). Then:
- Re-enable WKWebView in the terminal pane
- Load xterm.js (bundled as a resource)
- Route PTY bytes via `evaluateJavaScript("window.term.write(...)")`
- The `myui_evaluate_javascript` function is already in the ABI as a no-op — just implement it

### Plan 6 — .app Bundle and Packaging

- Proper `Contents/MacOS/`, `Contents/Frameworks/` structure
- `libMyMacUI.dylib` copied to `Contents/Frameworks/`
- `@rpath` resolution so the binary finds the dylib
- `Info.plist` with LSUIElement/bundle info
- macOS codesigning and notarization (required for distribution)

---

## The Blog — Evolving Diary

The user is documenting this project as a public diary of AI-assisted R&D. **The blog is important.** Update it when:
- A significant architectural pivot happens
- A plan is completed with interesting bugs
- Something works that previously didn't, with a good story behind it

Current posts:
- `blog/001-architecture-evolution.md` — the R&D pivots before any code
- `blog/002-first-window.md` — Plan 1 pivots and NSWindow validation
- `blog/003-split-pane-ui.md` — Plan 2 overview
- `blog/004-appkit-seven-bugs.md` — detailed debugging writeup with diagrams

**Tone:** first-person diary. Show the thinking at the time. When the vision changes, say so — don't revise history. The audience includes developers who will enjoy the technical detail and non-developers who want the AI-assisted R&D story.

**Do NOT use the blog to jump ahead.** Only write about things that have been validated.

---

## Working Style Notes

- The user writes plans before implementation (`docs/superpowers/plans/`)
- Subagent-driven development is used to execute plans (one subagent per task, spec + quality review)
- DECISIONS.md is kept current — add an ADR whenever a significant architectural choice is made
- `docs/APPKIT_PITFALLS.md` should be updated as new AppKit pitfalls are discovered
- The user prefers concise responses and gets frustrated with going in circles
- When stuck in a loop, strip back to the minimal test case. When that works, add complexity back one layer at a time.
- `mvn install -q` from the root is required before `mvn quarkus:dev` in app-macos — app-core needs to be installed to the local Maven repo first

---

## Before You Do Anything

1. **Read `docs/APPKIT_PITFALLS.md`** — 10 minutes now saves hours later
2. **Read `DECISIONS.md`** — understand why we're here, not just where we are
3. **Check `VISION.md`** — the long-term direction and the trade-offs we've accepted
4. **Look at recent git log** — `git log --oneline -20` to understand the current state
5. **Check the plan files** in `docs/superpowers/plans/` for what was built and what's next

If the user asks you to continue implementation, the next plan to write is **Plan 3: PTY Management**.
