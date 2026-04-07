# Slash Command Passthrough — Design Spec

**Date:** 2026-04-07
**Status:** Approved

---

## Overview

When the user types `/` in the NSTextField input, the app enters **slash passthrough mode**: all subsequent keystrokes are forwarded directly to the PTY instead of buffering in the text field. Claude Code's own TUI renders the slash command suggestion list in the xterm.js terminal. No native overlay, no command list to maintain, no fuzzy matching code.

Exit conditions:
- **Enter** — command executed; InteractionDetector takes over
- **Escape** — cancelled; Escape sent to PTY to clean up Claude Code's TUI
- **Space** — user broke from available commands; Escape sent to PTY, NSTextField cleared
- **Backspace to empty** — user deleted back past `/`; Escape sent to PTY, NSTextField cleared

---

## State Machine — InputRouter

New class `InputRouter` in `app-core`. Pure Java, no Panama FFM or AppKit dependency.

**States:**

```
NORMAL          — keystrokes go to NSTextField / TextSubmittedCallback path
SLASH_PASSTHROUGH — keystrokes forwarded directly to PtyProcess.write()
```

**Transitions:**

| From | Event | Action | To |
|------|-------|--------|----|
| NORMAL | text changes to exactly `"/"` | write `"/"` to PTY; call `bridge.setSlashMode(true)`; call `bridge.setInputText("")` | SLASH_PASSTHROUGH |
| NORMAL | any other text change | — | NORMAL |
| SLASH_PASSTHROUGH | text change (any) | ignore — triggered by our own `setInputText` call | SLASH_PASSTHROUGH |
| SLASH_PASSTHROUGH | letter / digit / symbol key | write char to PTY | SLASH_PASSTHROUGH |
| SLASH_PASSTHROUGH | arrow key (↑↓) | write escape sequence to PTY | SLASH_PASSTHROUGH |
| SLASH_PASSTHROUGH | Backspace, buffer non-empty | write `\x7f` to PTY | SLASH_PASSTHROUGH |
| SLASH_PASSTHROUGH | Backspace, buffer empty (only `/` was sent) | write `\x1b` to PTY; `bridge.setSlashMode(false)`; `bridge.setInputText("")` | NORMAL |
| SLASH_PASSTHROUGH | Space | write `\x1b` to PTY; `bridge.setSlashMode(false)`; `bridge.setInputText("")` | NORMAL |
| SLASH_PASSTHROUGH | Escape | write `\x1b` to PTY; `bridge.setSlashMode(false)`; `bridge.setInputText("")` | NORMAL |
| SLASH_PASSTHROUGH | Enter | write `\r` to PTY; `bridge.setSlashMode(false)` | NORMAL |

`InputRouter` tracks a local character count of what has been forwarded after `/` (for Backspace-to-empty detection). It does not store the actual characters — it only needs the count.

**Constructor dependencies:**

```java
InputRouter(PtyProcess pty, MacUIBridge bridge)
```

Both are interfaces/classes already in `app-core` / `app-macos`. Both are mockable in tests.

---

## ObjC Bridge Additions

### New callbacks

| Callback | Signature | When fired |
|----------|-----------|-----------|
| `TextChangedCallback` | `void(*)(const char* text)` | Every time NSTextField content changes (via `controlTextDidChange:`) |
| `KeyPressedCallback` | `void(*)(const char* chars)` | Every key event intercepted in slash mode |

### New C functions

| Function | Signature | Purpose |
|----------|-----------|---------|
| `myui_set_slash_mode` | `void(int active)` | Enables (`1`) or disables (`0`) the NSEvent key monitor |
| `myui_set_input_text` | `void(const char* text)` | Sets NSTextField string value (used to clear field on exit) |

### myui_start signature change

Two new parameters appended:

```c
void myui_start(
    const char *title, int width, int height, const char *html,
    WindowClosedCallback   onClosed,
    TextSubmittedCallback  onTextSubmitted,
    StopClickedCallback    onStop,
    TextChangedCallback    onTextChanged,   // NEW
    KeyPressedCallback     onKeyPressed     // NEW
);
```

### NSEvent monitor

Installed once in `myui_start`, stored as an `id` property on the delegate. Enabled/disabled via `myui_set_slash_mode`:

```objc
- (void)setSlashMode:(BOOL)active {
    slashModeActive = active;
}

// Installed once at startup:
[NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskKeyDown
                                      handler:^NSEvent*(NSEvent *event) {
    if (slashModeActive) {
        NSString *chars = event.characters;
        if (chars.length > 0 && keyPressedCallback) {
            keyPressedCallback([chars UTF8String]);
        }
        return nil;  // consume — NSTextField never sees it
    }
    return event;
}];
```

Thread safety: `slashModeActive` is read/written on the main thread only (AppKit events + Java calls via `performSelectorOnMainThread:`). No lock needed.

---

## Wiring (Main.java / MacUIBridge)

`Main.java` constructs `InputRouter` and passes it as both callbacks:

```java
InputRouter router = new InputRouter(ptyProcess, bridge);
bridge.start(
    ...,
    router::onTextChanged,   // TextChangedCallback
    router::onKeyPressed     // KeyPressedCallback
);
```

`MacUIBridge` exposes:

```java
void setSlashMode(boolean active);  // → myui_set_slash_mode
void setInputText(String text);     // → myui_set_input_text
```

`InputRouter` is the only class that calls these. `Main.java` does not make routing decisions.

---

## Data Flow

```
NSTextField content changes to "/"
    │
    ▼
controlTextDidChange: → TextChangedCallback("/") → InputRouter.onTextChanged("/")
    │  detects isolated "/"
    ├──→ PtyProcess.write("/")
    ├──→ bridge.setSlashMode(true)
    └──→ bridge.setInputText("")        ← clears NSTextField

NSEvent monitor active (slashModeActive = YES)
User presses "c":
    │
    ▼
KeyPressedCallback("c") → InputRouter.onKeyPressed("c")
    └──→ PtyProcess.write("c")         ← Claude Code TUI filters list in terminal

User presses Space:
    │
    ▼
KeyPressedCallback(" ") → InputRouter.onKeyPressed(" ")
    ├──→ PtyProcess.write("\x1b")      ← cleans up Claude Code's TUI
    ├──→ bridge.setSlashMode(false)    ← NSEvent monitor deactivated
    └──→ bridge.setInputText("")       ← NSTextField cleared, user starts fresh

User presses Enter:
    │
    ▼
KeyPressedCallback("\r") → InputRouter.onKeyPressed("\r")   // Cocoa Return = \r (0x0D)
    ├──→ PtyProcess.write("\r")        ← executes command
    └──→ bridge.setSlashMode(false)
    (InteractionDetector sees PTY output → PASSIVE mode)
```

---

## Testing

Plain JUnit 5 throughout — no Quarkus, no native dependencies. `InputRouter` takes mock implementations of `PtyProcess` and `MacUIBridge`.

**Entry conditions:**

```java
// Enter slash mode on isolated "/"
onTextChanged("/") → verifySlashModeEnabled, verifyPtyWrote("/"), verifyInputCleared

// Do NOT enter slash mode for other text
onTextChanged("a")    → verifyStillNormal
onTextChanged("/abc") → verifyStillNormal   // pasted text with more chars
onTextChanged("")     → verifyStillNormal
```

**In SLASH_PASSTHROUGH — routing:**

```java
onKeyPressed("c")    → verifyPtyWrote("c"),    verifyStillSlashMode
onKeyPressed("l")    → verifyPtyWrote("l"),    verifyStillSlashMode
onKeyPressed("\u001B[A") // ↑ arrow → verifyPtyWrote(escSeq), verifyStillSlashMode
```

**Exit conditions:**

```java
onKeyPressed(" ")    → verifyPtyWrote("\x1b"), verifySlashModeDisabled, verifyInputCleared
onKeyPressed("\x1b") → verifyPtyWrote("\x1b"), verifySlashModeDisabled, verifyInputCleared
onKeyPressed("\r")   → verifyPtyWrote("\r"),   verifySlashModeDisabled  // Cocoa Return = \r
onKeyPressed("\x7f") // Backspace on empty buffer → verifyPtyWrote("\x1b"), verifySlashModeDisabled
onKeyPressed("\x7f") // Backspace on non-empty buffer → verifyPtyWrote("\x7f"), verifyStillSlashMode
```

**No cross-state leakage:**

```java
// Keys in NORMAL mode do not reach PTY via InputRouter
onTextChanged("hello") // stays NORMAL
onKeyPressed("x")      // verifyPtyNotWritten (normal input goes via TextSubmittedCallback)
```

---

## AppKit Pitfalls Applicable to This Feature

- **Pitfall 1 (GCD serialisation):** `myui_set_slash_mode` and `myui_set_input_text` are called from Java. Use `performSelectorOnMainThread:withObject:waitUntilDone:NO` — never `dispatch_async(main_queue, ...)`.
- **Pitfall 5 (upcall context):** `TextChangedCallback` and `KeyPressedCallback` fire from AppKit event handlers (main thread). Java callbacks must not dispatch back to main thread.
- **NSEvent monitor installed once:** install in `myui_start`, not on every slash mode entry — avoids accumulating multiple monitors.

---

## Out of Scope

- Command discovery / list maintenance — Claude Code's TUI handles this entirely
- Fuzzy matching — not needed
- Visual overlay — not needed
- Argument completion after command selection — future work
