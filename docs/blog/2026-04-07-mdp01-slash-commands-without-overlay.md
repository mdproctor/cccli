# Claude Desktop CLI — Slash Commands Without the Overlay

**Date:** 2026-04-07
**Type:** phase-update

---

## What I was trying to achieve: handling slash commands without building an overlay

The app needed to handle `/` commands — `/clear`, `/compact`, `/review`. The obvious approach was an overlay: detect the `/`, show a filterable dropdown above the text field, handle keyboard navigation and fuzzy matching. I spent a few minutes researching how other tools (opencode, Gemini CLI) do this before realising I was about to build something unnecessary.

Claude Code already has a slash command TUI. When it receives `/` in the PTY, it renders a filterable command list directly in the terminal. The only problem was that all keystrokes went through the NSTextField — the PTY never saw them until the user pressed Enter.

## The decision that changed everything: let the terminal do it

Route keystrokes directly to the PTY when the user types `/`. Claude Code's TUI handles display, filtering, and selection inside xterm.js. The NSTextField clears; the terminal takes visual focus. When the command is selected or cancelled, routing returns to the text field.

No command list to maintain. No fuzzy matching. No overlay UI.

Exit conditions needed some thought: Enter executes the command (InteractionDetector takes over). Escape or Space sends `\x1b` to the PTY first — to dismiss whatever Claude Code has rendered — then returns to normal input. Backspace forwards `\x7f` to the PTY while characters remain in the buffer, exiting only when the buffer is empty.

## Implementation: InputRouter and the test that contradicted itself

I brought Claude in to build it, dispatching nine tasks as subagents. We built `InputRouter` in `app-core` — a pure Java state machine with two states, constructed with `Consumer<T>` lambdas so it needs no mocking in tests:

```java
public InputRouter(Consumer<String>  writeToPty,
                   Consumer<Boolean> setSlashMode,
                   Consumer<String>  setInputText) { ... }
```

Fifteen JUnit tests, no Quarkus overhead. On the ObjC side: an NSEvent local monitor installed once at startup and controlled by a `BOOL slashModeActive` flag. The monitor's cost when inactive is a single branch.

The spec review caught a problem I'd written into the tests. Two backspace cases were contradictory: type `/c`, press backspace → stay in slash mode; type `/cl`, press backspace twice → exit. Both have `bufferCount == 1` when the decisive backspace fires, yet expect opposite results. Claude's implementation resolved this with a `hadMultipleChars` boolean — a workaround for a test that was wrong.

The spec said: exit only when the user tries to backspace past the initial `/`, meaning `bufferCount` is already 0. A user who typed `/cl` needs three backspaces to exit, not two. We removed `hadMultipleChars`, corrected the test, and the logic simplified to two branches.

A code quality review caught one more thing: the NSEvent monitor was consuming Cmd+Q, Cmd+W, and other system shortcuts during slash mode. A modifier guard fixed it before it shipped:

```objc
NSEventModifierFlags mods = event.modifierFlags &
    (NSEventModifierFlagCommand | NSEventModifierFlagOption |
     NSEventModifierFlagControl);
if (mods) return event;
```

73 tests pass. The feature is on `main`.

## The seam that's still there

During normal text input, two input areas are visible: Claude Code's empty prompt line inside xterm.js and our NSTextField. The fix is speculative for now — report `N+1` PTY rows so Claude Code renders its input line below the WKWebView's visible area, with the NSTextField sitting in that hidden row's physical space. Whether Claude Code reliably renders its prompt on the last row is something we'd need to run to find out.
