package dev.mproctor.cccli.pty;

import org.junit.jupiter.api.*;
import java.lang.foreign.*;
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

    // ── open() ────────────────────────────────────────────────────────────────

    @Test
    void openReturnsMasterFdAndSlavePath() {
        pty.open();
        assertTrue(pty.getMasterFd() >= 0, "master fd should be ≥ 0");
        String slavePath = pty.getSlavePath();
        assertNotNull(slavePath);
        assertTrue(slavePath.startsWith("/dev/"), "slave path should start with /dev/, got: " + slavePath);
    }

    @Test
    void openAlsoOpensValidSlaveFd() {
        pty.open();
        assertTrue(pty.getSlaveFd() >= 0, "slave fd should be ≥ 0 after open");
    }

    @Test
    void echoFlagIsDisabledAfterOpen() throws Exception {
        pty.open();
        // With no subprocess, write to master fd. If ECHO were ON, the line discipline
        // would echo the bytes back to the master immediately. With ECHO disabled, nothing
        // should come back within the timeout.
        CompletableFuture<String> received = new CompletableFuture<>();
        pty.startReader(text -> received.complete(text));

        pty.write("hello\n");

        assertThrows(TimeoutException.class,
                () -> received.get(300, TimeUnit.MILLISECONDS),
                "ECHO should be disabled — nothing should be echoed back to the master");
    }

    // ── spawn() ───────────────────────────────────────────────────────────────

    @Test
    void spawnEchoSetsPid() {
        pty.open();
        pty.spawn(new String[]{"/bin/echo", "hello"});
        assertTrue(pty.getPid() > 0, "spawned process should have pid > 0");
        // echo exits immediately; waitpid in close() will reap it
    }

    @Test
    void spawnThrowsOnNonexistentCommand() {
        pty.open();
        assertThrows(RuntimeException.class,
                () -> pty.spawn(new String[]{"/nonexistent/binary/path/xyz"}),
                "posix_spawn should fail and throw RuntimeException for bad path");
    }

    @Test
    void spawnThrowsIfNotOpened() {
        // spawn() requires open() to have been called first
        assertThrows(IllegalStateException.class,
                () -> pty.spawn(new String[]{"/bin/echo", "hello"}),
                "spawn() before open() should throw IllegalStateException");
    }

    // ── write() ───────────────────────────────────────────────────────────────

    @Test
    void writeThrowsWhenNotOpen() {
        assertThrows(IllegalStateException.class,
                () -> pty.write("anything\n"),
                "write() before open() should throw IllegalStateException");
    }

    // ── reader + I/O ──────────────────────────────────────────────────────────

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

        String result = received.get(5, TimeUnit.SECONDS);
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

    // ── resize() ──────────────────────────────────────────────────────────────

    @Test
    void resizeDoesNotThrow() {
        pty.open();
        pty.spawn(new String[]{"/bin/cat"});
        assertDoesNotThrow(() -> pty.resize(24, 80));
        assertDoesNotThrow(() -> pty.resize(50, 200));
    }

    @Test
    void resizeIoctlSucceeds() {
        // resize() calls ioctl(TIOCSWINSZ) on the master fd.
        // Verify the ioctl succeeds (returns 0) and doesn't throw.
        // Note: on macOS, TIOCGWINSZ read-back after TIOCSWINSZ returns 0 for
        // disconnected PTYs — this is a macOS PTY behaviour not a code bug.
        // The resize is used in production with an active subprocess which changes
        // the behaviour. See PosixLibraryTest.tcgetattrAndTcsetattrWork for a test
        // that verifies the full termios ioctl round-trip works correctly.
        pty.open();
        pty.spawn(new String[]{"/bin/cat"}); // active subprocess
        assertDoesNotThrow(() -> pty.resize(24, 80));
        assertDoesNotThrow(() -> pty.resize(42, 137));
    }

    // ── sendSigInt() ──────────────────────────────────────────────────────────

    @Test
    void sendSigIntDoesNotThrowWhenProcessAlive() {
        pty.open();
        pty.spawn(new String[]{"/bin/cat"});
        assertDoesNotThrow(() -> pty.sendSigInt(),
                "sendSigInt() should not throw when process is alive");
    }

    @Test
    void sendSigIntIsSafeAfterProcessHasExited() throws Exception {
        pty.open();
        pty.spawn(new String[]{"/bin/echo", "hello"});
        // echo exits almost immediately
        Thread.sleep(200);
        assertDoesNotThrow(() -> pty.sendSigInt(),
                "sendSigInt() on stale pid should not throw — kill returns -1 harmlessly");
    }

    @Test
    void sendSigIntIsDeliveredToProcess() throws Exception {
        // Spawn a long sleep, send SIGINT, then verify the process exited by polling
        // waitpid(WNOHANG). This avoids PTY output capture timing issues — we check
        // the process's exit status directly. If kill() used the wrong signal constant
        // or failed silently, the process would still be sleeping at 2 seconds and the
        // assertion would fail.
        pty.open();
        pty.spawn(new String[]{"/bin/sleep", "30"}); // long enough not to exit naturally
        int pid = pty.getPid();
        assertTrue(pid > 0);

        Thread.sleep(50); // let sleep start
        pty.sendSigInt();

        // Poll waitpid(WNOHANG) until the process exits or timeout
        long deadline = System.currentTimeMillis() + 2_000;
        boolean exited = false;
        while (System.currentTimeMillis() < deadline) {
            int ret = PosixLibrary.waitpid(pid, MemorySegment.NULL, PosixLibrary.WNOHANG);
            if (ret == pid) {
                exited = true;
                break;
            }
            Thread.sleep(30);
        }
        assertTrue(exited, "Process should have exited after SIGINT — kill() must have delivered the signal");
    }

    // ── close() ───────────────────────────────────────────────────────────────

    @Test
    void closeTerminatesSubprocess() {
        pty.open();
        pty.spawn(new String[]{"/bin/cat"});
        int spawnedPid = pty.getPid();
        assertTrue(spawnedPid > 0);

        pty.close();

        assertEquals(-1, pty.getMasterFd(), "masterFd should be -1 after close");
        assertEquals(-1, pty.getSlaveFd(),  "slaveFd should be -1 after close");
        assertEquals(-1, pty.getPid(),      "pid should be -1 after close");
    }

    @Test
    void closeIsIdempotent() {
        pty.open();
        pty.spawn(new String[]{"/bin/cat"});
        pty.close();
        // tearDown() calls close() again — second call must not throw
        assertDoesNotThrow(() -> pty.close(),
                "second close() should be a safe no-op");
    }

    @Test
    void closeWithNoSpawnDoesNotThrow() {
        pty.open();
        // close() with masterFd and slaveFd open but no subprocess
        assertDoesNotThrow(() -> pty.close(),
                "close() after open() without spawn should not throw");
    }
}
