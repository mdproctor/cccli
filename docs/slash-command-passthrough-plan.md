# Slash Command Passthrough Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the user types `/` in the NSTextField, route all keystrokes directly to the PTY so Claude Code's own TUI handles slash command display and selection.

**Architecture:** A new `InputRouter` pure-Java state machine (NORMAL → SLASH_PASSTHROUGH) lives in `app-core`. Two new ObjC callbacks (`TextChangedCallback`, `KeyPressedCallback`) and two new C functions (`myui_set_slash_mode`, `myui_set_input_text`) are added to the bridge. `myui_start` gains two new callback parameters. `Main.java` wires `InputRouter` into the bridge.

**Tech Stack:** Java 26 / Panama FFM, Objective-C / AppKit / NSEvent, JUnit 5 (plain — no Quarkus for `InputRouter` tests), GraalVM native image.

---

## File Map

| File | Action | What changes |
|------|--------|-------------|
| `app-core/src/main/java/dev/mproctor/cccli/InputRouter.java` | **Create** | State machine: NORMAL ↔ SLASH_PASSTHROUGH |
| `app-core/src/test/java/dev/mproctor/cccli/InputRouterTest.java` | **Create** | JUnit 5 tests for all state transitions |
| `mac-ui-bridge/include/MyMacUI.h` | **Modify** | Add 2 typedefs, 2 functions, update `myui_start` |
| `mac-ui-bridge/src/MyMacUI.m` | **Modify** | NSEvent monitor, `controlTextDidChange:`, `setSlashMode`, `setInputText` |
| `app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java` | **Modify** | Add `createTextChangedCallback`, `createKeyPressedCallback` |
| `app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java` | **Modify** | Add downcall handles for 2 new C functions; update `myui_start` handle |
| `app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java` | **Modify** | Add `setSlashMode`, `setInputText`; update `start()` signature |
| `app-macos/src/main/java/dev/mproctor/cccli/Main.java` | **Modify** | Construct `InputRouter`, pass two new callbacks to `bridge.start()` |
| `app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json` | **Modify** | Register 2 new upcall methods; add 2 new downcall signatures |

---

## Task 1: InputRouter — write failing tests

**Files:**
- Create: `app-core/src/test/java/dev/mproctor/cccli/InputRouterTest.java`

- [ ] **Step 1: Create the test file**

```java
package dev.mproctor.cccli;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InputRouterTest {

    private List<String> ptyWrites;
    private List<Boolean> slashModeChanges;
    private List<String> inputTextChanges;
    private InputRouter router;

    @BeforeEach
    void setUp() {
        ptyWrites        = new ArrayList<>();
        slashModeChanges = new ArrayList<>();
        inputTextChanges = new ArrayList<>();
        router = new InputRouter(
                ptyWrites::add,
                slashModeChanges::add,
                inputTextChanges::add);
    }

    // ── Entry conditions ──────────────────────────────────────────────────────

    @Test
    void slashAloneEntersPassthroughMode() {
        router.onTextChanged("/");
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
        assertEquals(List.of("/"),    ptyWrites);
        assertEquals(List.of(true),   slashModeChanges);
        assertEquals(List.of(""),     inputTextChanges);
    }

    @Test
    void otherTextStaysNormal() {
        router.onTextChanged("a");
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
        assertTrue(ptyWrites.isEmpty());
        assertTrue(slashModeChanges.isEmpty());
    }

    @Test
    void pastedTextWithSlashStaysNormal() {
        // pasted "/abc" arrives as a single text-changed event — not slash-only
        router.onTextChanged("/abc");
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    @Test
    void emptyTextStaysNormal() {
        router.onTextChanged("");
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Text changes ignored in SLASH_PASSTHROUGH ─────────────────────────────

    @Test
    void textChangesIgnoredInPassthroughMode() {
        // setInputText("") causes a second text-changed event — must be ignored
        router.onTextChanged("/");
        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();

        router.onTextChanged("");   // triggered by our own setInputText call
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
        assertTrue(ptyWrites.isEmpty());
        assertTrue(slashModeChanges.isEmpty());
        assertTrue(inputTextChanges.isEmpty());
    }

    // ── Passthrough routing ───────────────────────────────────────────────────

    @Test
    void letterKeyForwardedToPty() {
        router.onTextChanged("/");
        ptyWrites.clear();

        router.onKeyPressed("c");
        assertEquals(List.of("c"), ptyWrites);
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
    }

    @Test
    void arrowEscapeSequenceForwardedToPty() {
        router.onTextChanged("/");
        ptyWrites.clear();

        router.onKeyPressed("\u001B[A");  // up arrow (already converted by ObjC)
        assertEquals(List.of("\u001B[A"), ptyWrites);
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
    }

    @Test
    void keysInNormalModeNotForwardedToPty() {
        // In NORMAL mode, InputRouter does not forward key events to PTY
        router.onKeyPressed("x");
        assertTrue(ptyWrites.isEmpty());
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Exit via Space ────────────────────────────────────────────────────────

    @Test
    void spaceExitsPassthroughAndSendsEscape() {
        router.onTextChanged("/");
        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();

        router.onKeyPressed(" ");
        assertEquals(List.of("\u001B"),  ptyWrites);         // Escape to clean up Claude TUI
        assertEquals(List.of(false),      slashModeChanges); // slash mode off
        assertEquals(List.of(""),         inputTextChanges);  // NSTextField cleared
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Exit via Escape ───────────────────────────────────────────────────────

    @Test
    void escapeExitsPassthroughAndSendsEscape() {
        router.onTextChanged("/");
        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();

        router.onKeyPressed("\u001B");
        assertEquals(List.of("\u001B"),  ptyWrites);
        assertEquals(List.of(false),      slashModeChanges);
        assertEquals(List.of(""),         inputTextChanges);
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Exit via Enter ────────────────────────────────────────────────────────

    @Test
    void enterExitsPassthroughAndSendsCr() {
        router.onTextChanged("/");
        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();

        router.onKeyPressed("\r");  // Cocoa Return = \r (0x0D)
        assertEquals(List.of("\r"), ptyWrites);    // command executed
        assertEquals(List.of(false), slashModeChanges);
        assertTrue(inputTextChanges.isEmpty());     // no text-field clear needed on Enter
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Exit via Backspace-to-empty ───────────────────────────────────────────

    @Test
    void backspaceWithCharsBufferedStaysInPassthrough() {
        router.onTextChanged("/");
        router.onKeyPressed("c");      // buffer count = 1
        ptyWrites.clear();

        router.onKeyPressed("\u007F"); // Backspace
        assertEquals(List.of("\u007F"), ptyWrites);
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
    }

    @Test
    void backspaceOnEmptyBufferExitsPassthrough() {
        router.onTextChanged("/");     // buffer count = 0 (only "/" was sent)
        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();

        router.onKeyPressed("\u007F"); // Backspace — nothing after "/" to delete
        assertEquals(List.of("\u001B"), ptyWrites);  // Escape to clean up TUI
        assertEquals(List.of(false),     slashModeChanges);
        assertEquals(List.of(""),        inputTextChanges);
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    @Test
    void multipleCharsBackspacedToEmptyExitsPassthrough() {
        router.onTextChanged("/");
        router.onKeyPressed("c");  // count = 1
        router.onKeyPressed("l");  // count = 2
        router.onKeyPressed("\u007F"); // count = 1
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());

        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();
        router.onKeyPressed("\u007F"); // count = 0 → exit
        assertEquals(List.of("\u001B"), ptyWrites);
        assertEquals(List.of(false),     slashModeChanges);
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());
    }

    // ── Post-exit state is clean ──────────────────────────────────────────────

    @Test
    void afterExitNewSlashEntersPassthroughAgain() {
        router.onTextChanged("/");
        router.onKeyPressed(" ");  // exit
        assertEquals(InputRouter.Mode.NORMAL, router.getMode());

        ptyWrites.clear(); slashModeChanges.clear(); inputTextChanges.clear();
        router.onTextChanged("/");
        assertEquals(InputRouter.Mode.SLASH_PASSTHROUGH, router.getMode());
        assertEquals(List.of(true), slashModeChanges);
    }
}
```

- [ ] **Step 2: Run the tests to confirm they all fail**

```bash
cd /Users/mdproctor/claude/cccli
jenv shell 26
mvn test -pl app-core -Dtest=InputRouterTest -q 2>&1 | tail -20
```

Expected: compilation error — `InputRouter` does not exist yet.

---

## Task 2: InputRouter — implement

**Files:**
- Create: `app-core/src/main/java/dev/mproctor/cccli/InputRouter.java`

- [ ] **Step 1: Create InputRouter**

```java
package dev.mproctor.cccli;

import java.util.function.Consumer;

/**
 * State machine that routes NSTextField keystrokes to the PTY during slash command entry.
 *
 * States:
 *   NORMAL          — user types in NSTextField; text is submitted in full on Enter.
 *   SLASH_PASSTHROUGH — user typed "/"; every subsequent keystroke is forwarded
 *                      directly to the PTY so Claude Code's TUI handles display.
 *
 * Constructed with three side-effect functions so it is testable with plain JUnit.
 *
 * Thread-safety: all methods are called on the AppKit main thread (ObjC upcalls).
 * No synchronisation needed.
 */
public class InputRouter {

    public enum Mode { NORMAL, SLASH_PASSTHROUGH }

    private final Consumer<String>  writeToPty;
    private final Consumer<Boolean> setSlashMode;
    private final Consumer<String>  setInputText;

    private Mode mode         = Mode.NORMAL;
    private int  bufferCount  = 0;  // chars forwarded after the initial "/"

    public InputRouter(Consumer<String>  writeToPty,
                       Consumer<Boolean> setSlashMode,
                       Consumer<String>  setInputText) {
        this.writeToPty   = writeToPty;
        this.setSlashMode = setSlashMode;
        this.setInputText = setInputText;
    }

    /**
     * Called when NSTextField content changes (controlTextDidChange:).
     * Entry point: text exactly "/" triggers slash passthrough.
     * Ignored when already in SLASH_PASSTHROUGH (our own setInputText fires this).
     */
    public void onTextChanged(String text) {
        if (mode == Mode.SLASH_PASSTHROUGH) return;  // our own setInputText — ignore
        if ("/".equals(text)) {
            mode        = Mode.SLASH_PASSTHROUGH;
            bufferCount = 0;
            writeToPty.accept("/");
            setSlashMode.accept(true);
            setInputText.accept("");
        }
    }

    /**
     * Called for each key intercepted by the NSEvent monitor in slash mode.
     * In NORMAL mode this is a no-op (monitor is inactive but may still fire briefly).
     */
    public void onKeyPressed(String chars) {
        if (mode != Mode.SLASH_PASSTHROUGH) return;

        switch (chars) {
            case " ", "\u001B" -> exitSlash(true);   // Space or Escape — abort
            case "\r"          -> exitSlashEnter();   // Return — execute command
            case "\u007F"      -> handleBackspace();  // DEL (Backspace)
            default            -> {
                writeToPty.accept(chars);
                bufferCount++;
            }
        }
    }

    public Mode getMode() { return mode; }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void exitSlash(boolean sendEscape) {
        if (sendEscape) writeToPty.accept("\u001B");
        mode        = Mode.NORMAL;
        bufferCount = 0;
        setSlashMode.accept(false);
        setInputText.accept("");
    }

    private void exitSlashEnter() {
        writeToPty.accept("\r");
        mode        = Mode.NORMAL;
        bufferCount = 0;
        setSlashMode.accept(false);
        // No setInputText — field is already empty, no need to clear again
    }

    private void handleBackspace() {
        if (bufferCount > 0) {
            writeToPty.accept("\u007F");
            bufferCount--;
        } else {
            exitSlash(true);  // back past "/" — abort
        }
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
jenv shell 26
mvn test -pl app-core -Dtest=InputRouterTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, all 14 tests pass.

- [ ] **Step 3: Commit**

```bash
git add app-core/src/main/java/dev/mproctor/cccli/InputRouter.java \
        app-core/src/test/java/dev/mproctor/cccli/InputRouterTest.java
git commit -m "feat(InputRouter): slash command passthrough state machine

NORMAL → SLASH_PASSTHROUGH on isolated '/'; exits via Enter,
Escape, Space, or Backspace-to-empty. 14 JUnit 5 tests.

Refs #9"
```

---

## Task 3: ObjC bridge — MyMacUI.h

**Files:**
- Modify: `mac-ui-bridge/include/MyMacUI.h`

- [ ] **Step 1: Add two new callback typedefs and two new functions; extend `myui_start`**

Add after the existing `WindowResizedCallback` typedef:

```c
/** Fired when NSTextField content changes (each keystroke that alters the field).
 *  text is a null-terminated UTF-8 string of the full current field contents. */
typedef void (*TextChangedCallback)(const char* text);

/** Fired for each key event intercepted in slash passthrough mode.
 *  chars is a null-terminated UTF-8 string (may be multi-byte for escape sequences). */
typedef void (*KeyPressedCallback)(const char* chars);
```

Add two new function declarations before `myui_start`:

```c
/**
 * Enable (active=1) or disable (active=0) slash passthrough mode.
 * When active, an NSEvent local monitor intercepts all key events and
 * routes them via KeyPressedCallback instead of the NSTextField.
 * Thread-safe — dispatches to AppKit main thread internally.
 */
void myui_set_slash_mode(int active);

/**
 * Set the NSTextField string value. Pass "" to clear the field.
 * Thread-safe — dispatches to AppKit main thread internally.
 */
void myui_set_input_text(const char* text);
```

Update `myui_start` declaration to add two new callback parameters:

```c
intptr_t myui_start(const char* title,
                    int width,
                    int height,
                    const char* initialHtml,
                    WindowClosedCallback   onClosed,
                    TextSubmittedCallback  onTextSubmitted,
                    StopClickedCallback    onStop,
                    TextChangedCallback    onTextChanged,
                    KeyPressedCallback     onKeyPressed);
```

- [ ] **Step 2: Verify header compiles**

```bash
cd /Users/mdproctor/claude/cccli/mac-ui-bridge
clang -fsyntax-only -x objective-c src/MyMacUI.m \
  -I include -framework Cocoa -framework WebKit 2>&1
```

Expected: compilation errors referencing unimplemented functions — that's fine at this stage. The important thing is no parse errors in the header itself.

---

## Task 4: ObjC bridge — MyMacUI.m

**Files:**
- Modify: `mac-ui-bridge/src/MyMacUI.m`

- [ ] **Step 1: Add new module-level statics (after the existing statics block)**

After `static WindowResizedCallback resizedCallback = NULL;`, add:

```objc
static TextChangedCallback  textChangedCallback  = NULL;
static KeyPressedCallback   keyPressedCallback   = NULL;
static BOOL                 slashModeActive      = NO;
```

- [ ] **Step 2: Add `NSTextFieldDelegate` to CCCAppDelegate and new properties**

Update the `@interface CCCAppDelegate` declaration:

```objc
@interface CCCAppDelegate : NSObject
    <NSApplicationDelegate, NSWindowDelegate, WKNavigationDelegate,
     WKScriptMessageHandler, NSTextFieldDelegate>
@property (nonatomic, assign) WindowClosedCallback   onClosed;
@property (nonatomic, assign) TextSubmittedCallback  onTextSubmitted;
@property (nonatomic, assign) StopClickedCallback    onStop;
@property (nonatomic, weak)   NSTextField           *inputField;
- (void)appendToOutput:(NSString *)str;
- (void)applyPassiveMode:(NSNumber *)value;
- (void)evaluateJS:(NSString *)js;
- (void)applySlashMode:(NSNumber *)value;
- (void)applyInputText:(NSString *)text;
@end
```

- [ ] **Step 3: Implement `applySlashMode:` and `applyInputText:` in `@implementation CCCAppDelegate`**

Add after `applyPassiveMode:`:

```objc
- (void)applySlashMode:(NSNumber *)value {
    slashModeActive = value.boolValue;
}

- (void)applyInputText:(NSString *)text {
    if (theInputField) {
        [theInputField setStringValue:text];
    }
}
```

- [ ] **Step 4: Implement `controlTextDidChange:` in `@implementation CCCAppDelegate`**

Add after `applyInputText:`:

```objc
/* NSTextFieldDelegate — fires on every keystroke that changes the field content.
 * Forwards the current text to Java for slash-mode detection.
 * Already on the AppKit main thread — update synchronously. (APPKIT_PITFALLS.md §5) */
- (void)controlTextDidChange:(NSNotification *)notification {
    NSTextField *field = notification.object;
    if (field == theInputField && textChangedCallback) {
        textChangedCallback(field.stringValue.UTF8String);
    }
}
```

- [ ] **Step 5: Install the NSEvent monitor in `setupUI`, and set the delegate**

In `setupUI`, after `[root addSubview:inputField]` and before `theInputField = inputField`, add:

```objc
    inputField.delegate = appDelegate;
```

After the Stop button block (before the closing `}`), add the NSEvent monitor installation:

```objc
    /* Install NSEvent local monitor once at startup.
     * Activated/deactivated by slashModeActive flag — never removed.
     * Converts AppKit special key codes (arrows) to ANSI escape sequences
     * before forwarding to KeyPressedCallback. */
    [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskKeyDown
                                          handler:^NSEvent*(NSEvent *event) {
        if (!slashModeActive) return event;

        NSString *chars = event.characters;
        if (!chars || chars.length == 0) return event;

        /* Convert AppKit function key codes to ANSI escape sequences */
        NSString *toSend = chars;
        if (chars.length == 1) {
            unichar ch = [chars characterAtIndex:0];
            switch (ch) {
                case NSUpArrowFunctionKey:    toSend = @"\x1b[A"; break;
                case NSDownArrowFunctionKey:  toSend = @"\x1b[B"; break;
                case NSRightArrowFunctionKey: toSend = @"\x1b[C"; break;
                case NSLeftArrowFunctionKey:  toSend = @"\x1b[D"; break;
                default: break;
            }
        }

        if (keyPressedCallback) {
            keyPressedCallback(toSend.UTF8String);
        }
        return nil;  /* consume — NSTextField never sees this key */
    }];
```

- [ ] **Step 6: Implement `myui_set_slash_mode` and `myui_set_input_text` C functions**

Add after `myui_set_passive_mode`:

```objc
void myui_set_slash_mode(int active) {
    if ([NSThread isMainThread]) {
        slashModeActive = (BOOL)active;
    } else {
        [appDelegate performSelectorOnMainThread:@selector(applySlashMode:)
                                     withObject:@((BOOL)active)
                                  waitUntilDone:NO];
    }
}

void myui_set_input_text(const char *text) {
    NSString *str = text ? [NSString stringWithUTF8String:text] : @"";
    if ([NSThread isMainThread]) {
        if (theInputField) [theInputField setStringValue:str];
    } else {
        [appDelegate performSelectorOnMainThread:@selector(applyInputText:)
                                     withObject:str
                                  waitUntilDone:NO];
    }
}
```

- [ ] **Step 7: Update `myui_start` signature and store new callbacks**

Update the `myui_start` C function signature:

```objc
intptr_t myui_start(const char *title,
                    int width,
                    int height,
                    const char *initialHtml,
                    WindowClosedCallback  onClosed,
                    TextSubmittedCallback onTextSubmitted,
                    StopClickedCallback   onStop,
                    TextChangedCallback   onTextChanged,
                    KeyPressedCallback    onKeyPressed) {
```

Inside `myui_start`, before the `if ([NSThread isMainThread])` branch, store the new callbacks:

```objc
    textChangedCallback = onTextChanged;
    keyPressedCallback  = onKeyPressed;
```

- [ ] **Step 8: Rebuild the dylib**

```bash
cd /Users/mdproctor/claude/cccli/mac-ui-bridge
make
```

Expected: `libMyMacUI.dylib` rebuilds without errors.

---

## Task 5: Java bindings — Callbacks.java

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java`

- [ ] **Step 1: Add two new static handler fields, factory methods, and handler methods**

After the existing `windowResizedHandler` field:

```java
private static volatile Consumer<String> textChangedHandler;
private static volatile Consumer<String> keyPressedHandler;
```

Add new `FunctionDescriptor` constant at the top of the class (same style as `VOID_PTR`):

This is already covered by `VOID_PTR` — `TextChangedCallback` and `KeyPressedCallback` both have the same signature `void(*)(const char*)`.

Add factory methods after `createWindowResizedCallback`:

```java
/** Creates a void(*)(const char*) upcall stub that calls handler with the current field text. */
public static MemorySegment createTextChangedCallback(Arena arena, Consumer<String> handler) {
    textChangedHandler = handler;
    try {
        MethodHandle mh = MethodHandles.lookup()
                .findStatic(Callbacks.class, "onTextChanged",
                        MethodType.methodType(void.class, MemorySegment.class));
        return Linker.nativeLinker().upcallStub(mh, VOID_PTR, arena);
    } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException("Failed to create text-changed upcall stub", e);
    }
}

/** Creates a void(*)(const char*) upcall stub that calls handler with the pressed key chars. */
public static MemorySegment createKeyPressedCallback(Arena arena, Consumer<String> handler) {
    keyPressedHandler = handler;
    try {
        MethodHandle mh = MethodHandles.lookup()
                .findStatic(Callbacks.class, "onKeyPressed",
                        MethodType.methodType(void.class, MemorySegment.class));
        return Linker.nativeLinker().upcallStub(mh, VOID_PTR, arena);
    } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException("Failed to create key-pressed upcall stub", e);
    }
}
```

Add handler methods after `onTerminalResized`:

```java
/** Called from Objective-C when NSTextField content changes. Registered in reachability-metadata.json. */
public static void onTextChanged(MemorySegment textPtr) {
    Consumer<String> handler = textChangedHandler;
    if (handler != null && textPtr != null && !MemorySegment.NULL.equals(textPtr)) {
        String text = textPtr.reinterpret(Long.MAX_VALUE).getString(0);
        handler.accept(text);
    }
}

/** Called from Objective-C for each key intercepted in slash mode. Registered in reachability-metadata.json. */
public static void onKeyPressed(MemorySegment charsPtr) {
    Consumer<String> handler = keyPressedHandler;
    if (handler != null && charsPtr != null && !MemorySegment.NULL.equals(charsPtr)) {
        String chars = charsPtr.reinterpret(Long.MAX_VALUE).getString(0);
        handler.accept(chars);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
jenv shell 26
mvn compile -pl app-macos -q 2>&1 | tail -20
```

Expected: compilation error in `MacUIBridge.java` (myui_start call doesn't match yet) — that's fine.

---

## Task 6: Java bindings — MyMacUI_h.java

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java`

- [ ] **Step 1: Find the `myui_start` downcall handle and add two parameters**

Search for `myui_start` in `MyMacUI_h.java`. The handle currently has 7 `ADDRESS`/`JAVA_INT` parameters. Update its `FunctionDescriptor` to add two more `C_POINTER` (= `ADDRESS`) parameters for `onTextChanged` and `onKeyPressed`.

The existing FunctionDescriptor line looks like:
```java
FunctionDescriptor.of(C_LONG, C_POINTER, C_INT, C_INT, C_POINTER, C_POINTER, C_POINTER, C_POINTER)
```

Change it to:
```java
FunctionDescriptor.of(C_LONG, C_POINTER, C_INT, C_INT, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER)
```

Also update the corresponding `MethodHandle` invocation — find the `myui_start` method in `MyMacUI_h.java` and update its parameter list to include two additional `MemorySegment` parameters:

```java
// The existing method signature returns long and takes 7 params.
// Change to take 9 params (add onTextChanged and onKeyPressed as MemorySegment).
// Find the public static long myui_start(...) method and add:
//   MemorySegment onTextChanged, MemorySegment onKeyPressed
// at the end, passing them through to the invoker.
```

- [ ] **Step 2: Add downcall handles for `myui_set_slash_mode` and `myui_set_input_text`**

Following the same pattern as `myui_set_passive_mode` (a `void(int)` function), add:

```java
private static class myui_set_slash_mode {
    public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(C_INT);
    public static final MethodHandle HANDLE;
    static {
        HANDLE = Linker.nativeLinker().downcallHandle(
                findOrThrow("myui_set_slash_mode"), DESC);
    }
}

public static void myui_set_slash_mode(int active) {
    if (TRACE_DOWNCALLS) traceDowncall("myui_set_slash_mode", active);
    try {
        myui_set_slash_mode.HANDLE.invokeExact(active);
    } catch (Throwable ex) {
        throw new AssertionError("should not reach here", ex);
    }
}
```

Following the same pattern as `myui_evaluate_javascript` (a `void(const char*)` function), add:

```java
private static class myui_set_input_text {
    public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(C_POINTER);
    public static final MethodHandle HANDLE;
    static {
        HANDLE = Linker.nativeLinker().downcallHandle(
                findOrThrow("myui_set_input_text"), DESC);
    }
}

public static void myui_set_input_text(MemorySegment text) {
    if (TRACE_DOWNCALLS) traceDowncall("myui_set_input_text", text);
    try {
        myui_set_input_text.HANDLE.invokeExact(text);
    } catch (Throwable ex) {
        throw new AssertionError("should not reach here", ex);
    }
}
```

---

## Task 7: MacUIBridge.java

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java`

- [ ] **Step 1: Update `start()` to accept two new callbacks**

Change the `start()` signature from:

```java
public long start(String title, int width, int height,
                  String initialHtml,
                  Runnable onClosed,
                  Consumer<String> onTextSubmitted,
                  Runnable onStop) {
```

To:

```java
public long start(String title, int width, int height,
                  String initialHtml,
                  Runnable onClosed,
                  Consumer<String> onTextSubmitted,
                  Runnable onStop,
                  Consumer<String> onTextChanged,
                  Consumer<String> onKeyPressed) {
```

Inside `start()`, create stubs for the two new callbacks and pass them to `myui_start`:

```java
MemorySegment textChangedCb = Callbacks.createTextChangedCallback(arena, onTextChanged);
MemorySegment keyPressedCb  = Callbacks.createKeyPressedCallback(arena, onKeyPressed);
return MyMacUI_h.myui_start(titleSeg, width, height,
                             htmlSeg, closedCb, submittedCb, stopCb,
                             textChangedCb, keyPressedCb);
```

- [ ] **Step 2: Add `setSlashMode` and `setInputText` methods**

Add after `setPassiveMode`:

```java
/**
 * Enable or disable slash passthrough mode. Thread-safe.
 * When active, NSEvent monitor intercepts all key events and routes
 * them via KeyPressedCallback.
 */
public void setSlashMode(boolean active) {
    MyMacUI_h.myui_set_slash_mode(active ? 1 : 0);
}

/**
 * Set the NSTextField content. Pass "" to clear. Thread-safe.
 */
public void setInputText(String text) {
    try (Arena temp = Arena.ofConfined()) {
        MemorySegment seg = temp.allocateFrom(text != null ? text : "");
        MyMacUI_h.myui_set_input_text(seg);
    }
}
```

---

## Task 8: Reachability metadata

**Files:**
- Modify: `app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json`

- [ ] **Step 1: Add two new upcall entries and update the `myui_start` downcall**

Add to `"directUpcalls"` array:

```json
{
  "class": "dev.mproctor.cccli.bridge.Callbacks",
  "method": "onTextChanged",
  "returnType": "void",
  "parameterTypes": ["void*"]
},
{
  "class": "dev.mproctor.cccli.bridge.Callbacks",
  "method": "onKeyPressed",
  "returnType": "void",
  "parameterTypes": ["void*"]
}
```

Update the `myui_start` downcall entry. The existing entry is:

```json
{"returnType": "jlong", "parameterTypes": ["void*", "jint", "jint", "void*", "void*", "void*", "void*"]}
```

Change to (two extra `void*` for the new callbacks):

```json
{"returnType": "jlong", "parameterTypes": ["void*", "jint", "jint", "void*", "void*", "void*", "void*", "void*", "void*"]}
```

Add two new downcall entries for the new C functions:

```json
{"returnType": "void", "parameterTypes": ["jint"]},
{"returnType": "void", "parameterTypes": ["void*"]}
```

Note: The `void(jint)` pattern already exists in the downcalls list (used by `myui_set_passive_mode`), so GraalVM's AOT compiler already knows about that shape. The `void(void*)` pattern also exists. These additions make the specific functions findable by symbol name — add them to be safe.

---

## Task 9: Main.java — wire InputRouter

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/Main.java`

- [ ] **Step 1: Construct InputRouter and pass to bridge.start()**

After the `pty.startReader(...)` block, add:

```java
InputRouter inputRouter = new InputRouter(
        pty::write,
        bridge::setSlashMode,
        bridge::setInputText);
```

Update the `bridge.start(...)` call to pass two new callbacks:

```java
bridge.start("Claude Desktop CLI", 900, 600,
        "Connecting to Claude...\n",
        () -> {
            Log.info("Window closed — terminating");
            detector.forceIdle();
            detector.close();
            pty.close();
            bridge.terminate();
        },
        text -> {
            if (detector.getState() == ClaudeState.FREE_TEXT) {
                Log.infof("Sending to claude: %s", text);
                detector.onSubmit();
                pty.write(text + "\n");
            }
        },
        () -> {
            Log.info("Stop clicked — sending SIGINT");
            pty.sendSigInt();
            detector.forceIdle();
        },
        inputRouter::onTextChanged,   // NEW — fires on each NSTextField change
        inputRouter::onKeyPressed);   // NEW — fires on each key in slash mode
```

- [ ] **Step 2: Add import for InputRouter**

Add to imports at top of `Main.java`:

```java
import dev.mproctor.cccli.InputRouter;
```

---

## Task 10: Build, test, and smoke test

- [ ] **Step 1: Run all unit tests**

```bash
jenv shell 26
mvn test 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`, 58+ tests pass (all existing + 14 new InputRouter tests).

- [ ] **Step 2: Build the native .app bundle**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn install -Pnative -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, `.app` bundle rebuilt in `app-macos/target/`.

- [ ] **Step 3: Smoke test**

```bash
open "app-macos/target/Claude Desktop CLI.app"
```

Manual test sequence:
1. App launches, terminal connects — type a normal message and press Enter. Confirm it works as before.
2. Type `/` — the NSTextField clears immediately, Claude Code's TUI shows slash commands in the terminal.
3. Type `c`, `l` — Claude Code filters to commands starting with `/cl`.
4. Press arrow keys — Claude Code navigates the list.
5. Press Enter — command executes, InteractionDetector transitions to PASSIVE, then FREE_TEXT.
6. Type `/` then press Space — Claude Code's TUI clears (Escape sent), NSTextField is empty, normal input resumes.
7. Type `/` then press Backspace — count reaches 0, Escape sent, returns to normal mode.

- [ ] **Step 4: Commit**

```bash
git add mac-ui-bridge/include/MyMacUI.h \
        mac-ui-bridge/src/MyMacUI.m \
        mac-ui-bridge/build/libMyMacUI.dylib \
        app-macos/src/main/java/dev/mproctor/cccli/bridge/Callbacks.java \
        app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java \
        app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java \
        app-macos/src/main/java/dev/mproctor/cccli/Main.java \
        app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json
git commit -m "feat: slash command passthrough mode

Type '/' to route keystrokes directly to PTY. Claude Code's TUI
handles slash command display and selection. Exit via Enter,
Space, Escape, or Backspace-to-empty.

Closes #9"
```
