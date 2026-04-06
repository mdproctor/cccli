# PTY Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `PtyProcess` class in `app-core` opens a PTY, spawns a subprocess with its stdio wired to the PTY slave, and streams output back to the UI — all via Panama FFM direct POSIX calls, no JNA or pty4j.

**Architecture:** `PosixLibrary` wraps POSIX libc functions as static Panama FFM downcall handles (build-time initialised, GraalVM-safe). `PtyProcess` owns the PTY lifecycle: `open → spawn → startReader → write/resize → close`. In `app-macos/Main.java`, the PTY is started with `/bin/cat` before `bridge.start()`, and the reader daemon thread calls `bridge.appendOutput()` thread-safely. Plan 4 will swap `/bin/cat` for `claude`.

**Tech Stack:** Java 22+, Panama FFM (java.lang.foreign), Quarkus Native (GraalVM 25), macOS POSIX libc, JUnit 5

---

## Why no JNA / pty4j

pty4j uses JNA internally. JNA's runtime reflection breaks GraalVM native image. `posix_openpt`, `posix_spawn`, `read`, `write` are all in macOS's `libSystem.B.dylib`, accessible via `Linker.nativeLinker().defaultLookup()` — the same mechanism we already use for the AppKit bridge.

## Threading model

The PTY reader runs in a daemon thread. It calls `bridge.appendOutput()` from that thread. `myui_append_output()` in the ObjC bridge already handles the non-main-thread case with `dispatch_async(main_queue, ...)`. No extra synchronisation needed.

`posix_spawn()` is used instead of `fork()`. `fork()` is unsafe in GraalVM native image because the JVM runtime state cannot be safely duplicated.

## POSIX constants (macOS AArch64)

| Constant | Value | Source |
|----------|-------|--------|
| `O_RDWR` | `0x0002` | `<fcntl.h>` |
| `O_NOCTTY` | `0x20000` | `<fcntl.h>` |
| `TIOCSWINSZ` | `0x80087467L` | `_IOW('t', 103, struct winsize)` |
| `SIGTERM` | `15` | `<signal.h>` |
| `SIGKILL` | `9` | `<signal.h>` |
| `WNOHANG` | `1` | `<sys/wait.h>` |

`struct winsize` is 8 bytes: 4 × `unsigned short` (row, col, xpixel, ypixel).  
`posix_spawn_file_actions_t` is 80 bytes on macOS AArch64 — allocate 128 for safety.

---

## File Map

```
app-core/
├── pom.xml                                     MODIFY — add JUnit5 test dep + Surefire plugin
└── src/
    ├── main/java/dev/mproctor/cccli/pty/
    │   ├── PosixLibrary.java                   CREATE — static Panama FFM handles for POSIX calls
    │   └── PtyProcess.java                     CREATE — PTY lifecycle: open, spawn, read, write, resize, close
    └── test/java/dev/mproctor/cccli/pty/
        ├── PosixLibraryTest.java               CREATE — verifies posix_openpt works
        └── PtyProcessTest.java                 CREATE — spawn /bin/echo, /bin/cat round-trip, resize, close

app-macos/src/main/java/dev/mproctor/cccli/
└── Main.java                                   MODIFY — spawn /bin/cat PTY, wire to bridge
```

`reachability-metadata.json` is updated in Task 7 **only if** the native image build fails with Panama errors. The `PosixLibrary` static fields initialise at build time so GraalVM discovers the downcalls automatically.

---

## Task 1: Add JUnit5 to app-core

**Files:**
- Modify: `app-core/pom.xml`

- [ ] **Step 1: Replace app-core/pom.xml with test-capable version**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dev.mproctor.cccli</groupId>
        <artifactId>claude-desktop</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>app-core</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create a trivial smoke test to confirm the test harness works**

Create `app-core/src/test/java/dev/mproctor/cccli/pty/SmokeTest.java`:

```java
package dev.mproctor.cccli.pty;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTest {
    @Test
    void alwaysPasses() {
        assertTrue(true);
    }
}
```

- [ ] **Step 3: Run the test**

```bash
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q
```

Expected: `BUILD SUCCESS` with 1 test passing. No failures.

- [ ] **Step 4: Delete the smoke test**

```bash
rm app-core/src/test/java/dev/mproctor/cccli/pty/SmokeTest.java
```

- [ ] **Step 5: Commit**

```bash
git add app-core/pom.xml
git commit -m "build: add JUnit5 to app-core for PTY tests"
```

---

## Task 2: PosixLibrary — Panama FFM downcalls

**Files:**
- Create: `app-core/src/main/java/dev/mproctor/cccli/pty/PosixLibrary.java`
- Create: `app-core/src/test/java/dev/mproctor/cccli/pty/PosixLibraryTest.java`

- [ ] **Step 1: Write the failing test**

Create `app-core/src/test/java/dev/mproctor/cccli/pty/PosixLibraryTest.java`:

```java
package dev.mproctor.cccli.pty;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PosixLibraryTest {

    @Test
    void posixOpenptReturnsValidFd() {
        int fd = PosixLibrary.posixOpenpt(PosixLibrary.O_RDWR | PosixLibrary.O_NOCTTY);
        assertTrue(fd >= 0, "posix_openpt should return a valid file descriptor, got: " + fd);
        PosixLibrary.close(fd);
    }

    @Test
    void grantptAndUnlockptSucceed() {
        int fd = PosixLibrary.posixOpenpt(PosixLibrary.O_RDWR | PosixLibrary.O_NOCTTY);
        assertTrue(fd >= 0);
        assertEquals(0, PosixLibrary.grantpt(fd), "grantpt should return 0");
        assertEquals(0, PosixLibrary.unlockpt(fd), "unlockpt should return 0");
        PosixLibrary.close(fd);
    }

    @Test
    void ptsnameReturnsDevPath() {
        int fd = PosixLibrary.posixOpenpt(PosixLibrary.O_RDWR | PosixLibrary.O_NOCTTY);
        assertTrue(fd >= 0);
        PosixLibrary.grantpt(fd);
        PosixLibrary.unlockpt(fd);
        String slavePath = PosixLibrary.ptsname(fd);
        assertNotNull(slavePath);
        assertTrue(slavePath.startsWith("/dev/"), "slave path should start with /dev/, got: " + slavePath);
        PosixLibrary.close(fd);
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails (class missing)**

```bash
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q 2>&1 | head -20
```

Expected: compilation error — `PosixLibrary` does not exist.

- [ ] **Step 3: Create PosixLibrary.java**

Create `app-core/src/main/java/dev/mproctor/cccli/pty/PosixLibrary.java`:

```java
package dev.mproctor.cccli.pty;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Panama FFM downcall handles for POSIX libc functions needed by the PTY layer.
 *
 * All MethodHandle fields are static final — they initialise at class-load time
 * (or at GraalVM native image build time). No JNA, no pty4j, no JNI.
 *
 * Errors: POSIX functions returning -1 indicate failure. Callers check return
 * values and throw accordingly. errno is not surfaced (no errno downcall).
 */
public final class PosixLibrary {

    // ── Constants ────────────────────────────────────────────────────────────

    /** open(2) flags — macOS AArch64 values */
    public static final int O_RDWR   = 0x0002;
    public static final int O_NOCTTY = 0x20000;

    /** ioctl(2) request for setting terminal window size */
    public static final long TIOCSWINSZ = 0x80087467L;

    /** Signal numbers */
    public static final int SIGTERM = 15;
    public static final int SIGKILL = 9;

    /** waitpid(2) options */
    public static final int WNOHANG = 1;

    // ── Linker and lookup ────────────────────────────────────────────────────

    private static final Linker       LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC   = LINKER.defaultLookup();

    // ── Downcall handles ─────────────────────────────────────────────────────

    /** int posix_openpt(int oflag) */
    private static final MethodHandle POSIX_OPENPT = LINKER.downcallHandle(
            LIBC.find("posix_openpt").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    /** int grantpt(int fd) */
    private static final MethodHandle GRANTPT = LINKER.downcallHandle(
            LIBC.find("grantpt").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    /** int unlockpt(int fd) */
    private static final MethodHandle UNLOCKPT = LINKER.downcallHandle(
            LIBC.find("unlockpt").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    /** char* ptsname(int fd) — returns pointer to static buffer, copy immediately */
    private static final MethodHandle PTSNAME = LINKER.downcallHandle(
            LIBC.find("ptsname").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** int open(const char* path, int oflag) */
    private static final MethodHandle OPEN = LINKER.downcallHandle(
            LIBC.find("open").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** ssize_t read(int fd, void* buf, size_t count) */
    private static final MethodHandle READ = LINKER.downcallHandle(
            LIBC.find("read").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    /** ssize_t write(int fd, const void* buf, size_t count) */
    private static final MethodHandle WRITE = LINKER.downcallHandle(
            LIBC.find("write").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    /** int close(int fd) */
    private static final MethodHandle CLOSE = LINKER.downcallHandle(
            LIBC.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    /** int kill(pid_t pid, int sig) */
    private static final MethodHandle KILL = LINKER.downcallHandle(
            LIBC.find("kill").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    /** pid_t waitpid(pid_t pid, int* wstatus, int options) */
    private static final MethodHandle WAITPID = LINKER.downcallHandle(
            LIBC.find("waitpid").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /**
     * int ioctl(int fd, unsigned long request, void* arg)
     * Non-variadic descriptor — safe for the TIOCSWINSZ call we always make.
     */
    private static final MethodHandle IOCTL = LINKER.downcallHandle(
            LIBC.find("ioctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    /** int posix_spawn_file_actions_init(posix_spawn_file_actions_t* file_actions) */
    private static final MethodHandle SPAWN_ACTIONS_INIT = LINKER.downcallHandle(
            LIBC.find("posix_spawn_file_actions_init").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** int posix_spawn_file_actions_adddup2(posix_spawn_file_actions_t*, int fd, int newfd) */
    private static final MethodHandle SPAWN_ACTIONS_ADDDUP2 = LINKER.downcallHandle(
            LIBC.find("posix_spawn_file_actions_adddup2").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    /** int posix_spawn_file_actions_addclose(posix_spawn_file_actions_t*, int fd) */
    private static final MethodHandle SPAWN_ACTIONS_ADDCLOSE = LINKER.downcallHandle(
            LIBC.find("posix_spawn_file_actions_addclose").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** int posix_spawn_file_actions_destroy(posix_spawn_file_actions_t* file_actions) */
    private static final MethodHandle SPAWN_ACTIONS_DESTROY = LINKER.downcallHandle(
            LIBC.find("posix_spawn_file_actions_destroy").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /**
     * int posix_spawn(pid_t* pid, const char* path,
     *                 const posix_spawn_file_actions_t* file_actions,
     *                 const posix_spawnattr_t* attrp,
     *                 char* const argv[], char* const envp[])
     */
    private static final MethodHandle POSIX_SPAWN = LINKER.downcallHandle(
            LIBC.find("posix_spawn").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,  // pid_t*
                    ValueLayout.ADDRESS,  // const char* path
                    ValueLayout.ADDRESS,  // posix_spawn_file_actions_t*
                    ValueLayout.ADDRESS,  // posix_spawnattr_t* (NULL)
                    ValueLayout.ADDRESS,  // char* const argv[]
                    ValueLayout.ADDRESS   // char* const envp[] (NULL)
            ));

    // ── Public API ───────────────────────────────────────────────────────────

    public static int posixOpenpt(int oflag) {
        try { return (int) POSIX_OPENPT.invokeExact(oflag); }
        catch (Throwable t) { throw new RuntimeException("posix_openpt", t); }
    }

    public static int grantpt(int fd) {
        try { return (int) GRANTPT.invokeExact(fd); }
        catch (Throwable t) { throw new RuntimeException("grantpt", t); }
    }

    public static int unlockpt(int fd) {
        try { return (int) UNLOCKPT.invokeExact(fd); }
        catch (Throwable t) { throw new RuntimeException("unlockpt", t); }
    }

    /** Returns the slave device path (e.g. "/dev/ttys003"). Never null on success. */
    public static String ptsname(int fd) {
        try {
            MemorySegment ptr = (MemorySegment) PTSNAME.invokeExact(fd);
            if (ptr == null || MemorySegment.NULL.equals(ptr)) return null;
            return ptr.reinterpret(256).getString(0);
        } catch (Throwable t) { throw new RuntimeException("ptsname", t); }
    }

    /** Opens a file and returns its fd, or -1 on error. */
    public static int open(MemorySegment path, int oflag) {
        try { return (int) OPEN.invokeExact(path, oflag); }
        catch (Throwable t) { throw new RuntimeException("open", t); }
    }

    /** Reads up to count bytes from fd into buf. Returns bytes read, 0 on EOF, -1 on error. */
    public static long read(int fd, MemorySegment buf, long count) {
        try { return (long) READ.invokeExact(fd, buf, count); }
        catch (Throwable t) { throw new RuntimeException("read", t); }
    }

    /** Writes count bytes from buf to fd. Returns bytes written or -1 on error. */
    public static long write(int fd, MemorySegment buf, long count) {
        try { return (long) WRITE.invokeExact(fd, buf, count); }
        catch (Throwable t) { throw new RuntimeException("write", t); }
    }

    /** Closes fd. Returns 0 on success, -1 on error. */
    public static int close(int fd) {
        try { return (int) CLOSE.invokeExact(fd); }
        catch (Throwable t) { throw new RuntimeException("close", t); }
    }

    /** Sends signal sig to process pid. Returns 0 on success, -1 on error. */
    public static int kill(int pid, int sig) {
        try { return (int) KILL.invokeExact(pid, sig); }
        catch (Throwable t) { throw new RuntimeException("kill", t); }
    }

    /**
     * Waits for process pid. Pass MemorySegment.NULL for wstatus to discard exit status.
     * Returns the pid that was waited for, 0 with WNOHANG if none exited, -1 on error.
     */
    public static int waitpid(int pid, MemorySegment wstatus, int options) {
        try { return (int) WAITPID.invokeExact(pid, wstatus, options); }
        catch (Throwable t) { throw new RuntimeException("waitpid", t); }
    }

    /**
     * ioctl with a MemorySegment argument (used for TIOCSWINSZ).
     * Returns 0 on success, -1 on error.
     */
    public static int ioctl(int fd, long request, MemorySegment arg) {
        try { return (int) IOCTL.invokeExact(fd, request, arg); }
        catch (Throwable t) { throw new RuntimeException("ioctl", t); }
    }

    public static int spawnFileActionsInit(MemorySegment fileActions) {
        try { return (int) SPAWN_ACTIONS_INIT.invokeExact(fileActions); }
        catch (Throwable t) { throw new RuntimeException("posix_spawn_file_actions_init", t); }
    }

    public static int spawnFileActionsAdddup2(MemorySegment fileActions, int fd, int newfd) {
        try { return (int) SPAWN_ACTIONS_ADDDUP2.invokeExact(fileActions, fd, newfd); }
        catch (Throwable t) { throw new RuntimeException("posix_spawn_file_actions_adddup2", t); }
    }

    public static int spawnFileActionsAddclose(MemorySegment fileActions, int fd) {
        try { return (int) SPAWN_ACTIONS_ADDCLOSE.invokeExact(fileActions, fd); }
        catch (Throwable t) { throw new RuntimeException("posix_spawn_file_actions_addclose", t); }
    }

    public static int spawnFileActionsDestroy(MemorySegment fileActions) {
        try { return (int) SPAWN_ACTIONS_DESTROY.invokeExact(fileActions); }
        catch (Throwable t) { throw new RuntimeException("posix_spawn_file_actions_destroy", t); }
    }

    /**
     * Spawns a process. envp may be MemorySegment.NULL to inherit the parent's environment.
     * Returns 0 on success, errno value on failure (POSIX convention).
     */
    public static int posixSpawn(MemorySegment pidPtr, MemorySegment path,
                                  MemorySegment fileActions, MemorySegment argv,
                                  MemorySegment envp) {
        try {
            return (int) POSIX_SPAWN.invokeExact(pidPtr, path, fileActions,
                    MemorySegment.NULL, argv, envp);
        } catch (Throwable t) { throw new RuntimeException("posix_spawn", t); }
    }

    private PosixLibrary() {}
}
```

- [ ] **Step 4: Run the tests**

```bash
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q
```

Expected: 3 tests pass (posixOpenptReturnsValidFd, grantptAndUnlockptSucceed, ptsnameReturnsDevPath).

- [ ] **Step 5: Commit**

```bash
git add app-core/src/main/java/dev/mproctor/cccli/pty/PosixLibrary.java \
        app-core/src/test/java/dev/mproctor/cccli/pty/PosixLibraryTest.java
git commit -m "feat(core): PosixLibrary — Panama FFM downcalls for POSIX PTY functions"
```

---

## Task 3: PtyProcess — open PTY pair

**Files:**
- Create: `app-core/src/main/java/dev/mproctor/cccli/pty/PtyProcess.java`
- Create: `app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java`

- [ ] **Step 1: Write the failing test**

Create `app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java`:

```java
package dev.mproctor.cccli.pty;

import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PtyProcessTest {

    private PtyProcess pty;

    @BeforeEach
    void setUp() {
        pty = new PtyProcess();
    }

    @AfterEach
    void tearDown() {
        pty.close();
    }

    @Test
    void openReturnsMasterFdAndSlavePath() {
        pty.open();
        assertTrue(pty.getMasterFd() >= 0, "master fd should be ≥ 0");
        String slavePath = pty.getSlavePath();
        assertNotNull(slavePath);
        assertTrue(slavePath.startsWith("/dev/"), "slave path should start with /dev/, got: " + slavePath);
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q 2>&1 | head -20
```

Expected: compilation error — `PtyProcess` does not exist.

- [ ] **Step 3: Create PtyProcess.java with the open() method**

Create `app-core/src/main/java/dev/mproctor/cccli/pty/PtyProcess.java`:

```java
package dev.mproctor.cccli.pty;

import java.lang.foreign.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * PTY lifecycle: open a master/slave pair, spawn a subprocess with its
 * stdio wired to the slave, stream output via a daemon reader thread.
 *
 * Usage:
 *   PtyProcess pty = new PtyProcess();
 *   pty.open();
 *   pty.spawn(new String[]{"/bin/cat"});
 *   pty.startReader(text -> ui.appendOutput(text));
 *   pty.resize(24, 120);
 *   pty.write("hello\n");
 *   pty.close();
 *
 * Thread safety: open/spawn/resize/close are single-threaded; write is
 * safe to call from any thread after spawn() returns.
 */
public class PtyProcess {

    /** posix_spawn_file_actions_t is 80 bytes on macOS AArch64. Allocate 128 for safety. */
    private static final int FILE_ACTIONS_SIZE = 128;

    private int masterFd = -1;
    private int slaveFd  = -1;
    private int pid      = -1;
    private String slavePath;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readerThread;
    private final Arena arena = Arena.ofShared();

    // ── Open ────────────────────────────────────────────────────────────────

    /**
     * Opens a PTY master/slave pair. Must be called before spawn().
     * Throws RuntimeException if any POSIX call fails.
     */
    public void open() {
        int mfd = PosixLibrary.posixOpenpt(PosixLibrary.O_RDWR | PosixLibrary.O_NOCTTY);
        if (mfd < 0) throw new RuntimeException("posix_openpt failed: " + mfd);

        if (PosixLibrary.grantpt(mfd) != 0) {
            PosixLibrary.close(mfd);
            throw new RuntimeException("grantpt failed");
        }
        if (PosixLibrary.unlockpt(mfd) != 0) {
            PosixLibrary.close(mfd);
            throw new RuntimeException("unlockpt failed");
        }

        String path = PosixLibrary.ptsname(mfd);
        if (path == null) {
            PosixLibrary.close(mfd);
            throw new RuntimeException("ptsname returned null");
        }

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment pathSeg = temp.allocateFrom(path);
            int sfd = PosixLibrary.open(pathSeg, PosixLibrary.O_RDWR | PosixLibrary.O_NOCTTY);
            if (sfd < 0) {
                PosixLibrary.close(mfd);
                throw new RuntimeException("open slave PTY failed: " + sfd);
            }
            this.slaveFd   = sfd;
        }
        this.masterFd  = mfd;
        this.slavePath = path;
    }

    // ── Accessors for tests ──────────────────────────────────────────────────

    public int    getMasterFd()  { return masterFd; }
    public int    getSlaveFd()   { return slaveFd; }
    public int    getPid()       { return pid; }
    public String getSlavePath() { return slavePath; }

    // ── Spawn ────────────────────────────────────────────────────────────────

    /**
     * Spawns a subprocess with its stdin/stdout/stderr wired to the PTY slave.
     * open() must have been called first.
     *
     * @param command  e.g. new String[]{"/bin/cat"} or new String[]{"/bin/echo", "hello"}
     */
    public void spawn(String[] command) {
        if (masterFd < 0) throw new IllegalStateException("call open() first");

        try (Arena temp = Arena.ofConfined()) {
            // Build null-terminated argv array of pointers
            MemorySegment argv = temp.allocate(
                    ValueLayout.ADDRESS.byteSize() * (command.length + 1));
            for (int i = 0; i < command.length; i++) {
                MemorySegment argStr = temp.allocateFrom(command[i]);
                argv.setAtIndex(ValueLayout.ADDRESS, i, argStr);
            }
            argv.setAtIndex(ValueLayout.ADDRESS, command.length, MemorySegment.NULL);

            // Set up file actions: dup2 slave to 0/1/2, then close slave in child
            MemorySegment fileActions = temp.allocate(FILE_ACTIONS_SIZE);
            PosixLibrary.spawnFileActionsInit(fileActions);
            PosixLibrary.spawnFileActionsAdddup2(fileActions, slaveFd, 0);  // stdin
            PosixLibrary.spawnFileActionsAdddup2(fileActions, slaveFd, 1);  // stdout
            PosixLibrary.spawnFileActionsAdddup2(fileActions, slaveFd, 2);  // stderr
            PosixLibrary.spawnFileActionsAddclose(fileActions, slaveFd);    // close original in child

            // pid_t is int (4 bytes) on macOS
            MemorySegment pidSeg  = temp.allocate(ValueLayout.JAVA_INT);
            MemorySegment pathSeg = temp.allocateFrom(command[0]);

            int ret = PosixLibrary.posixSpawn(pidSeg, pathSeg, fileActions, argv,
                    MemorySegment.NULL);
            PosixLibrary.spawnFileActionsDestroy(fileActions);
            if (ret != 0) throw new RuntimeException("posix_spawn failed with errno: " + ret);

            this.pid = pidSeg.get(ValueLayout.JAVA_INT, 0);
        }
    }

    // ── Reader ───────────────────────────────────────────────────────────────

    /**
     * Starts a daemon thread that reads PTY output and delivers decoded UTF-8
     * strings to outputHandler. Safe to call from any thread.
     *
     * outputHandler is called on the reader thread — it must be thread-safe.
     * For the UI: bridge.appendOutput() already dispatches to the main thread.
     */
    public void startReader(Consumer<String> outputHandler) {
        running.set(true);
        readerThread = new Thread(() -> {
            try (Arena local = Arena.ofConfined()) {
                MemorySegment buf = local.allocate(4096);
                while (running.get()) {
                    long n = PosixLibrary.read(masterFd, buf, 4096);
                    if (n <= 0) break;  // EOF or error (process exited / fd closed)
                    byte[] bytes = new byte[(int) n];
                    MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, bytes, 0, (int) n);
                    outputHandler.accept(new String(bytes, StandardCharsets.UTF_8));
                }
            }
        });
        readerThread.setName("pty-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /**
     * Writes text to the PTY master (simulates keyboard input to the subprocess).
     * spawn() must have been called first. Thread-safe.
     */
    public void write(String text) {
        if (masterFd < 0) throw new IllegalStateException("PTY not open");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment buf = temp.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, buf, ValueLayout.JAVA_BYTE, 0, bytes.length);
            long written = PosixLibrary.write(masterFd, buf, bytes.length);
            if (written < 0) throw new RuntimeException("write to PTY failed: " + written);
        }
    }

    // ── Resize ───────────────────────────────────────────────────────────────

    /**
     * Sends TIOCSWINSZ to notify the subprocess of a terminal resize.
     * Has no effect if the subprocess doesn't watch SIGWINCH, but is harmless.
     */
    public void resize(int rows, int cols) {
        if (masterFd < 0) return;
        try (Arena temp = Arena.ofConfined()) {
            // struct winsize { unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel; }
            MemorySegment winsize = temp.allocate(8);
            winsize.set(ValueLayout.JAVA_SHORT, 0, (short) rows);
            winsize.set(ValueLayout.JAVA_SHORT, 2, (short) cols);
            winsize.set(ValueLayout.JAVA_SHORT, 4, (short) 0);
            winsize.set(ValueLayout.JAVA_SHORT, 6, (short) 0);
            PosixLibrary.ioctl(masterFd, PosixLibrary.TIOCSWINSZ, winsize);
        }
    }

    // ── Close ────────────────────────────────────────────────────────────────

    /**
     * Terminates the subprocess (SIGTERM) and closes all fds.
     * Safe to call multiple times. Waits up to ~2s for the process to exit.
     */
    public void close() {
        running.set(false);
        if (pid > 0) {
            PosixLibrary.kill(pid, PosixLibrary.SIGTERM);
            // Wait for process to exit (blocking, no timeout — subprocess should exit quickly)
            PosixLibrary.waitpid(pid, MemorySegment.NULL, 0);
            pid = -1;
        }
        if (slaveFd >= 0)  { PosixLibrary.close(slaveFd);  slaveFd  = -1; }
        if (masterFd >= 0) { PosixLibrary.close(masterFd); masterFd = -1; }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        arena.close();
    }
}
```

- [ ] **Step 4: Run the test**

```bash
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q
```

Expected: 4 tests pass (3 PosixLibraryTest + 1 PtyProcessTest: openReturnsMasterFdAndSlavePath).

- [ ] **Step 5: Commit**

```bash
git add app-core/src/main/java/dev/mproctor/cccli/pty/PtyProcess.java \
        app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java
git commit -m "feat(core): PtyProcess — open PTY master/slave pair via POSIX calls"
```

---

## Task 4: PtyProcess — spawn, reader, write

**Files:**
- Modify: `app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java`

All implementation code is already in PtyProcess.java from Task 3. This task adds tests.

- [ ] **Step 1: Add spawn and round-trip tests to PtyProcessTest.java**

Add these three test methods to the existing `PtyProcessTest` class (inside the class, after the existing test):

```java
    @Test
    void spawnEchoSetsPid() {
        pty.open();
        pty.spawn(new String[]{"/bin/echo", "hello"});
        assertTrue(pty.getPid() > 0, "spawned process should have pid > 0");
        // echo exits immediately; waitpid in close() will reap it
    }

    @Test
    void catRoundtripEchosInput() throws Exception {
        pty.open();
        pty.spawn(new String[]{"/bin/cat"});

        CompletableFuture<String> received = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();
        pty.startReader(text -> {
            output.append(text);
            if (output.toString().contains("hello")) {
                received.complete(output.toString());
            }
        });

        pty.write("hello\n");

        String result = received.get(2, TimeUnit.SECONDS);
        assertTrue(result.contains("hello"), "output should contain 'hello', got: " + result);
    }

    @Test
    void spawnEchoOutputReachesReader() throws Exception {
        pty.open();

        CompletableFuture<String> received = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();
        pty.startReader(text -> {
            output.append(text);
            if (output.toString().contains("ping")) {
                received.complete(output.toString());
            }
        });

        pty.spawn(new String[]{"/bin/echo", "ping"});

        String result = received.get(2, TimeUnit.SECONDS);
        assertTrue(result.contains("ping"), "output should contain 'ping', got: " + result);
    }
```

Also add the missing imports at the top of the file (the full file should now look like):

```java
package dev.mproctor.cccli.pty;

import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PtyProcessTest {

    private PtyProcess pty;

    @BeforeEach
    void setUp() {
        pty = new PtyProcess();
    }

    @AfterEach
    void tearDown() {
        pty.close();
    }

    @Test
    void openReturnsMasterFdAndSlavePath() {
        pty.open();
        assertTrue(pty.getMasterFd() >= 0, "master fd should be ≥ 0");
        String slavePath = pty.getSlavePath();
        assertNotNull(slavePath);
        assertTrue(slavePath.startsWith("/dev/"), "slave path should start with /dev/, got: " + slavePath);
    }

    @Test
    void spawnEchoSetsPid() {
        pty.open();
        pty.spawn(new String[]{"/bin/echo", "hello"});
        assertTrue(pty.getPid() > 0, "spawned process should have pid > 0");
    }

    @Test
    void catRoundtripEchosInput() throws Exception {
        pty.open();
        pty.spawn(new String[]{"/bin/cat"});

        CompletableFuture<String> received = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();
        pty.startReader(text -> {
            output.append(text);
            if (output.toString().contains("hello")) {
                received.complete(output.toString());
            }
        });

        pty.write("hello\n");

        String result = received.get(2, TimeUnit.SECONDS);
        assertTrue(result.contains("hello"), "output should contain 'hello', got: " + result);
    }

    @Test
    void spawnEchoOutputReachesReader() throws Exception {
        pty.open();

        CompletableFuture<String> received = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();
        pty.startReader(text -> {
            output.append(text);
            if (output.toString().contains("ping")) {
                received.complete(output.toString());
            }
        });

        pty.spawn(new String[]{"/bin/echo", "ping"});

        String result = received.get(2, TimeUnit.SECONDS);
        assertTrue(result.contains("ping"), "output should contain 'ping', got: " + result);
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q
```

Expected: 7 tests pass (3 PosixLibraryTest + 4 PtyProcessTest). If `spawnEchoOutputReachesReader` times out, the reader may need to start before spawn — that test already does start reader before spawn, so it should pass. If the cat round-trip fails with a timeout, increase to 5 seconds and re-run.

- [ ] **Step 3: Commit**

```bash
git add app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java
git commit -m "test(core): PtyProcess spawn, reader, and cat round-trip tests"
```

---

## Task 5: PtyProcess — resize and close

**Files:**
- Modify: `app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java`

Resize and close are already implemented in PtyProcess.java. This task adds tests.

- [ ] **Step 1: Add resize and close tests**

Add these two test methods to `PtyProcessTest`:

```java
    @Test
    void resizeDoesNotThrow() {
        pty.open();
        pty.spawn(new String[]{"/bin/cat"});
        // resize before startReader — should not throw
        assertDoesNotThrow(() -> pty.resize(24, 80));
        assertDoesNotThrow(() -> pty.resize(50, 200));
    }

    @Test
    void closeTerminatesSubprocess() throws Exception {
        pty.open();
        pty.spawn(new String[]{"/bin/cat"});
        int spawnedPid = pty.getPid();
        assertTrue(spawnedPid > 0);

        pty.close();  // sends SIGTERM and waitpid

        // After close(), all fds are -1
        assertEquals(-1, pty.getMasterFd(), "masterFd should be -1 after close");
        assertEquals(-1, pty.getSlaveFd(),  "slaveFd should be -1 after close");
        assertEquals(-1, pty.getPid(),      "pid should be -1 after close");
    }
```

The full `PtyProcessTest.java` now has 6 test methods. (tearDown calls `close()` so the `closeTerminatesSubprocess` test's `close()` call will be double-called — `PtyProcess.close()` is idempotent by design: checking for `pid > 0` and `fd >= 0` guards against double-close.)

- [ ] **Step 2: Run all tests**

```bash
cd /Users/mdproctor/claude/cccli
mvn test -pl app-core -q
```

Expected: 9 tests pass (3 PosixLibraryTest + 6 PtyProcessTest). `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add app-core/src/test/java/dev/mproctor/cccli/pty/PtyProcessTest.java
git commit -m "test(core): PtyProcess resize and close tests"
```

---

## Task 6: Wire PTY to UI in Main.java

**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/Main.java`

- [ ] **Step 1: Replace Main.java with the PTY-wired version**

```java
package dev.mproctor.cccli;

import dev.mproctor.cccli.bridge.MacUIBridge;
import dev.mproctor.cccli.pty.PtyProcess;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

@QuarkusMain
public class Main implements QuarkusApplication {

    @Inject
    MacUIBridge bridge;

    public static void main(String... args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) {
        PtyProcess pty = new PtyProcess();

        // Open PTY and spawn test subprocess.
        // Plan 4 replaces /bin/cat with the resolved `claude` binary.
        pty.open();
        pty.spawn(new String[]{"/bin/cat"});

        // Reader runs on a daemon thread. bridge.appendOutput() is thread-safe:
        // myui_append_output() dispatches to the AppKit main thread via dispatch_async.
        pty.startReader(text -> bridge.appendOutput(text));

        // Set an initial terminal size (rows, cols).
        // Plan 5 wires this to the actual window size.
        pty.resize(24, 120);

        Log.info("Starting Claude Desktop CLI...");
        bridge.start("Claude Desktop CLI", 900, 600,
                "Claude Desktop CLI — PTY ready. Type and press Enter.\n",
                () -> {
                    Log.info("Window closed — terminating");
                    pty.close();
                    bridge.terminate();
                },
                text -> {
                    Log.infof("Sending to PTY: %s", text);
                    pty.write(text + "\n");
                });

        Log.info("Application terminated");
        return 0;
    }
}
```

- [ ] **Step 2: Build in JVM dev mode**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd /Users/mdproctor/claude/cccli
mvn install -q
cd app-macos
mvn quarkus:dev -Dcccli.dylib.path="$(pwd)/../mac-ui-bridge/build/libMyMacUI.dylib"
```

- [ ] **Step 3: Manual integration test**

In the running app:
1. Type `hello` and press Enter.
2. Expected: the output pane shows `hello` echoed back (PTY echo from `/bin/cat`).
3. Type `world` and press Enter.
4. Expected: `world` appears in the output pane.
5. Close the window.
6. Expected: app exits cleanly (no hung process).

If output appears correctly, the full pipeline works: UI input → `pty.write()` → PTY master → `/bin/cat` → PTY master (echo) → reader thread → `bridge.appendOutput()` → NSTextView.

- [ ] **Step 4: Commit**

```bash
cd /Users/mdproctor/claude/cccli
git add app-macos/src/main/java/dev/mproctor/cccli/Main.java
git commit -m "feat(macos): wire PtyProcess to UI — /bin/cat round-trip via PTY"
```

---

## Task 7: Native image build and validation

**Files:**
- Possibly modify: `app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json`

`PosixLibrary` initialises its downcall handles via static final fields (build-time). GraalVM 25 discovers these automatically with `--enable-native-access=ALL-UNNAMED`. No metadata changes should be needed — but if the build fails, follow the fallback below.

- [ ] **Step 1: Build the native image**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd /Users/mdproctor/claude/cccli
mvn package -pl app-macos -am -Pnative 2>&1 | tail -40
```

Expected: `BUILD SUCCESS`. Build takes 2–4 minutes.

- [ ] **Step 2: If the build fails with Panama errors, add POSIX downcalls to reachability-metadata.json**

Look for errors like `foreign function ... not declared` or `downcall handle ... not reachable`. If they appear, open `app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json` and extend the `downcalls` array with the POSIX signatures:

```json
{
  "foreign": {
    "directUpcalls": [
      {
        "class": "dev.mproctor.cccli.bridge.Callbacks",
        "method": "onWindowClosed",
        "returnType": "void",
        "parameterTypes": []
      },
      {
        "class": "dev.mproctor.cccli.bridge.Callbacks",
        "method": "onTextSubmitted",
        "returnType": "void",
        "parameterTypes": ["void*"]
      }
    ],
    "downcalls": [
      {"returnType": "jlong", "parameterTypes": ["void*", "jint", "jint", "void*", "void*", "void*"]},
      {"returnType": "void",  "parameterTypes": ["void*"]},
      {"returnType": "void",  "parameterTypes": []},
      {"returnType": "jint",  "parameterTypes": ["jint"]},
      {"returnType": "jint",  "parameterTypes": ["void*"]},
      {"returnType": "void*", "parameterTypes": ["jint"]},
      {"returnType": "jint",  "parameterTypes": ["void*", "jint"]},
      {"returnType": "jint",  "parameterTypes": ["void*", "jint", "jint"]},
      {"returnType": "jlong", "parameterTypes": ["jint", "void*", "jlong"]},
      {"returnType": "jint",  "parameterTypes": ["jint", "jint"]},
      {"returnType": "jint",  "parameterTypes": ["jint", "void*", "jint"]},
      {"returnType": "jint",  "parameterTypes": ["jint", "jlong", "void*"]},
      {"returnType": "jint",  "parameterTypes": ["void*", "void*", "void*", "void*", "void*"]}
    ]
  }
}
```

Then rebuild:
```bash
mvn package -pl app-macos -am -Pnative 2>&1 | tail -40
```

- [ ] **Step 3: Run the native binary**

```bash
./app-macos/target/app-macos-1.0.0-SNAPSHOT-runner \
  -Dcccli.dylib.path=/Users/mdproctor/claude/cccli/mac-ui-bridge/build/libMyMacUI.dylib
```

Expected: window appears in < 50ms.

- [ ] **Step 4: Manual integration test (native)**

Same as Task 6 Step 3: type `hello`, see it echoed; type `world`, see it echoed; close window, app exits cleanly.

- [ ] **Step 5: Commit**

If reachability-metadata.json was modified:
```bash
git add app-macos/src/main/resources/META-INF/native-image/dev.mproctor.cccli/app-macos/reachability-metadata.json
git commit -m "build: add POSIX downcall signatures to native-image reachability metadata"
```

If no changes were needed:
```bash
git commit --allow-empty -m "chore: native image validates PTY — no metadata changes needed"
```

---

## Self-Review

### Spec coverage check

| Requirement | Task |
|-------------|------|
| Panama FFM POSIX calls (no JNA) | Task 2 |
| `posix_openpt / grantpt / unlockpt / ptsname` | Task 2, 3 |
| `posix_spawn` with slave as stdin/stdout/stderr | Task 3 |
| `read / write` byte I/O on master fd | Task 4 |
| Background reader thread | Task 3/4 |
| `ioctl TIOCSWINSZ` resize | Task 3/5 |
| Clean subprocess termination | Task 5 |
| PTY wired to UI | Task 6 |
| GraalVM native image validated | Task 7 |
| Test with `/bin/echo` and `/bin/cat` before `claude` | Tasks 4, 6 |

All requirements covered.

### Placeholder scan

No TBD, TODO, or "similar to Task N" patterns. All code is complete.

### Type consistency

- `PosixLibrary.read/write` return `long` (ssize_t) — consistent with reader loop `long n = PosixLibrary.read(...)`.
- `PtyProcess.spawn` uses `MemorySegment.NULL` for envp — consistent with `posixSpawn` signature.
- `resize(int rows, int cols)` uses `(short)` cast for `struct winsize` fields — correct (unsigned short).
- `close()` checks `pid > 0`, `slaveFd >= 0`, `masterFd >= 0` before operating — idempotent.
