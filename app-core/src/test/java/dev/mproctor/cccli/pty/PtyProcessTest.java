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
