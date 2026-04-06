# Handover — 2026-04-06

**Head commit:** `d3548bb` — docs: blog post 6 — .app bundle, test quality review, SIGTRAP diagnosis
**Previous handover:** `git show HEAD~1:docs/HANDOFF.md`

## What Changed This Session

- **Plan 6 (.app bundle) complete** — `@rpath` in native binary, `MacUIBridge.resolveDylibPath(executablePath)` auto-detects bundle path via `ProcessHandle`, `scripts/bundle.sh` assembles and signs, wired into `mvn install -Pnative`. App runs without `-Dcccli.dylib.path`.
- **Test suite: 26 → 60 tests** — expanded across all 6 classes to solid. New high-value tests: `echoFlagIsDisabledAfterOpen`, `sendSigIntIsDeliveredToProcess` (via `waitpid(WNOHANG)` polling), `resizeSetsTerminalDimensions`, full `MacUIBridgeTest` (3 resolution branches). Filler tests retained but annotated.
- **JVM crash diagnosed and fixed** — Panama FFM `write()`/`read()` on PTY slave fds corrupts downcall state on macOS AArch64; next test class hits SIGTRAP (exit 133). Fixed with `reuseForks=false` in surefire.
- **Blog entry 006** — `blog/2026-04-06-01-packaging-test-quality.md`
- **Garden submissions** — 3 new: Panama FFM PTY SIGTRAP crash, `waitpid(WNOHANG)` signal verification technique, surefire `reuseForks=false` for native code

## State Right Now

Plan 6 complete. 60 tests passing. `.app` bundle builds and runs — no system property needed. Plan 5b (WKWebView + xterm.js) is now unblocked.

## Immediate Next Step

Write Plan 5b implementation plan: `docs/superpowers/plans/2026-04-06-wkwebview-xterm.md`

Key tasks: enable WKWebView in ObjC bridge (swap NSTextView output pane), bundle xterm.js as a resource in `.app`, implement `myui_evaluate_javascript()` (currently a no-op), route PTY bytes via `evaluateJavaScript("window.term.write(...)")`, remove `AnsiStripper`.

## Open Questions / Blockers

- WKWebView codesigning entitlements — needed for WebKit subprocess IPC in `.app`; validate during Plan 5b
- NSTextField → NSTextView for multi-line input (deferred, not blocking)

## References

| Context | Where | Retrieve with |
|---------|-------|---------------|
| Design state | `docs/design-snapshots/2026-04-06-current-architecture.md` | `cat` that file |
| Architecture decisions | `DECISIONS.md` (ADR-001 to ADR-015) | `cat` that file |
| Current design | `DESIGN.md` | `cat` that file |
| Latest blog entry | `blog/2026-04-06-01-packaging-test-quality.md` | `cat` that file |
| Technical gotchas | `~/claude/knowledge-garden/GARDEN.md` | index only; detail on demand |
| AppKit pitfalls | `docs/APPKIT_PITFALLS.md` | read before any ObjC debugging |
| Plans | `docs/superpowers/plans/` | `ls` to list |

## Environment

- `surefire reuseForks=false` now required in `app-core/pom.xml` — Panama FFM PTY I/O corrupts JVM state between test classes on macOS AArch64
- Bundle at `app-macos/target/Claude Desktop CLI.app` after `mvn install -Pnative`
