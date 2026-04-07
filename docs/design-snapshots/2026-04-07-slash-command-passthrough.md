# Claude Desktop CLI — Design Snapshot
**Date:** 2026-04-07
**Topic:** Slash command passthrough — InputRouter and NSEvent monitor
**Supersedes:** *(none — new topic)*
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

Slash command passthrough is shipped. When the user types `/` in the NSTextField, `InputRouter` (a pure Java state machine in `app-core`) switches to `SLASH_PASSTHROUGH` mode: all subsequent keystrokes are intercepted by an NSEvent local monitor in `MyMacUI.m` and forwarded directly to the PTY. Claude Code's own TUI renders and filters the slash command list inside the xterm.js terminal — no native overlay is needed. Exit conditions are Enter (execute), Escape/Space (abort, sends `\x1b` to clean up the TUI), and Backspace-to-empty (abort when buffer count reaches zero). 73 tests pass; native bundle builds with `-DskipTests`.

## How We Got Here

Key decisions made to reach this point, in rough chronological order.

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| No native slash command overlay | Reuse Claude Code's TUI | Claude Code already renders the command list; building an overlay duplicates that work | NSPanel overlay with fuzzy matching; HTML overlay in WKWebView |
| Exit condition: Space | Space = abort, send `\x1b` to PTY | Slash commands are single words; space signals the user is not selecting a command | Arrow-key monitoring; parsing PTY output to detect "no match" |
| InputRouter as pure Java state machine | `Consumer<T>` constructor injection | Fully testable with plain JUnit — no Mockito, no native deps | Coupling to PtyProcess/MacUIBridge directly |
| NSEvent monitor: install once, BOOL flag | Install at startup, flag toggles it | No risk of accumulating monitors from repeated entry/exit | Install/remove per mode entry/exit |
| Backspace exit: bufferCount==0 | Exit only when buffer is truly empty | One more backspace after all chars deleted signals "undo the slash" | Exit when bufferCount reaches 1 (one backspace too early — caught by spec review) |
| Modifier key pass-through | Cmd/Option/Ctrl events bypass the monitor | System shortcuts must remain functional during slash mode | Consuming all events unconditionally |
| Arrow key → ANSI conversion in ObjC | ObjC converts `NSUpArrowFunctionKey` → `\x1b[A` | InputRouter stays stack-agnostic; Java only sees UTF-8 strings | Conversion in Java InputRouter |

## Where We're Going

The hidden-row experiment was discussed but not implemented: reporting `N+1` PTY rows to Claude Code so its input line renders below the WKWebView's visible area, making the NSTextField the only visible input surface.

**Next steps:**
- Hidden-row experiment: set WKWebView height to `N * lineHeight`, report `N+1` rows to PTY. NSTextField sits in the hidden row's physical space. Requires trying it to know if Claude Code reliably renders its input on the last row.
- Argument completion: after selecting a slash command that takes arguments, `/compact ` (trailing space) could trigger a follow-on UI (label or context prompt).
- `@` mention passthrough: the same InputRouter pattern could handle `@` file/agent mentions if Claude Code's TUI supports them.

**Open questions:**
- Does Claude Code always render its prompt/input on the last PTY row, or does it shift based on output length? (Required to validate the hidden-row experiment.)
- How many rows does Claude Code use for status/info below the input line? (Determines K in the `N+K` PTY row trick.)

## Linked ADRs

| ADR | Decision |
|---|---|
| ADR-017 — Panama FFM firstVariadicArg | Required for variadic ioctl downcalls; silent AArch64 data corruption without it |
| ADR-018 — Terminal resize pipeline | xterm.js FitAddon as authoritative rows/cols source via WKScriptMessageHandler |

*(ADR-017 and ADR-018 are in `DECISIONS.md` — this project uses a single ADR log, not separate files.)*

## Context Links

- Design spec: `docs/superpowers/specs/2026-04-07-slash-command-passthrough-design.md`
- Implementation plan: `docs/superpowers/plans/2026-04-07-slash-command-passthrough.md`
- GitHub issue: mdproctor/cccli#9 (closed)
