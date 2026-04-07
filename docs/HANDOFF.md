# Handover — 2026-04-07 (session 2)

**Head commit:** `198323c` — docs: add blog entry 2026-04-07-02-terminal-resize-ioctl-bug
**Previous handover:** `git show HEAD~1:docs/HANDOFF.md`

## What Changed This Session

- **Terminal resize complete** — FitAddon + WKScriptMessageHandler + TIOCSWINSZ pipeline. `windowDidResize:` → `requestAnimationFrame(fitAddon.fit())` → `term.onResize` → `WKScriptMessageHandler "termSize"` → `WindowResizedCallback` → `pty.resize(rows, cols)`. Hardcoded 120×24 gone.
- **Critical Panama FFM bug fixed** — `ioctl()` was missing `Linker.Option.firstVariadicArg(2)`. `TIOCSWINSZ` was silently operating on garbage addresses since day one. Found by tput integration tests.
- **58 tests passing** — 7 new: 3 tput (verify kernel path), 3 TIOCGWINSZ API contract, 1 bridge smoke.
- **CLAUDE.md updated** — test command now `jenv shell 26 && mvn test` (Java 22 not installed; JDK 26 via jenv is correct).
- **Garden entries** — GE-0053 (Panama FFM IOC_OUT silent failure), GE-0059 (REVISE: firstVariadicArg fix), GE-0060 (tput TERM env requirement), GE-0061 (tput PTY dimension technique).
- **GitHub issue #8 closed.**

## State Right Now

Terminal resize shipped. 58/58 tests passing. `jenv shell 26 && mvn test` from root.

## Immediate Next Step

Decide on and brainstorm the **slash command overlay** — `/` command input over the terminal. Architecture still unclear: transparent NSView overlay vs popup. Needs a brainstorm session before planning.

## Open Questions / Blockers

- Slash command overlay — transparent NSView vs popup? Needs brainstorming.
- `firstVariadicArg(2)` and `WindowResizedCallback` decisions have no ADRs yet.
- Hardened runtime / notarisation — needs `com.apple.security.cs.allow-jit` if added.

## Environment

- Tests: `jenv shell 26 && mvn test` (JDK 26 via jenv; Java 22 not installed)
- Native builds: `JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home mvn install -Pnative`
- `surefire reuseForks=false` required in `app-core/pom.xml`

## References

| Context | Where |
|---------|-------|
| Design state | `docs/design-snapshots/2026-04-07-terminal-resize-complete.md` |
| Architecture decisions | `DECISIONS.md` (ADR-001 to ADR-016) |
| Current design | `DESIGN.md` |
| Latest blog | `blog/2026-04-07-02-terminal-resize-ioctl-bug.md` |
| AppKit pitfalls | `docs/APPKIT_PITFALLS.md` — read before any ObjC debugging |
| GitHub issues | mdproctor/cccli |
