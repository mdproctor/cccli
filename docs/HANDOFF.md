# Handover — 2026-04-07

**Head commit:** `7d0f1ea` — docs: session wrap 2026-04-07 — blog, design snapshot, CLAUDE.md update
**Previous handover:** `git show HEAD~1:docs/HANDOFF.md`

## What Changed This Session

- **Plan 5b complete and merged** — WKWebView + xterm.js replaces NSTextView in production (`.app` bundle). Runtime detection via `myui_is_bundle()` (checks for `Resources/xterm/index.html` in bundle). Page-ready buffer flushes PTY output after `didFinishNavigation:`. Base64 encoding for safe PTY byte transfer. `AnsiStripper` deleted.
- **GitHub repo created** — mdproctor/cccli (public). Issue tracking enabled. Epic #1 (WKWebView + xterm.js) with issues #2–#7 all closed.
- **`github-epic-workflow` skill designed** — text written for the skills Claude; creates epics + child issues before implementation, handles ad-hoc tasks during sprints with user confirmation.
- **ADR-016** — WKWebView works without entitlements under ad-hoc signing (verified via WebContent process detection). In `DECISIONS.md`.
- **Subagent execution lesson** — implementer subagents consistently conflated plan task numbers with GitHub issue numbers in commit messages; spec review caught it 3 times. Worth noting for future plan authoring: make issue numbers explicit.
- **GraalVM build fix** — `mvn install -Pnative` requires `JAVA_HOME` pointing at GraalVM 25 (`/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home`), not just any Java 22. Documented in CLAUDE.md.
- **GE-0051 submitted** — WKWebView headless smoke-testing via WebContent process detection.

## State Right Now

Plan 5b complete. 51 tests passing. `.app` bundle builds with `mvn install -Pnative` (GraalVM required) and runs with full colour xterm.js terminal. All Plan 5b GitHub issues closed.

## Immediate Next Step

Decide on the next plan: **slash command overlay** (NSTextField overlay for `/` commands) or **terminal resize** (wire window resize events to `ioctl(TIOCSWINSZ)` + `term.resize()` in xterm.js). Neither is planned yet — pick one and write a plan.

## Open Questions / Blockers

- Slash command overlay — transparent NSView over split pane vs popup? Architecture unclear; needs brainstorming before planning.
- Terminal resize — needs `WKScriptMessageHandler` to pass dimensions from JS to Java; straightforward but not planned.
- Hardened runtime / notarisation — if added, needs `com.apple.security.cs.allow-jit` for WebKit JIT (ADR-016).
- `github-epic-workflow` skill — drafted but not yet installed in the skills Claude; needs to be handed off.

## References

| Context | Where | Retrieve with |
|---------|-------|---------------|
| Design state | `docs/design-snapshots/2026-04-07-wkwebview-xterm-complete.md` | `cat` that file |
| Architecture decisions | `DECISIONS.md` (ADR-001 to ADR-016) | `cat` that file |
| Current design | `DESIGN.md` | `cat` that file |
| Latest blog entry | `blog/2026-04-07-01-wkwebview-xterm-issues.md` | `cat` that file |
| Plan 5b (completed) | `docs/superpowers/plans/2026-04-06-wkwebview-xterm.md` | reference only |
| Technical gotchas | `~/claude/knowledge-garden/GARDEN.md` | index only; detail on demand |
| AppKit pitfalls | `docs/APPKIT_PITFALLS.md` | read before any ObjC debugging |
| GitHub issues | mdproctor/cccli | `gh issue list` |

## Environment

- Native builds require GraalVM 25: `JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home mvn install -Pnative`
- JVM tests use Java 22: `JAVA_HOME=$(/usr/libexec/java_home -v 22) mvn test`
- `surefire reuseForks=false` required in `app-core/pom.xml` — Panama FFM PTY I/O corrupts JVM state between test classes on macOS AArch64
