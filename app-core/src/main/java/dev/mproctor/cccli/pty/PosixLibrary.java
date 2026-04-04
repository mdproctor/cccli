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
