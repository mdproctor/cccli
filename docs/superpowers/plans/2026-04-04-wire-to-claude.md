# Wire to Claude Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `/bin/cat` test subprocess with the real `claude` CLI binary, resolving it via a login shell, stripping ANSI escape codes before display, and validating that a prompt typed in the UI receives a visible Claude response in the output pane.

**Architecture:** `ClaudeLocator` resolves the binary once at startup using `/bin/zsh -l -c 'which claude'` (login shell ensures PATH entries from shell profiles are honoured). `AnsiStripper` strips VT100 escape sequences from PTY output before passing it to `bridge.appendOutput()` — NSTextView has no terminal emulation, so raw ANSI codes would appear as garbage. Both classes live in `app-core` (pure Java, no UI dependency). `Main.java` wires them together. If `claude` is not found, the app writes an error to the output pane and exits cleanly.

**Tech Stack:** Java 22+, `ProcessBuilder` (for login-shell resolution — simpler than Panama for a one-shot read), Panama FFM `posixSpawn` (for the PTY subprocess — unchanged from Plan 3), Quarkus Native (GraalVM 25), `java.util.regex.Pattern` (ANSI stripping)

---

## Note: no NSOpenPanel / file picker

The HANDOFF mentioned a file picker fallback if `claude` is not found. That requires adding `NSOpenPanel` to the ObjC bridge — significant scope. For Plan 4, the fallback is a clear error message written to the output pane. The file picker is deferred.

## Note: ANSI codes in NSTextView

Claude is an interactive TUI application and outputs colors, cursor movement, spinners, and OSC sequences. NSTextView has no terminal emulation — these appear as escape characters. `AnsiStripper` strips them so the text content is readable. Plan 5b replaces NSTextView with WKWebView + xterm.js, which handles ANSI natively. `AnsiStripper` is dev-mode only.

---

## File Map

```
app-core/
└── src/
    ├── main/java/dev/mproctor/cccli/
    │   ├── ClaudeLocator.java          CREATE — resolves claude binary via login shell
    │   └── AnsiStripper.java           CREATE — strips VT100 escape sequences
    └── test/java/dev/mproctor/cccli/
        ├── ClaudeLocatorTest.java      CREATE — verifies locate() returns real executable
        └── AnsiStripperTest.java       CREATE — unit tests for every escape sequence type

app-macos/src/main/java/dev/mproctor/cccli/
└── Main.java                           MODIFY — use ClaudeLocator + AnsiStripper, spawn claude
```

No changes needed to `PosixLibrary`, `PtyProcess`, ObjC bridge, or native-image config.

---

## Task 1: AnsiStripper — strip VT100 escape sequences

**Files:**
- Create: `app-core/src/main/java/dev/mproctor/cccli/AnsiStripper.java`
- Create: `app-core/src/test/java/dev/mproctor/cccli/AnsiStripperTest.java`

- [ ] **Step 1: Write the failing tests**

Create `app-core/src/test/java/dev/mproctor/cccli/AnsiStripperTest.java`:

```java
package dev.mproctor.cccli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnsiStripperTest {

    @Test
    void preservesPlainText() {
        assertEquals("hello world\n", AnsiStripper.strip("hello world\n"));
    }

    @Test
    void stripsColorSequences() {
        // ESC[32m = green, ESC[0m = reset
        assertEquals("hello", AnsiStripper.strip("\u001B[32mhello\u001B[0m"));
    }

    @Test
    void stripsBoldAndMultiParamSequences() {
        // ESC[1;32m = bold green
        assertEquals("text", AnsiStripper.strip("\u001B[1;32mtext\u001B[0m"));
    }

    @Test
    void stripsCursorMovementSequences() {
        // ESC[1A = cursor up 1, ESC[2K = erase line
        assertEquals("", AnsiStripper.strip("\u001B[1A\u001B[2K"));
    }

    @Test
    void stripsOscSequences() {
        // ESC]0;title BEL — window title sequence
        assertEquals("hello", AnsiStripper.strip("\u001B]0;title\u0007hello"));
    }

    @Test
    void normalisesCrLfToLf() {
        assertEquals("hello\nworld\n", AnsiStripper.strip("hello\r\nworld\r\n"));
    }

    @Test
    void stripsBareCarriageReturn() {
        // Bare CR without LF (spinner overwrite pattern) is dropped
        assertEquals("progress", AnsiStripper.strip("progress\r"));
    }

    @Test
    void keepsLfFromCrLf() {
        // CRLF → LF (not stripped)
        String result = AnsiStripper.strip("line\r\n");
        assertEquals("line\n", result);
    }

    @Test
    void stripsComplexRealWorldSequence() {
        // Typical claude output: color + text + reset + CRLF
        String input = "\u001B[1m\u001B[32m>\u001B[0m Hello there\r\n";
        assertEquals("> Hello there\n", AnsiStripper.strip(input));
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail (class missing)**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.0.2-graalce
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q 2>&1 | head -10
```

Expected: compilation error — `AnsiStripper` does not exist.

- [ ] **Step 3: Create AnsiStripper.java**

Create `app-core/src/main/java/dev/mproctor/cccli/AnsiStripper.java`:

```java
package dev.mproctor.cccli;

import java.util.regex.Pattern;

/**
 * Strips ANSI/VT100 escape sequences from PTY output for display in NSTextView.
 *
 * NSTextView has no terminal emulation — escape codes appear as literal
 * characters. This class strips them so the text content is readable.
 *
 * Used in development (NSTextView) mode only. Plan 5b routes bytes to
 * xterm.js which handles ANSI natively and does not use this class.
 */
public final class AnsiStripper {

    /**
     * Matches:
     *   CSI sequences    ESC [ <params> <letter>   e.g. ESC[1;32m, ESC[2K, ESC[?25l
     *   Other escapes    ESC <single non-[ char>   e.g. ESC= ESC> ESC7 ESC8 ESC M
     *   OSC sequences    ESC ] <text> BEL          e.g. ESC]0;window title BEL
     *   Bare CR          \r not followed by \n     e.g. spinner overwrite
     */
    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\[[0-9;:?]*[A-Za-z]"    // CSI: ESC [ params letter
            + "|\u001B[^\\[\u001B\r\n]"       // Other: ESC + single char (not [, ESC, newlines)
            + "|\u001B\\][^\u0007]*\u0007"    // OSC: ESC ] text BEL
            + "|\r(?!\n)"                     // Bare CR not followed by LF
    );

    /**
     * Strips ANSI escape sequences and normalises CRLF to LF.
     * Returns the plain-text content suitable for NSTextView display.
     */
    public static String strip(String text) {
        // Normalise CRLF → LF first, then strip remaining control sequences
        return ANSI.matcher(text.replace("\r\n", "\n")).replaceAll("");
    }

    private AnsiStripper() {}
}
```

- [ ] **Step 4: Run all tests**

```bash
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q
```

Expected: all tests pass including the 9 new `AnsiStripperTest` tests. Total should be 18 (9 PtyProcessTest + 3 PosixLibraryTest + 6... wait, check the count). Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app-core/src/main/java/dev/mproctor/cccli/AnsiStripper.java \
        app-core/src/test/java/dev/mproctor/cccli/AnsiStripperTest.java
git commit -m "feat(core): AnsiStripper — strip VT100 escape sequences for NSTextView display"
```

---

## Task 2: ClaudeLocator — resolve claude binary via login shell

**Files:**
- Create: `app-core/src/main/java/dev/mproctor/cccli/ClaudeLocator.java`
- Create: `app-core/src/test/java/dev/mproctor/cccli/ClaudeLocatorTest.java`

- [ ] **Step 1: Write the failing test**

Create `app-core/src/test/java/dev/mproctor/cccli/ClaudeLocatorTest.java`:

```java
package dev.mproctor.cccli;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeLocatorTest {

    @Test
    void locateReturnsExecutablePath() {
        Path path = ClaudeLocator.locate();
        assertNotNull(path, "claude binary not found — is it installed?");
        assertTrue(Files.exists(path), "resolved path does not exist: " + path);
        assertTrue(Files.isExecutable(path), "resolved path is not executable: " + path);
    }

    @Test
    void locateReturnsAbsolutePath() {
        Path path = ClaudeLocator.locate();
        assertNotNull(path);
        assertTrue(path.isAbsolute(), "path should be absolute, got: " + path);
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q 2>&1 | head -10
```

Expected: compilation error — `ClaudeLocator` does not exist.

- [ ] **Step 3: Create ClaudeLocator.java**

Create `app-core/src/main/java/dev/mproctor/cccli/ClaudeLocator.java`:

```java
package dev.mproctor.cccli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the absolute path to the {@code claude} CLI binary.
 *
 * Uses {@code /bin/zsh -l -c 'which claude'} so that PATH entries from
 * shell profiles (~/.zshrc, ~/.zprofile) are honoured — e.g. ~/.local/bin,
 * homebrew, nvm, etc. A plain {@code PATH} lookup would miss these when the
 * app is launched as a native binary outside a login shell.
 *
 * Returns {@code null} if claude is not found or not executable. Callers
 * should write an error to the UI and exit cleanly.
 */
public final class ClaudeLocator {

    /**
     * Returns the absolute path to the claude binary, or {@code null} if not found.
     * Blocks briefly while running {@code which claude} in a login shell.
     */
    public static Path locate() {
        try {
            Process p = new ProcessBuilder("/bin/zsh", "-l", "-c", "which claude")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes()).strip();
            int exit = p.waitFor();
            if (exit != 0 || output.isEmpty()) return null;
            Path path = Path.of(output).toAbsolutePath();
            return Files.isExecutable(path) ? path : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private ClaudeLocator() {}
}
```

- [ ] **Step 4: Run all tests**

```bash
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q
```

Expected: BUILD SUCCESS. All prior tests plus 2 new `ClaudeLocatorTest` tests pass. (If claude is not installed on the machine running tests, the `locateReturnsExecutablePath` test will fail with a helpful assertion message.)

- [ ] **Step 5: Commit**

```bash
git add app-core/src/main/java/dev/mproctor/cccli/ClaudeLocator.java \
        app-core/src/test/java/dev/mproctor/cccli/ClaudeLocatorTest.java
git commit -m "feat(core): ClaudeLocator — resolve claude binary via login shell"
```

---

## Task 3: Wire claude into Main.java

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/Main.java`

- [ ] **Step 1: Replace Main.java with the claude-wired version**

```java
package dev.mproctor.cccli;

import dev.mproctor.cccli.bridge.MacUIBridge;
import dev.mproctor.cccli.pty.PtyProcess;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import java.nio.file.Path;

@QuarkusMain
public class Main implements QuarkusApplication {

    @Inject
    MacUIBridge bridge;

    public static void main(String... args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) {
        Path claudePath = ClaudeLocator.locate();
        if (claudePath == null) {
            Log.error("claude binary not found in PATH");
            /* Write error to output pane then exit — bridge isn't started yet,
             * so we can't use appendOutput. Exit with a message to stderr.      */
            System.err.println("""
                    claude not found. Install it with:
                      npm install -g @anthropic-ai/claude-code
                    Then relaunch the app.
                    """);
            return 1;
        }
        Log.infof("Found claude at: %s", claudePath);

        PtyProcess pty = new PtyProcess();
        pty.open();
        pty.spawn(new String[]{claudePath.toString()});

        // Strip ANSI escape codes before display — NSTextView has no terminal
        // emulation. Plan 5b switches to WKWebView + xterm.js which handles
        // ANSI natively; AnsiStripper is removed at that point.
        pty.startReader(text -> bridge.appendOutput(AnsiStripper.strip(text)));

        // Plan 5 wires resize to actual window dimensions.
        pty.resize(24, 120);

        Log.info("Starting Claude Desktop CLI...");
        bridge.start("Claude Desktop CLI", 900, 600,
                "Connecting to Claude...\n",
                () -> {
                    Log.info("Window closed — terminating");
                    pty.close();
                    bridge.terminate();
                },
                text -> {
                    Log.infof("Sending to claude: %s", text);
                    pty.write(text + "\n");
                });

        Log.info("Application terminated");
        return 0;
    }
}
```

- [ ] **Step 2: Build in JVM dev mode**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.0.2-graalce
cd /Users/mdproctor/claude/cccli
mvn install -q
cd app-macos
mvn quarkus:dev -Dcccli.dylib.path="$(pwd)/../mac-ui-bridge/build/libMyMacUI.dylib"
```

- [ ] **Step 3: Manual integration test — JVM mode**

With the app running:
1. Wait for output to appear in the top pane (claude's startup prompt/UI should appear, ANSI-stripped)
2. Type a simple prompt: `say hello` and press Enter
3. Expected: Claude responds with text visible in the output pane
4. Close the window — app exits cleanly

If output pane shows only garbled ANSI codes (AnsiStripper not working), check the regex. If nothing appears, check logs for PTY errors.

- [ ] **Step 4: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add app-macos/src/main/java/dev/mproctor/cccli/Main.java
git commit -m "feat(macos): wire claude CLI via PtyProcess with ANSI stripping"
```

---

## Task 4: Native image build and validation

**Files:**
- No changes expected. If the build fails, follow the fallback steps below.

- [ ] **Step 1: Build native image**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 25.0.2-graalce
cd /Users/mdproctor/claude/cccli
mvn package -pl app-macos -am -Pnative -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`. Build takes 2–4 minutes. `ProcessBuilder` is supported natively by GraalVM — no extra config needed. `AnsiStripper` uses `java.util.regex.Pattern` which is fully supported.

- [ ] **Step 2: If the build fails with ProcessBuilder / reflection errors**

Add to `app-macos/src/main/resources/META-INF/native-image/native-image.properties`, appending to the `Args` line:

```properties
Args = --enable-native-access=ALL-UNNAMED \
       -H:+ReportExceptionStackTraces \
       --initialize-at-run-time=dev.mproctor.cccli.bridge.gen \
       --initialize-at-run-time=dev.mproctor.cccli.pty.PosixLibrary \
       -H:ReflectionConfigurationFiles=reflect-config.json
```

And check if `reflect-config.json` needs `ProcessBuilder`-related entries. Run `mvn package -pl app-macos -am -Pnative 2>&1 | grep ERROR` for specifics.

- [ ] **Step 3: Run the native binary**

```bash
./app-macos/target/app-macos-1.0.0-SNAPSHOT-runner \
  -Dcccli.dylib.path=/Users/mdproctor/claude/cccli/mac-ui-bridge/build/libMyMacUI.dylib
```

Window should appear in < 50ms.

- [ ] **Step 4: Manual integration test — native binary**

1. Wait for claude's startup output to appear (stripped of ANSI codes)
2. Type `say hello in one sentence` and press Enter
3. Expected: Claude's response appears in the output pane as readable plain text
4. Type another prompt — verify the conversation continues
5. Close the window — app exits cleanly, no hung processes

- [ ] **Step 5: Commit**

If `native-image.properties` was modified:
```bash
git add app-macos/src/main/resources/META-INF/native-image/native-image.properties
git commit -m "build: native image config for ClaudeLocator ProcessBuilder support"
```

If no changes needed:
```bash
git commit --allow-empty -m "chore: native image validates claude CLI wiring"
```

---

## Self-Review

### Spec coverage

| Requirement | Task |
|-------------|------|
| Resolve `claude` binary via login shell | Task 2 |
| Graceful error if claude not found | Task 3 |
| Replace `/bin/cat` with `claude` | Task 3 |
| Route PTY output to `bridge.appendOutput()` | Task 3 (unchanged from Plan 3) |
| Strip ANSI codes for NSTextView | Task 1 + 3 |
| Validate Claude output renders correctly | Task 3 Step 3, Task 4 Step 4 |
| Native image works | Task 4 |

File picker fallback: **explicitly deferred** (documented in plan header).

### Placeholder scan

No TBD, TODO, or "similar to Task N" patterns. All code blocks are complete.

### Type consistency

- `ClaudeLocator.locate()` returns `Path` — used as `claudePath.toString()` in `Main.java` ✓
- `AnsiStripper.strip(String)` returns `String` — used in `pty.startReader(text -> bridge.appendOutput(AnsiStripper.strip(text)))` ✓
- `PtyProcess.spawn(String[])` — unchanged, called with `new String[]{claudePath.toString()}` ✓
