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
