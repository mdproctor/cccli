# Retrospective Issue Mapping — mdproctor/cccli

Generated: 2026-04-08  
Scope: full history (148 commits, 2026-04-03 → 2026-04-07)

---

## Existing Issues (no recreation needed)

| # | Title | State | Action |
|---|-------|-------|--------|
| #1 | Epic: WKWebView + xterm.js terminal renderer | OPEN | Close — all children (#2–#7) are done |
| #2 | Swap NSTextView for WKWebView in the Obj-C bridge | CLOSED | None |
| #3 | Bundle xterm.js as a resource inside the .app | CLOSED | None |
| #4 | Implement myui_evaluate_javascript() — currently a no-op | CLOSED | None |
| #5 | Route PTY output bytes through evaluateJavaScript(window.term.write(...)) | CLOSED | None |
| #6 | Remove AnsiStripper once WKWebView rendering is live | CLOSED | None |
| #7 | Validate WKWebView codesigning entitlements in .app bundle | CLOSED | None |
| #8 | feat: terminal resize — wire window resize to PTY and xterm.js | CLOSED | None |
| #9 | feat: slash command passthrough mode | OPEN | Close — shipped in session 3 |

---

## New Issues to Create

All new issues will be created **closed** with a retrospective note.

### Epic — "Build Claude Desktop CLI MVP"

**Label:** `epic, enhancement`  
**Date range:** 2026-04-03 → 2026-04-06  
**Children:** Issues A, B, C, D (below)

**Overview:** Four sequential layers that together delivered the first working native macOS app: the Obj-C/Panama bridge, the split-pane UI, the PTY/claude integration, and the signed .app bundle.

**Definition of Done:** A signed `.app` bundle opens, runs claude in a PTY, renders its output in xterm.js, and accepts input via NSTextField.

**Scope:**
- [x] #11 — Implement Objective-C/Panama FFM bridge foundation
- [x] #12 — Implement split-pane UI with NSTextField input
- [x] #13 — Implement PTY layer and wire claude CLI with passive mode
- [x] #14 — Package app as a signed .app bundle

---

### Issue A — "Implement Objective-C/Panama FFM bridge foundation" *(child of MVP epic)*

**Label:** `enhancement`  
**Date range:** 2026-04-03  
**Commits:**

| Hash | Message |
|------|---------|
| `024e9cc` | feat: Maven multi-module skeleton (app-core, app-macos) |
| `a84bd28` | feat: Objective-C bridge C ABI header (Phase 1 primitives) |
| `7ad45db` | feat: Objective-C bridge implementation (NSWindow + close callback) |
| `120325a` | docs: note single-window delegate callback limitation |
| `e476c68` | feat: Makefile builds libMyMacUI.dylib via clang |
| `fb36d7e` | feat: Panama FFM bindings generated from MyMacUI.h via jextract |
| `d77366e` | feat: Panama upcall stub for window-close callback |
| `a5a320b` | feat: MacUIBridge facade — loads dylib, wraps Panama downcalls and upcall |
| `ede00ca` | fix: use Arena.ofShared() in MacUIBridge — ofAuto() does not support close() |
| `a712a76` | feat: @QuarkusMain entry point — init AppKit, create window, run event loop |
| `0c454c6` | fix: dispatch AppKit to main thread via GCD in myui_start() |
| `8f901ee` | docs: ADR-006 — main thread dispatch via GCD (myui_start) |
| `6c46e2b` | fix: clear onClosed before invoking — prevents double-fire on NSApp terminate |
| `af0e503` | feat: native image config — enable-native-access and Panama FFM reflection |
| `c5d2d78` | fix: defer jextract generated class init to runtime — symbols not available at build time |
| `649fac0` | fix: bypass jextract upcall helper — use findStatic on own class for native image |
| `598e430` | fix: add reachability-metadata.json registering void upcall descriptor for GraalVM native image |
| `8a5f105` | fix: correct reachability-metadata.json format — directUpcalls with class/method, downcall descriptors |

---

### Issue B — "Implement split-pane UI with NSTextField input" *(child of MVP epic)*

**Label:** `enhancement`  
**Date range:** 2026-04-04  
**Commits:**

| Hash | Message |
|------|---------|
| `40c7caf` | docs: blog post 2 — bridge foundation pivots and first native window |
| `eeec501` | docs: ADR-007 — myui_start thread detection for native image vs JVM mode |
| `316d365` | fix: myui_start handles both main-thread and worker-thread callers |
| `e8a6421` | docs: Plan 2 — split pane UI (NSSplitView + WKWebView + NSTextView) |
| `ad4b38b` | feat: add TextSubmittedCallback, myui_load_html, myui_evaluate_javascript to bridge header |
| `627e1b0` | feat: split pane UI — NSSplitView + WKWebView + NSTextView + Enter key callback |
| `7dff32b` | feat: add WebKit framework to dylib build |
| `6b8c233` | feat: regenerate Panama bindings — split pane API with 6-param myui_start |
| `7355889` | feat: add TextSubmittedCallback upcall to Callbacks (findStatic pattern) |
| `f4c7638` | feat: MacUIBridge — extend start() with html/text params, add loadHtml/evaluateJavaScript |
| `591c931` | feat: update native image metadata — TextSubmittedCallback upcall + 6-param myui_start downcall |
| `7126cd1` | feat: Main — split pane with initial HTML and echo input handler |
| `52b201c` | fix: delay makeFirstResponder 200ms — WKWebView claims focus during init |
| `56561a6` | debug: add NSLog instrumentation for window key status and text view interactions |
| `5860686` | fix: replace NSScrollView+NSTextView with NSTextField — reliable focus and Enter key handling |
| `475426b` | fix: recalculateKeyViewLoop after contentView replacement — restores keyboard routing |
| `438082b` | fix: replace NSSplitView with plain NSView container — NSSplitView blocks keyboard events to subviews |
| `4072155` | fix: log JS errors from evaluateJavaScript; move script inside body tag |
| `4cb591b` | fix: replace WKWebView with NSTextView for output — WKWebView subprocess fails in non-bundle JVM |
| `e3a7283` | debug: WKNavigationDelegate + verbose JS logging to diagnose WebView rendering |
| `d63b944` | fix: proper NSTextView scroll config + setString: for reliable append and redraw |
| `4ba279f` | debug: log every step of append chain to find the break point |
| `dc44e33` | fix: update NSTextView synchronously when on main thread — dispatch_async not drained inside Panama upcall |
| `a3e451d` | fix: make NSTextView first responder on startup — enables keyboard focus |
| `c0ae8e3` | fix: use window.initialFirstResponder for NSTextField cursor — blink timer initialises correctly |
| `bef7199` | fix: visible placeholder colour on dark bg; defer focus with updateInsertionPointStateAndRestartTimer |
| `a565765` | fix: no-op string assignment after makeFirstResponder (AppKit empty-field bug) |
| `bf2bcce` | fix: cursor blink via windowDidBecomeKey: — GCD dispatch blocks can't fire inside dispatch_async |
| `cba514c` | docs: ADR-008/009/010/011 — key Plan 2 architectural findings |
| `e9fa938` | docs: blog post 3 — split pane UI, seven bugs and what they taught us |
| `7e59521` | docs: blog post 4 — seven AppKit bugs, narrative form for end users |
| `67562d5` | docs: blog post 4 — enhanced with tables, ASCII diagrams, and quick reference |
| `6f8770c` | docs: APPKIT_PITFALLS.md — reference guide of hard-won debugging knowledge |

---

### Issue C — "Implement PTY layer and wire claude CLI with passive mode" *(child of MVP epic)*

**Label:** `enhancement`  
**Date range:** 2026-04-04 → 2026-04-05  
**Commits:**

| Hash | Message |
|------|---------|
| `b146f17` | build: add JUnit5 to app-core for PTY tests |
| `22cf9f9` | feat(core): PosixLibrary — Panama FFM downcalls for POSIX PTY functions |
| `72b5e20` | feat(core): PtyProcess — open PTY master/slave pair via POSIX calls |
| `4b101b9` | fix(core): guard arena.close() against double-call in PtyProcess.close() |
| `a692c9a` | fix(core): remove vestigial arena, fix partial-write loop, correct close() javadoc |
| `49a827b` | test(core): PtyProcess spawn, reader, and cat round-trip tests |
| `bcfab71` | test(core): PtyProcess resize and close tests |
| `5b081e6` | feat(macos): wire PtyProcess to UI — /bin/cat round-trip via PTY |
| `69593ef` | build: fix native image — add POSIX downcall signatures and run-time init |
| `92ac37a` | fix: PTY echo suppression and AppKit cross-thread output |
| `001c063` | feat(core): AnsiStripper — strip VT100 escape sequences for NSTextView display |
| `513e43a` | fix(core): narrow AnsiStripper other-escape pattern, add null guard |
| `31629f8` | feat(core): ClaudeLocator — resolve claude binary via login shell |
| `e4b1c04` | fix(core): restore interrupt status on InterruptedException in ClaudeLocator |
| `a91d103` | feat(macos): wire claude CLI via PtyProcess with ANSI stripping |
| `ed1d0d3` | chore: native image validates claude CLI integration |
| `b5ea8e0` | feat(core): InteractionDetector, ClaudeState, PtyProcess.sendSigInt |
| `5bc512f` | feat(bridge): Stop button, passive mode, StopClickedCallback in myui_start |
| `0ccb376` | feat(macos): Panama bindings for StopClickedCallback and setPassiveMode |
| `c63c79f` | feat(macos): wire InteractionDetector — PASSIVE mode, Stop button |
| `977a725` | chore: native image validates passive mode |
| `a996e2b` | docs: blog post 5 — PTY layer, claude wiring, passive mode |
| `8d0fcf2` | docs: ADR-012 through ADR-015 — performSelectorOnMainThread, PTY echo, InteractionDetector, AnsiStripper |
| `e9e9824` | docs: update DESIGN.md — reflects Plans 3-5 implementation (PTY, claude, passive mode) |
| `9c492a3` | docs: comprehensive handoff document for future Claude instances |

---

### Issue D — "Package app as a signed .app bundle" *(child of MVP epic)*

**Label:** `enhancement`  
**Date range:** 2026-04-06  
**Commits:**

| Hash | Message |
|------|---------|
| `15e5590` | docs: Plan 6 — .app bundle packaging |
| `592f5fe` | build: add @rpath to native binary for .app bundle dylib resolution |
| `d4812b4` | feat(macos): add Info.plist for .app bundle |
| `c6f4e7d` | feat(macos): auto-detect dylib path from .app bundle via ProcessHandle |
| `f281026` | feat: bundle.sh — assembles and signs .app bundle |
| `6566d30` | feat(macos): wire bundle.sh into native Maven profile — mvn install -Pnative produces .app |
| `d3548bb` | docs: blog post 6 — .app bundle, test quality review, SIGTRAP diagnosis |

---

### Issue E — "Expand test suite from 26 to 58 tests"

**Label:** `enhancement`  
**Date range:** 2026-04-06  
**Commits:**

| Hash | Message |
|------|---------|
| `42df19a` | test: expand coverage to solid across all 6 classes — 26 → 58 tests |
| `28cf2db` | test: add genuinely high-value tests for resize, sendSigInt + fix JVM crash |

---

### Issue F — "Align blog entries with writing style guide"

**Label:** `documentation`  
**Date range:** 2026-04-05 → 2026-04-06  
**Commits:**

| Hash | Message |
|------|---------|
| `0ffa27f` | docs: restore lost thematic headings, fix bare structural slot in 005 |
| `93b2ec4` | docs: align blog entries with updated style guide — dual headings, integrated closings |
| `a65f795` | docs(blog): align 001 with mandatory rules — metadata, subtitle, Claude intro, paragraph break |
| `fa8f522` | docs(blog): align 002 with mandatory rules — metadata, subtitle, Claude intro |
| `3c04a81` | docs(blog): align 003 with mandatory rules — metadata, subtitle, Claude intro, name Claude in closing |
| `de7a596` | docs(blog): align 004 with mandatory rules — metadata, subtitle, Claude intro, name Claude in closing |
| `072c4d8` | docs(blog): align 005 with mandatory rules — Claude intro before first we, paragraph break |
| `b5ecf87` | refactor(blog): rename to YYYY-MM-DD-NN-title.md convention, fix HANDOFF.md references |

---

## Excluded Commits

These do not warrant a ticket.

| Hash | Message | Reason |
|------|---------|--------|
| `8f23339` | docs: session handover 2026-04-07 session 3 | session bookkeeping |
| `c54db1a` | docs: update CLAUDE.md — ADR count and native build -DskipTests note | trivial config update |
| `c12474d` | docs: add writing style guide pointer to CLAUDE.md | trivial config update |
| `7907ddc` | docs: add design snapshot 2026-04-07-slash-command-passthrough | session artifact |
| `923927b` | docs: session handover 2026-04-07 session 2 | session bookkeeping |
| `9274f42` | docs: add design snapshot 2026-04-07-terminal-resize-complete | session artifact |
| `f20ecaa` | docs: session handover 2026-04-07 | session bookkeeping |
| `7d0f1ea` | docs: session wrap 2026-04-07 — blog, design snapshot, CLAUDE.md update | session bookkeeping |
| `885375b` | docs: session handover 2026-04-06 | session bookkeeping |
| `5a2b4e2` | docs: session handover 2026-04-06 | session bookkeeping |
| `e1cbb53` | docs: add design snapshot 2026-04-06 — architecture after Plans 1-5 | session artifact |
| `7575059` | chore: ignore .worktrees/ directory | trivial |
| `aa30db1` | docs: reference knowledge garden from HANDOFF.md | trivial one-line addition |
| `a33c556` | docs: update HANDOFF.md — Plans 3-6 complete, Plan 6 is next | session bookkeeping |
| `fd7ae24` | chore: strip source map comment from vendored xterm-addon-fit.js | trivial |
| `f343d20` | docs: fix stale no-op comments on myui_evaluate_javascript | trivial comment fix |
| `0260f76` | revert: remove unnecessary Java 25 / --enable-preview from pom.xml | trivial revert |
| `29888a9` | chore: remove debug try/catch from Main.java — appendOutput working | trivial cleanup |

---

## Summary

| | Count |
|-|-------|
| Existing issues (no action) | 7 closed (#2–#8) |
| Existing issues to close | 2 (#1 epic done, #9 work done) |
| New epic | 1 (MVP: Build Claude Desktop CLI MVP) |
| New children (A–D) | 4 |
| New standalones (E, F) | 2 |
| Excluded commits | 18 |
