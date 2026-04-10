# Claude Desktop CLI — Design

> Current implemented architecture. For product vision and technology rationale, see `VISION.md`.
> All architectural decisions are in `DECISIONS.md` (ADR-001 through ADR-018+).

---

## Module Structure

```
claude-desktop/
├── pom.xml              ← parent Maven POM
├── mac-ui-bridge/       ← Objective-C: MyMacUI.dylib + xterm.js resources
│   ├── include/MyMacUI.h
│   ├── MyMacUI.m
│   ├── Makefile
│   └── resources/xterm/ ← vendored xterm.js 5.3.0 + index.html
├── app-core/            ← Java: PTY, session, interaction, InputRouter (no UI dependency)
└── app-macos/           ← Java: Panama bindings, Quarkus wiring, .app packaging
```

**Rule:** `app-core` has zero dependency on Panama FFM, AppKit, or the dylib. All public interfaces are plain Java. `app-macos` depends on `app-core` only.

---

## Technology Stack

| Concern | Technology |
|---------|-----------|
| Native macOS UI | AppKit via Objective-C bridge (MyMacUI.dylib) |
| Java ↔ native bridge | Panama FFM API + jextract |
| Terminal renderer | WKWebView + xterm.js 5.3.0 (vendored; requires .app bundle) |
| Terminal renderer (dev fallback) | NSTextView — active when `myui_is_bundle()` returns false |
| PTY → xterm.js encoding | Base64: `evaluateJavaScript("write(base64decode(...))")` — injection-safe, UTF-8 native |
| Input pane | NSTextField (single-line native text field) |
| Slash command routing | `InputRouter` Java state machine + NSEvent local monitor in ObjC |
| Terminal resize | FitAddon (JS) → WKScriptMessageHandler → `WindowResizedCallback` → `ioctl(TIOCSWINSZ)` |
| PTY management | Panama FFM → POSIX libc |
| Java runtime | Quarkus Native (GraalVM/Mandrel) — ~0.020s startup |
| Packaging | macOS .app bundle in `Contents/` — required for WKWebView subprocess IPC |

---

## AppKit Bridge Primitives (mac-ui-bridge)

A minimal, tailored bridge — not a generic AppKit wrapper.

| Primitive | AppKit control | Purpose |
|-----------|---------------|---------|
| Main window | NSWindow | App shell |
| Terminal pane | WKWebView (bundle) / NSTextView (dev) | Renders PTY output via xterm.js |
| Input pane | NSTextField | Single-line native text input |
| Stop button | NSButton | Overlaid on input pane; fires `StopClickedCallback` on click |
| Passive mode | `myui_set_passive_mode(int)` | Disables input field; shows/hides Stop button |
| JS evaluation | `myui_evaluate_javascript(const char*)` | Calls `WKWebView evaluateJavaScript:` — used for PTY byte delivery and page-ready flush |
| Resize callback | `myui_set_resize_callback(WindowResizedCallback)` | Registered at startup; fires on `windowDidResize:` after FitAddon reports new dims |
| NSEvent monitor | Installed at startup in ObjC; BOOL flag toggles it | Intercepts keystrokes during `SLASH_PASSTHROUGH` and forwards to PTY |

---

## C ABI (mac-ui-bridge/include/MyMacUI.h)

| Function | Signature | Purpose |
|----------|-----------|---------|
| `myui_start` | `(title, w, h, html, onClosed, onTextSubmitted, onStop)` | 7-param entry point; blocks until app terminates |
| `myui_append_output` | `(const char*)` | Thread-safe; uses `performSelectorOnMainThread:` |
| `myui_set_passive_mode` | `(int passive)` | Thread-safe; disables/enables input + Stop button |
| `myui_evaluate_javascript` | `(const char*)` | Evaluates JS in WKWebView on main thread |
| `myui_is_bundle` | `(void) → int` | Returns 1 if running inside .app bundle (checks for Resources/xterm/index.html) |
| `myui_set_resize_callback` | `(WindowResizedCallback)` | Registers callback fired on `windowDidResize:` with cols/rows from FitAddon |

**Callbacks:**
- `WindowClosedCallback` — `void(*)(void)` — window close
- `TextSubmittedCallback` — `void(*)(const char*)` — Enter pressed in NSTextField
- `StopClickedCallback` — `void(*)(void)` — Stop button clicked
- `WindowResizedCallback` — `void(*)(int cols, int rows)` — terminal dimensions changed

---

## app-core Components

| Class | Purpose |
|-------|---------|
| `PosixLibrary` | 17+ POSIX function handles via Panama FFM downcalls |
| `PtyProcess` | PTY lifecycle: open/spawn/read/write/resize/sendSigInt/close |
| `ClaudeLocator` | Resolves `claude` binary via `/bin/zsh -l -c 'which claude'` |
| `ClaudeState` | Enum: `FREE_TEXT`, `PASSIVE`, `SLASH_PASSTHROUGH` |
| `InteractionDetector` | Timer-based mode transitions: PTY output → PASSIVE; 800ms quiet → FREE_TEXT |
| `InputRouter` | Pure Java state machine — routes NSTextField input; manages `SLASH_PASSTHROUGH` mode |

`AnsiStripper` was deleted when xterm.js replaced NSTextView as the terminal renderer.

**PTY POSIX calls:**

| Call | Purpose |
|------|---------|
| `posix_openpt` | Open PTY master |
| `grantpt` / `unlockpt` | Grant and unlock slave |
| `ptsname` | Get slave device path |
| `tcgetattr` / `tcsetattr` | Clear ECHO flag on master fd (prevents double output) |
| `posix_spawn` | Launch `claude` with slave as stdin/stdout/stderr |
| `ioctl(TIOCSWINSZ)` | Forward window resize to PTY — requires `Linker.Option.firstVariadicArg(2)` (ADR-017) |
| `read` / `write` | Byte I/O on master fd |
| `kill(SIGINT)` | Send Ctrl+C to claude subprocess (Stop button) |

---

## Data Flow

### PTY → Terminal

```
PTY master fd
    │ raw bytes
    ▼
PtyProcess reader thread (app-core)
    ├──→ base64-encode → myui_evaluate_javascript("write(base64decode(...))") → xterm.js  [bundle mode]
    ├──→ AnsiStripper → MacUIBridge.appendOutput() → NSTextView                            [dev fallback only]
    └──→ InteractionDetector → ClaudeState → MacUIBridge.setPassiveMode()
```

Page-ready buffering: PTY reader may start before WKWebView page load completes.
Output is buffered in `NSMutableArray` and flushed in `didFinishNavigation:`.

### User Input → PTY

```
NSTextField (Enter)
    │ text
    ▼
Panama upcall → InputRouter.onTextSubmit()
    ├──→ FREE_TEXT mode: PtyProcess.write(text + "\n")
    └──→ SLASH_PASSTHROUGH mode: PtyProcess.write(text + "\n") + exit SLASH_PASSTHROUGH

NSTextField (/ typed)
    │
    ▼
InputRouter.onKeyChar('/') → enter SLASH_PASSTHROUGH
    │
    ▼
NSEvent local monitor active → keystrokes forwarded directly to PTY
    ├── Enter → execute command + exit SLASH_PASSTHROUGH
    ├── Escape / Space → send \x1b to PTY (abort TUI) + exit SLASH_PASSTHROUGH
    └── Backspace (buffer empty) → exit SLASH_PASSTHROUGH
```

### Terminal Resize

```
User resizes window
    │
    ▼
windowDidResize: (ObjC)
    │ requestAnimationFrame(()=>fitAddon.fit())   ← defers until layout reflow
    ▼
FitAddon computes cols/rows from rendered char cell
    │ term.onResize → postMessage({cols, rows}) via WKScriptMessageHandler "termSize"
    ▼
ObjC userContentController:didReceiveScriptMessage:
    │ validates: isKindOfClass:NSDictionary, cols>0, rows>0
    ▼
WindowResizedCallback(cols, rows)
    │
    ▼
PtyProcess.resize(rows, cols) → ioctl(TIOCSWINSZ) with firstVariadicArg(2)
```

FitAddon also drives the **initial** terminal size at page-ready time — the hardcoded 120×24 startup size is gone.

---

## Interaction Modes

| Mode | Input surface | Status |
|------|--------------|--------|
| `FREE_TEXT` | NSTextField (enabled) | ✅ Implemented |
| `PASSIVE` | NSTextField (disabled) + Stop button | ✅ Implemented |
| `SLASH_PASSTHROUGH` | NSTextField + NSEvent monitor forwards to PTY | ✅ Implemented — Claude Code's own TUI renders the command list inside xterm.js; no native overlay |
| `LIST_SELECTION` | List view | Planned |
| `FREE_TEXT_ANSWER` | NSTextField with context label | Planned |
| `CONFIRMATION` | Yes/No control | Planned |
| `PASSTHROUGH` | Full terminal focus | Planned |

---

## Key Design Decisions

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| UI framework | Objective-C bridge (MyMacUI.dylib) via Panama FFM | Clean C ABI, GraalVM native-image-safe, minimal ObjC | JavaFX, SWT (uses JNA), Swift (unstable ABI), JNI — ADR-001 |
| Terminal renderer | WKWebView + xterm.js (prod) / NSTextView (dev fallback) | WKWebView requires .app bundle; NSTextView works in JVM dev mode | Custom NSView VT100 — ADR-002, ADR-010 |
| PTY management | Panama FFM → POSIX libc | Same mechanism as AppKit bridge; GraalVM-safe; no extra deps | pty4j (uses JNA) — ADR-003 |
| Java runtime | Quarkus Native (GraalVM) | Fast startup, native binary, familiar toolchain | Plain GraalVM, Spring Native — ADR-004 |
| Bridge language | Objective-C | Stable C ABI; jextract works from .h headers directly | Swift (unstable ABI, name-mangled) — ADR-005 |
| PTY → xterm.js encoding | Base64 + `Uint8Array.from(atob(...))` | Injection-safe; xterm.js handles UTF-8 natively | JSON-escaped string (quoting complexity); raw UTF-8 (injection risk) |
| Bundle detection | Runtime `myui_is_bundle()` checking for `Resources/xterm/index.html` | No compile-time flags; works naturally in both modes | Compile-time `#ifdef` (requires two dylib builds) |
| Page-ready buffering | NSMutableArray buffer flushed in `didFinishNavigation:` | WKWebView page load is async; PTY reader starts before page ready | Blocking until page loads (deadlock risk) |
| WKWebView entitlements | None required for ad-hoc signing | Tested: WebContent process spawns without entitlements on macOS 15.x | `com.apple.security.cs.allow-jit` (only needed if hardened runtime added) — ADR-016 |
| xterm.js sourcing | Vendored in `mac-ui-bridge/resources/xterm/`, pinned to 5.3.0 | Self-contained build; no internet dependency | CDN (requires network); npm at runtime |
| Terminal resize dimension source | FitAddon (JS) | Only xterm.js knows the rendered char cell size at runtime | Java pixel math (fragile, font-dependent); xterm.js internal API (breaks on upgrades) |
| JS→Java resize bridge | WKScriptMessageHandler `"termSize"` | Only correct way to get async data from JS back to Java in WKWebView | Polling; synchronous JS evaluation (blocked) |
| Resize timing | `requestAnimationFrame(()=>fitAddon.fit())` | Defers until after WKWebView layout reflow completes | Direct `fitAddon.fit()` call (reads stale offsetWidth during live resize) |
| `ioctl` variadic fix | `Linker.Option.firstVariadicArg(2)` on IOCTL handle | AArch64 ABI requires this for all variadic functions; without it the pointer arg lands in the wrong register — ADR-017 | None — this is the correct fix per Panama FFM docs |
| Slash command approach | Reuse Claude Code's TUI via PTY passthrough | Claude Code already renders the command list; building an overlay duplicates that work | NSPanel overlay; HTML overlay in WKWebView |
| InputRouter | Pure Java state machine with `Consumer<T>` constructor injection | Fully testable with plain JUnit — no Mockito, no native deps | Coupling to PtyProcess/MacUIBridge directly |
| NSEvent monitor | Install once at startup, BOOL flag toggles | No risk of accumulating monitors from repeated entry/exit | Install/remove per mode entry/exit |
| Slash exit on Space | Space = abort, send `\x1b` to PTY | Slash commands are single words; space signals user is not selecting a command | Arrow-key monitoring; parsing PTY output |
| Backspace exit | Exit only when `bufferCount == 0` | One more backspace after all chars deleted signals "undo the slash" | Exit when `bufferCount` reaches 1 (one backspace too early) |
| Arrow key conversion | ObjC converts `NSUpArrowFunctionKey` → `\x1b[A` | InputRouter stays stack-agnostic; Java only sees UTF-8 strings | Conversion in Java InputRouter |
| Interaction detection | Timer-based: 800ms quiet → FREE_TEXT | Simple, reliable, format-agnostic | Pattern-matching on Claude's prompt strings (fragile) |
| Claude binary resolution | `ClaudeLocator` via `/bin/zsh -l -c 'which claude'` | Picks up shell profile PATH (Homebrew, nvm, ~/.local/bin) | Hardcoded path; file picker |

---

## ADRs

All ADRs are in `DECISIONS.md`.

| ADR | Decision |
|---|---|
| ADR-001 | Objective-C bridge via Panama FFM as UI framework |
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
| ADR-016 | WKWebView works without entitlements under ad-hoc signing |
| ADR-017 | Panama FFM `firstVariadicArg(2)` required for variadic ioctl downcalls |
| ADR-018 | Terminal resize pipeline: FitAddon as authoritative rows/cols source via WKScriptMessageHandler |

**ADR candidates (not yet written):**
- `WindowResizedCallback` design and registration pattern
- Page-ready buffering pattern (NSMutableArray + didFinishNavigation:)
- InputRouter pure-Java state machine (Consumer injection for testability)

---

## Next Steps

- **Hardened runtime / notarisation** — if added, needs `com.apple.security.cs.allow-jit` for WebKit JIT (ADR-016)
- **Hidden-row experiment** — set WKWebView height to `N * lineHeight`, report `N+1` rows to PTY; NSTextField sits in the hidden row's physical space; makes NSTextField the only visible input surface
- **Argument completion** — after selecting a slash command that takes arguments, `/compact ` (trailing space) could trigger a follow-on UI
- **`@` mention passthrough** — same `InputRouter` pattern could handle `@` file/agent mentions if Claude Code's TUI supports them
- **NSTextField → NSTextView for input** — multi-line prompts eventually need NSTextView with scroll; deferred to v2

---

## Open Questions

- Does `windowDidResize:` behave correctly during live drag resize on all macOS versions, or are there edge cases where `requestAnimationFrame` coalesces differently at high refresh rates?
- `tput cols`/`tput lines` verification in tests requires `TERM=xterm-256color` injected via the `spawn(String[], String[])` env override — should this be a first-class test utility rather than repeated inline?
- Does Claude Code always render its prompt/input on the last PTY row, or does it shift based on output length? (Required to validate the hidden-row experiment.)
- How many rows does Claude Code use for status/info below the input line? (Determines K in the `N+K` PTY row trick.)
