package dev.mproctor.cccli.pty;

import org.junit.jupiter.api.Test;
import java.lang.foreign.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class PosixLibraryTest {

    // ── PTY open sequence ─────────────────────────────────────────────────────

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

    // ── open() / close() ──────────────────────────────────────────────────────

    @Test
    void openSlaveAndClose() {
        int masterFd = PosixLibrary.posixOpenpt(PosixLibrary.O_RDWR | PosixLibrary.O_NOCTTY);
        assertTrue(masterFd >= 0);
        PosixLibrary.grantpt(masterFd);
        PosixLibrary.unlockpt(masterFd);
        String slavePath = PosixLibrary.ptsname(masterFd);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSeg = arena.allocateFrom(slavePath);
            int slaveFd = PosixLibrary.open(pathSeg, PosixLibrary.O_RDWR | PosixLibrary.O_NOCTTY);
            assertTrue(slaveFd >= 0, "open(slave path) should return a valid fd");
            assertEquals(0, PosixLibrary.close(slaveFd), "close(slave fd) should return 0");
        }
        PosixLibrary.close(masterFd);
    }

    // ── write() / read() ──────────────────────────────────────────────────────

    @Test
    void writeToSlaveIsReadableFromMaster() {
        int masterFd = PosixLibrary.posixOpenpt(PosixLibrary.O_RDWR | PosixLibrary.O_NOCTTY);
        PosixLibrary.grantpt(masterFd);
        PosixLibrary.unlockpt(masterFd);
        String slavePath = PosixLibrary.ptsname(masterFd);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSeg = arena.allocateFrom(slavePath);
            int slaveFd = PosixLibrary.open(pathSeg, PosixLibrary.O_RDWR | PosixLibrary.O_NOCTTY);

            // Write "test\n" to slave stdout → readable from master
            byte[] toWrite = "test\n".getBytes(StandardCharsets.UTF_8);
            MemorySegment writeBuf = arena.allocate(toWrite.length);
            MemorySegment.copy(toWrite, 0, writeBuf, ValueLayout.JAVA_BYTE, 0, toWrite.length);
            long written = PosixLibrary.write(slaveFd, writeBuf, toWrite.length);
            assertEquals(toWrite.length, written, "write should return the number of bytes written");

            // Read it back from master
            MemorySegment readBuf = arena.allocate(64);
            long n = PosixLibrary.read(masterFd, readBuf, 64);
            assertTrue(n > 0, "read should return bytes written to slave");

            byte[] readBytes = new byte[(int) n];
            MemorySegment.copy(readBuf, ValueLayout.JAVA_BYTE, 0, readBytes, 0, (int) n);
            String result = new String(readBytes, StandardCharsets.UTF_8);
            assertTrue(result.contains("test"), "read data should contain 'test', got: " + result);

            PosixLibrary.close(slaveFd);
        }
        PosixLibrary.close(masterFd);
    }

    // ── kill() ────────────────────────────────────────────────────────────────

    @Test
    void killReturnsMinus1ForNonexistentPid() {
        // A very large PID that should not exist
        int result = PosixLibrary.kill(999_999_999, PosixLibrary.SIGTERM);
        assertEquals(-1, result, "kill on a nonexistent pid should return -1 (ESRCH)");
    }

    @Test
    void killWithSigintConstantIsCorrectValue() {
        // SIGINT = 2 on POSIX — validate the constant is correct before relying on it
        assertEquals(2, PosixLibrary.SIGINT, "SIGINT should be 2 on macOS");
    }

    // ── tcgetattr / tcsetattr (ECHO flag) ─────────────────────────────────────

    @Test
    void tcgetattrAndTcsetattrWork() {
        // tcgetattr on a PTY master requires the slave to be open first on macOS.
        int masterFd = PosixLibrary.posixOpenpt(PosixLibrary.O_RDWR | PosixLibrary.O_NOCTTY);
        assertTrue(masterFd >= 0);
        PosixLibrary.grantpt(masterFd);
        PosixLibrary.unlockpt(masterFd);
        String slavePath = PosixLibrary.ptsname(masterFd);

        try (Arena arena = Arena.ofConfined()) {
            // Open slave so the line discipline is active (required for tcgetattr on master)
            MemorySegment pathSeg = arena.allocateFrom(slavePath);
            int slaveFd = PosixLibrary.open(pathSeg, PosixLibrary.O_RDWR | PosixLibrary.O_NOCTTY);
            assertTrue(slaveFd >= 0);

            MemorySegment termios = arena.allocate(64);
            assertEquals(0, PosixLibrary.tcgetattr(masterFd, termios),
                    "tcgetattr on master (with slave open) should return 0");

            // Verify ECHO is initially set (default PTY line discipline state)
            long lflag = termios.get(ValueLayout.JAVA_LONG, PosixLibrary.TERMIOS_LFLAG_OFFSET);
            assertNotEquals(0, lflag & PosixLibrary.ECHO_FLAG, "ECHO should be set by default");

            // Clear ECHO and write back
            termios.set(ValueLayout.JAVA_LONG, PosixLibrary.TERMIOS_LFLAG_OFFSET,
                    lflag & ~PosixLibrary.ECHO_FLAG);
            assertEquals(0, PosixLibrary.tcsetattr(masterFd, PosixLibrary.TCSANOW, termios),
                    "tcsetattr should return 0");

            // Read back and confirm ECHO is cleared
            MemorySegment termios2 = arena.allocate(64);
            PosixLibrary.tcgetattr(masterFd, termios2);
            long readBack = termios2.get(ValueLayout.JAVA_LONG, PosixLibrary.TERMIOS_LFLAG_OFFSET);
            assertEquals(0, readBack & PosixLibrary.ECHO_FLAG,
                    "ECHO flag should be cleared after tcsetattr");

            PosixLibrary.close(slaveFd);
        }
        PosixLibrary.close(masterFd);
    }
}
