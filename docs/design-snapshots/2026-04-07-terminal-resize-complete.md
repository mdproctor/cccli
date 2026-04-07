# Claude Desktop CLI — Design Snapshot
**Date:** 2026-04-07
**Topic:** Terminal resize — FitAddon + WKScriptMessageHandler + TIOCSWINSZ
**Supersedes:** [2026-04-07-wkwebview-xterm-complete](2026-04-07-wkwebview-xterm-complete.md)
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

Terminal grid dimensions now track the window size end-to-end. When the user resizes the window, AppKit fires `windowDidResize:`, which evaluates `fitAddon.fit()` in the WKWebView via `requestAnimationFrame`. FitAddon computes exact cols/rows from the rendered character cell and fires `term.onResize`, which posts `{cols, rows}` via `WKScriptMessageHandler`. The registered `WindowResizedCallback` C function pointer calls `pty.resize(rows, cols)` on the Java side, sending `TIOCSWINSZ` to the kernel. The hardcoded 120×24 startup size is gone; FitAddon drives the initial size at page-ready time. A critical Panama FFM bug was also fixed: `ioctl()` was missing `Linker.Option.firstVariadicArg(2)`, causing `TIOCSWINSZ` to silently operate on garbage memory since day one.

## How We Got Here

Key decisions made to reach this point, in rough chronological order.

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Dimension computation | FitAddon (JS) | Only xterm.js knows the rendered char cell size at runtime | Java pixel math (fragile, font-dependent); xterm.js internal API (breaks on upgrades) |
| JS→Java bridge | WKScriptMessageHandler `"termSize"` | Only correct way to get async data from JS back to Java in WKWebView | Polling; synchronous JS evaluation (blocked) |
| Obj-C→Java callback | New `WindowResizedCallback` C function pointer via `myui_set_resize_callback()` | Consistent with existing callback pattern; avoids GCD | NSNotification; new Obj-C method on delegate |
| Window resize timing | `requestAnimationFrame(()=>fitAddon.fit())` | Defers until after WKWebView layout reflow completes | Direct `fitAddon.fit()` call (reads stale offsetWidth during live resize) |
| Message body validation | `isKindOfClass:[NSDictionary class]` + `cols>0 && rows>0` guard | Prevents crash on non-dict postMessage; prevents spurious zero-dim PTY resize during FitAddon teardown | No guard (fragile) |
| ioctl variadic fix | `Linker.Option.firstVariadicArg(2)` on IOCTL handle | AArch64 ABI requires this for all variadic functions; without it the pointer arg lands in the wrong register | None — this is the correct fix per Panama FFM docs |

## Where We're Going

**Next steps:**
- Slash command overlay — `/` command input overlay over the terminal. Architecture needs brainstorming (transparent NSView vs popup).
- Hardened runtime / notarisation — if added, needs `com.apple.security.cs.allow-jit` for WebKit JIT (ADR-016).

**Open questions:**
- Does `windowDidResize:` behave correctly during live drag resize on all macOS versions, or are there edge cases where rAF coalesces differently at high refresh rates?
- `tput cols`/`tput lines` verification in tests requires `TERM=xterm-256color` injected via the `spawn(String[], String[])` env override — should this be a first-class test utility rather than repeated inline?

## Linked ADRs

| ADR | Decision |
|---|---|
| [ADR-016](../../DECISIONS.md) | WKWebView works without entitlements under ad-hoc signing |

*(The `firstVariadicArg(2)` Panama FFM fix and the `WindowResizedCallback` design do not yet have ADRs — candidates for creation.)*

## Context Links

- Design spec: `docs/superpowers/specs/2026-04-07-terminal-resize-design.md`
- Implementation plan: `docs/superpowers/plans/2026-04-07-terminal-resize.md`
- GitHub issue: mdproctor/cccli#8 (closed)
- Garden entries: GE-0053 (Panama FFM IOC_OUT silent failure), GE-0059 (REVISE: firstVariadicArg fix), GE-0060 (tput TERM requirement), GE-0061 (tput PTY verification technique)
