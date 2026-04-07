# Handover — 2026-04-07 (session 3)

**Head commit:** `679138e` — docs: add blog entry 2026-04-07-mdp01-slash-commands-without-overlay
**Previous handover:** `git show HEAD~1:docs/HANDOFF.md`

## What Changed This Session

- **ADR-017 and ADR-018 written** — firstVariadicArg for variadic ioctl, and the terminal resize pipeline decision. Both in `DECISIONS.md`.
- **Slash command passthrough shipped** — `#9` closed. Type `/` → NSTextField clears → keystrokes route to PTY → Claude Code's TUI handles display and selection. `InputRouter` state machine in `app-core`, NSEvent monitor in `MyMacUI.m`. 73/73 tests.
- **Key fix during review:** contradictory backspace tests fixed (needed 3 backspaces not 2 to exhaust buffer); modifier key guard added to NSEvent monitor (Cmd/Option/Ctrl pass through during slash mode).
- **Native build:** must use `-DskipTests` — PtyProcessTest crashes with GraalVM 25 (exit 133). CLAUDE.md updated.
- **Garden:** GE-0072 (`performSelectorOnMainThread:NO` from main thread is async), GE-0073 (NSEvent monitor + BOOL flag pattern). Both submitted.

## State Right Now

73 tests passing. Slash command passthrough on `main`. No open issues.

## Immediate Next Step

Try the **hidden-row experiment**: report `N+1` PTY rows to Claude Code (via the resize callback) so its input line renders below the WKWebView visible area. The NSTextField sits where that hidden row would be — two inputs become one visual surface. One-line change in `Main.java` resize callback: `pty.resize(rows + 1, cols)`. Try it and see if Claude Code reliably renders its prompt on the last row.

## Open Questions / Blockers

- Does Claude Code always render its input on the last PTY row? (Must verify before the hidden-row approach is viable.)
- How many rows does Claude Code use for status info below the input line? (Determines if `+1` is enough or `+2` needed.)

## Environment

*Unchanged — `git show HEAD~1:docs/HANDOFF.md`*

## References

| Context | Where |
|---------|-------|
| Design state | `docs/design-snapshots/2026-04-07-slash-command-passthrough.md` |
| Previous design state | `docs/design-snapshots/2026-04-07-terminal-resize-complete.md` |
| Architecture decisions | `DECISIONS.md` (ADR-001 to ADR-018) |
| Latest blog | `docs/blog/2026-04-07-mdp01-slash-commands-without-overlay.md` |
| AppKit pitfalls | `docs/APPKIT_PITFALLS.md` |
| GitHub issues | mdproctor/cccli |
