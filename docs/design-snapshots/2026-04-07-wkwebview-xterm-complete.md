# Claude Desktop CLI — Design Snapshot

**Date:** 2026-04-07
**Topic:** Architecture after Plan 5b (WKWebView + xterm.js terminal renderer)
**Supersedes:** [2026-04-06-current-architecture](2026-04-06-current-architecture.md)
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

The app runs as a GraalVM native binary inside a proper `.app` bundle. It spawns
`claude` in a PTY and renders all output — including full ANSI colour, cursor
movement, and scrollback — in an xterm.js terminal hosted in a WKWebView. The
ObjC bridge detects bundle mode at runtime via `myui_is_bundle()` and falls back
to NSTextView in JVM dev mode (WKWebView requires a bundle). PTY bytes are
base64-encoded and delivered to xterm.js via `evaluateJavaScript`. `AnsiStripper`
has been deleted. The Objective-C + Panama FFM + Quarkus Native architecture
remains unchanged.

---

## How We Got Here

Unchanged from previous snapshot for Plans 1–5. New decisions this session:

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| PTY → xterm.js encoding | base64 + `Uint8Array.from(atob(...))` | Injection-safe; xterm.js handles UTF-8 natively | JSON-escaped string (quoting complexity); raw UTF-8 string (injection risk) |
| WKWebView / NSTextView selection | Runtime detection via `myui_is_bundle()` checking for `Resources/xterm/index.html` | No compile-time flags; works naturally in both modes | Compile-time `#ifdef` (requires two dylib builds); hardcoded mode |
| Page-ready buffering | NSMutableArray buffer flushed in `didFinishNavigation:` | WKWebView page load is async; PTY reader starts before page is ready | Blocking start until page loads (deadlock risk); dropping early output |
| WKWebView entitlements | None required for ad-hoc signing | Tested: WebContent process spawns without entitlements on macOS 15.x | `com.apple.security.cs.allow-jit` (only needed if hardened runtime added later — ADR-016) |
| xterm.js sourcing | Vendored in `mac-ui-bridge/resources/xterm/` | Self-contained build; no internet dependency; pinned to 5.3.0 | CDN (requires network); npm at runtime (too heavy) |

---

## Where We're Going

Plan 5b (WKWebView + xterm.js) is now complete and merged to `main`.

**Open questions:**

- **Slash command overlay** — NSTextField has no overlay buffer; need a transparent
  NSView layered over the split pane or a popup anchored in NSSplitView; deferred
- **NSTextField → NSTextView for input** — multi-line prompts eventually need
  NSTextView with scroll; deferred to v2
- **Terminal resize** — xterm.js is fixed at 120×24; should track window resize
  via `WKScriptMessageHandler` and `ioctl(TIOCSWINSZ)`; not yet planned
- **Hardened runtime / notarisation** — currently ad-hoc signed; adding hardened
  runtime will require `com.apple.security.cs.allow-jit` for WebKit JIT (ADR-016)

**Next milestone:** Slash command overlay (Plan 6) or terminal resize.

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
| ADR-016 | WKWebView works without entitlements under ad-hoc signing |

## Context Links

- Architecture decisions: `DECISIONS.md`
- Current design: `DESIGN.md`
- Implementation plan: `docs/superpowers/plans/2026-04-06-wkwebview-xterm.md`
- GitHub issues: mdproctor/cccli#1–#7 (all closed)
- Blog: `blog/` (entries covering the full journey)
