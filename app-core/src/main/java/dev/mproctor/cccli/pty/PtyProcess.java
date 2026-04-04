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
            long total = 0;
            while (total < bytes.length) {
                long n = PosixLibrary.write(masterFd, buf.asSlice(total), bytes.length - total);
                if (n < 0) throw new RuntimeException("write to PTY failed: " + n);
                total += n;
            }
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
     * Blocks until the subprocess exits — no timeout.
     * Not safe for concurrent callers; single-threaded close only.
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
    }
}
