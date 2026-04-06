# Claude Desktop CLI — Vision
# ARCHIVED — original JavaFX/JediTerm approach, superseded by Objective-C bridge + Quarkus Native design.

> This document captures the product vision, founding design decisions, and long-term roadmap. It explains the *why* behind the architecture — the trade-offs considered, the paths not taken, and the directions worth pursuing. It is intended to be stable and rarely edited.
>
> For the current implemented architecture, see `DESIGN.md`.

## Overview

A macOS desktop application that wraps the Claude Code CLI with a superior input/output experience. The top pane is a full terminal emulator (JediTerm) running the actual `claude` subprocess unmodified. The bottom pane is a native JavaFX text input with full OS-quality editing. The two surfaces communicate cleanly, with the app detecting CLI interaction modes and upgrading the input surface accordingly — but always with a toggle to fall back to raw terminal mode if anything goes wrong.

---

## Core Design Principles

1. **Claude Code runs unmodified** — we spawn it as a PTY subprocess. We never patch it, fork it, or depend on its internals.
2. **The PTY is the source of truth** — all interaction state is derived from the byte stream. If our detection fails, the user can toggle to passthrough and carry on.
3. **The enhanced UI is an upgrade, never a blocker** — degrading gracefully is a first-class requirement, not an afterthought.
4. **Platform portability is designed in from day one** — Mac is the first target, but the module boundaries make Linux and Windows straightforward additions.
5. **`MainWindow` never knows what is underneath it** — it talks only to `SessionProvider` and `OutputRenderer` interfaces. Swapping from JediTerm (PTY mode) to TamboUI (direct API mode) is an implementation swap behind those interfaces, not a window redesign.

---

## Technology Stack

| Concern | Technology | Rationale |
|---|---|---|
| UI framework | JavaFX | Native Mac `.app`, clean split pane, owns the TextArea |
| Terminal emulator (v1) | JediTerm (JetBrains) | Production-hardened, MIT license, same as IntelliJ |
| PTY management | pty4j (JetBrains) | Pairs with JediTerm, Mac/Linux/Windows support |
| Swing/FX bridge | SwingNode | Embeds JediTerm (Swing) into JavaFX hierarchy |
| Output renderer (future) | TamboUI | Structured widget renderer for direct API mode — replaces JediTerm behind the `OutputRenderer` interface |
| CLI argument parsing | Picocli | Lightweight, GraalVM-friendly |
| Native packaging | GraalVM + Gluon Client | Fast-startup `.app` bundle, no JVM required |

> **SwingNode note:** SwingNode works on Mac but has known quirks on Retina displays and with focus. This is the biggest v1 risk. If SwingNode proves problematic, the `OutputRenderer` interface means we can swap `JediTermRenderer` for a TerminalFX-based implementation without changing any other code.

---

## Module Structure

```
claude-desktop/
├── app-core/                   # Zero UI dependencies
│   ├── session/
│   │   ├── SessionProvider.java         # KEY INTERFACE — send(input), events(), close()
│   │   ├── SessionEvent.java            # Union type: BytesReceived | MessageReceived |
│   │   │                               #   ToolCall | ToolResult | ThinkingBlock | Done
│   │   ├── PtySessionProvider.java      # Implements SessionProvider via PTY subprocess.
│   │   │                               #   Internally tees PTY bytes → OutputRenderer +
│   │   │                               #   InteractionDetector. No buffering on the
│   │   │                               #   renderer path — display is never delayed.
│   │   └── ApiSessionProvider.java      # Implements SessionProvider via Anthropic API (future)
│   ├── pty/
│   │   ├── PtyProcess.java              # Interface: start, write, resize, stop
│   │   ├── MacPtyProcess.java           # pty4j implementation for macOS
│   │   ├── LinuxPtyProcess.java         # future
│   │   └── WinPtyProcess.java           # future
│   ├── stream/
│   │   └── InteractionDetector.java     # Pattern matching on PTY byte chunks → InteractionEvent
│   └── interaction/
│       ├── AppMode.java                 # ENHANCED | PASSTHROUGH
│       ├── InteractionMode.java         # FREE_TEXT | SLASH_COMMAND | LIST_SELECTION |
│       │                               #   FREE_TEXT_ANSWER | CONFIRMATION | PASSIVE
│       ├── InteractionEvent.java        # Events: ListDetected, ConfirmationDetected, etc.
│       ├── InteractionStateMachine.java # Owns transitions between modes
│       └── DetectedList.java            # Parsed list: items[], hasFreetextOption, prompt
│
├── app-ui/                     # JavaFX only, no PTY/session logic
│   ├── window/
│   │   └── MainWindow.java              # SplitPane — wires OutputRenderer + InputPane only
│   │                                   # Never references JediTerm, TamboUI, or PTY directly
│   ├── output/
│   │   ├── OutputRenderer.java          # KEY INTERFACE — accept(SessionEvent), clear(), focus()
│   │   ├── JediTermRenderer.java        # Pipes BytesReceived events to JediTerm (v1)
│   │   └── TamboUIRenderer.java         # Renders structured SessionEvents as widgets (future)
│   └── input/
│       ├── InputPane.java               # Mode-switching container, owns all input surfaces
│       ├── FreeTextInput.java           # Normal TextArea — default mode
│       ├── SlashCommandInput.java       # Slash mode: filters suggestions, sends to output
│       ├── ListSelectionInput.java      # Arrow-key list picker
│       ├── FreeTextAnswerInput.java     # "Something else..." inline text entry
│       └── ConfirmationInput.java       # Yes/No or Enter-to-confirm
│
├── app-mac/                    # macOS packaging only
│   ├── Info.plist
│   ├── entitlements.plist
│   └── icons/
│
├── app-linux/                  # future — packaging only
└── app-windows/                # future — packaging only
```

**Rule:** `app-core` has no dependency on `app-ui`. `app-ui` depends on `app-core` interfaces only, never on platform-specific implementations. `app-mac/linux/windows` wire everything together via dependency injection.

**The two key interfaces that make the swap possible:**

```java
// app-core — owns the session lifecycle and event stream
interface SessionProvider {
    void send(String input);           // user submits text
    Flux<SessionEvent> events();       // stream of events from claude
    void close();
}

// app-ui — owns how events are displayed
interface OutputRenderer {
    void accept(SessionEvent event);   // render one event
    void clear();                      // new session started
    Node getNode();                    // the JavaFX node to embed in MainWindow
}
```

`MainWindow` wires them together and nothing else. In v1, `SessionProvider` is `PtySessionProvider` and `OutputRenderer` is `JediTermRenderer`. In direct API mode, `SessionProvider` becomes `ApiSessionProvider` and `OutputRenderer` becomes `TamboUIRenderer`. The window, the input pane, the interaction state machine — none of it changes.

---

## The Two Top-Level Modes

```
AppMode.ENHANCED
  └── InteractionMode (active)
        ├── FREE_TEXT          default — user composing a message
        ├── HISTORY_BROWSING   ↑/↓ at edge of input is navigating history entries
        ├── SLASH_COMMAND      user typed '/' as first character
        ├── LIST_SELECTION     CLI rendered a choice list
        ├── FREE_TEXT_ANSWER   "Something else..." was selected from a list
        ├── CONFIRMATION       CLI is asking yes/no or enter-to-confirm
        └── PASSIVE            Claude is working — submission blocked, Stop button shown, composition allowed

AppMode.PASSTHROUGH
  └── InteractionMode (paused — no detection, no mode switching)
      JediTerm has full keyboard focus
      TextArea hidden/collapsed
```

**Toggle:** `Cmd+\` switches between `ENHANCED` and `PASSTHROUGH`. A subtle status badge on the terminal pane border shows the current mode ("ENHANCED" / "PASSTHROUGH").

**Toggle mid-interaction:** If the user toggles while in `LIST_SELECTION` or any other non-free-text mode, the `InteractionStateMachine` pauses cleanly. When toggling back, the detector resumes watching the PTY stream and picks up the next interaction naturally. The PTY subprocess is never interrupted.

---

## Data Flow

### v1 — PTY mode (JediTerm)

```
PTY subprocess (claude)
        │
        │  raw bytes (SessionEvent.BytesReceived)
        ▼
  PtySessionProvider
  (tees internally — no buffering on renderer path)
   ┌────┴────┐
   │         │
   ▼         ▼
JediTermRenderer   InteractionDetector
(renders bytes)    (pattern matches)
                         │
                         │  InteractionEvent
                         ▼
                 InteractionStateMachine
                         │
                         │  mode transition
                         ▼
                    InputPane
             (swaps active input surface)
```

### Future — Direct API mode (TamboUI)

```
Anthropic API (SSE stream)
        │
        │  structured events (MessageReceived | ToolCall | ThinkingBlock | ...)
        ▼
  ApiSessionProvider
        │
        ▼
TamboUIRenderer                InteractionStateMachine
(renders widgets)              (driven by structured events,
                                not ANSI patterns — no
                                screen-scraping required)
```

In direct API mode the `InteractionDetector` is retired entirely. The API stream tells you directly when Claude is presenting a list or asking for confirmation, making interaction detection reliable rather than best-effort.

---

## InteractionDetector — Approach C

The detector uses **best-effort pattern matching with graceful fallback**. It watches the PTY byte stream for known patterns. When confident, it emits an `InteractionEvent`. When uncertain, it stays silent — JediTerm has already rendered the output and the user can interact via the toggle if needed.

### Patterns to detect (v1)

```java
// List selection — Claude Code renders these with ANSI box-drawing + arrow indicators
// Example output stream contains sequences like:
//   ESC[?25l          (hide cursor — list rendering starting)
//   "❯ " or "> "      (selected item indicator)
//   ESC[?25h          (show cursor — list rendering done)

// Confirmation prompts
//   ends with " (y/n)" or " [Y/n]" or "Press Enter to continue"

// "Something else" option
//   list item text matches "something else", "other", "custom" (case-insensitive)
//   + free-text cursor appears on next line after selection
```

### Detection is best-effort

```java
public interface InteractionDetector {
    // Returns empty Optional if not confident — never blocks, never throws
    Optional<InteractionEvent> analyze(byte[] chunk);
}
```

If a pattern is not recognized, `Optional.empty()` is returned. The `InteractionStateMachine` stays in its current mode. The user sees the raw JediTerm output and can use `Cmd+\` to interact directly if needed.

---

## InputPane — Mode Switching Contract

`InputPane` listens to `InteractionStateMachine` events and swaps the active child component:

```
InteractionMode.FREE_TEXT       → show FreeTextInput
InteractionMode.HISTORY_BROWSING→ show FreeTextInput with history indicator, ↑/↓ navigates entries
InteractionMode.SLASH_COMMAND   → show SlashCommandInput (replaces FreeTextInput in-place)
InteractionMode.LIST_SELECTION  → show ListSelectionInput (replaces TextArea entirely)
InteractionMode.FREE_TEXT_ANSWER→ show FreeTextAnswerInput with context label
InteractionMode.CONFIRMATION    → show ConfirmationInput
InteractionMode.PASSIVE         → show FreeTextInput, submission disabled, Stop button visible (see Input Blocking section)
AppMode.PASSTHROUGH             → collapse InputPane entirely, JediTerm fills window
```

Each input surface has one responsibility: capture the user's intent and write the correct bytes to the session. All surfaces call `sessionProvider.send(input)` — the write contract is identical regardless of which surface is active.

---

## Input Blocking During Generation (PASSIVE Mode)

When Claude is working — processing a prompt, running tools, generating a response — the `InteractionMode` is `PASSIVE` and the input area is blocked. This mirrors claude.ai's behaviour and is a deliberate UX decision, not an implementation shortcut.

**Why block at all?**
Claude Code is a stateful subprocess. Sending input mid-generation would be received by the running process as unexpected stdin — likely corrupting the interaction. Unlike the Anthropic API (which has clear message boundaries), the PTY is a raw byte stream with no concept of "between turns". Blocking prevents the user from accidentally sending input at the wrong moment.

**What PASSIVE looks like**
The `FreeTextInput` remains visible and editable — the user can compose their next message while Claude is responding, exactly as claude.ai allows. What is disabled is *submission* — the Enter key does not send, and a visual indicator makes this clear:

```
┌─────────────────────────────────────────┐
│  Claude is working...          [Stop ■] │
│                                         │
│  > next message being composed here     │
└─────────────────────────────────────────┘
```

The input area is visually subdued (reduced opacity or a distinct border colour) but not greyed out or hidden. The user's composed text is preserved and submitted automatically once PASSIVE ends.

**The Stop button**
A "Stop" button is shown during PASSIVE mode, matching claude.ai's pattern. For PTY mode, stop sends `SIGINT` to the `claude` subprocess (equivalent to the user pressing `Ctrl+C` in a normal terminal). The subprocess handles this gracefully — Claude Code already supports interrupt. After stop, `InteractionMode` returns to `FREE_TEXT`.

**What triggers PASSIVE**
Immediately on the user submitting input (Enter pressed in any input surface). The transition is instant — there is no waiting for the first response byte, because the PTY subprocess has no acknowledgement signal.

**What ends PASSIVE**
This is the hardest part in PTY mode. The `InteractionDetector` watches for patterns that indicate Claude is ready for input again — typically the reappearance of a prompt indicator at the start of a new line. This is best-effort, consistent with Approach C. If detection fails, the user can use `Cmd+\` to toggle to PASSTHROUGH and assess the state directly in JediTerm.

In direct API mode this problem goes away entirely — the `Done` event in `SessionEvent` signals unambiguously that generation is complete.

---

## Input History

The `FreeTextInput` maintains a history of submitted prompts, accessible both via keyboard and a UI control. Selecting a history entry loads it into the input area for editing — it is never executed immediately.

### Keyboard navigation

Arrow keys have two meanings in a multi-line input field — cursor movement within the text, and history navigation — resolved by cursor position:

```
Cursor on first line + ↑     → load previous history entry
Cursor on last line  + ↓     → load next history entry (or clear to empty if at end)
Cursor anywhere else + ↑/↓  → normal cursor movement within the text
```

### History storage

History is persisted to disk (a simple JSON file in the app's data directory) so it survives across sessions. Maximum 1000 entries; oldest entries are dropped when the limit is reached.

History is **per-project** if Claude Code is launched in a specific directory, and **global** otherwise.

---

## v1 Build Sequence

**Step 1 — JavaFX shell**
Bare `Application` subclass. `SplitPane` (vertical). Confirm it launches as `.app` via GraalVM + Gluon.

**Step 2 — JediTerm integration**
Replace top placeholder with `JediTermPane`. Spawn `/bin/zsh` via pty4j. Embed in `SwingNode`. Confirm terminal works, resizes, renders correctly on Retina.

**Step 3 — Swap shell for Claude Code**
Change PTY command to `claude`. Confirm Claude Code renders correctly in JediTerm.

**Step 4 — TextArea input routing**
Replace bottom placeholder with `FreeTextInput`. On Enter: write content + newline to PTY stdin, clear TextArea.

**Step 5 — Focus choreography**
Focus in TextArea on start. Cmd+\ toggles PASSTHROUGH/ENHANCED.

**Step 6 — Polish**
Window sizing, font matching, color scheme, status badge, app icon.

---

## Deferred (Not v1)

- Slash command system
- `InteractionDetector` pattern matching
- Linux and Windows packaging
- Session management
- Settings UI
- Markdown rendering in input
- Voice input and TTS
- Direct Anthropic API calls

---

## Key Open Questions (resolve before v1 complete)

1. **SwingNode vs TerminalFX** — validate SwingNode + JediTerm on Retina Mac in Step 2.
2. **`claude` binary location** — v1 can hardcode PATH resolution; make configurable later.
3. **GraalVM + JavaFX build toolchain** — validate Gluon Client native image build at Step 1.
4. **PTY resize events** — wire SIGWINCH from JediTerm resize callback to pty4j.
5. **Overlay buffer for slash suggestions** — evaluate at slash command implementation time.

---

## Possible Roadmap

### Near-term
- Interaction detection improvements
- Settings UI
- Session management
- Linux and Windows support

### Medium-term
- Split view for file output
- Inline markdown rendering
- Direct API mode with TamboUI renderer

### Longer-term
- Voice input and TTS
- Multi-pane workspace

---

## What This Is Not

- Not a reimplementation of Claude Code's output rendering in v1
- Not a fork of Claude Code
- Not a terminal emulator (JediTerm is the terminal emulator)
- Not a TamboUI application in v1
