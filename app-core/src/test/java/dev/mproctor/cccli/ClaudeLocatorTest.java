package dev.mproctor.cccli;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeLocatorTest {

    // ── Happy paths ───────────────────────────────────────────────────────────

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

    // ── Error / null paths ────────────────────────────────────────────────────

    @Test
    void locateReturnsNullWhenBinaryNotFound() {
        // Searching for a binary that does not exist should return null
        Path result = ClaudeLocator.locate("/bin/zsh", "-l", "-c", "which nonexistent_binary_xyz_abc_123");
        assertNull(result, "should return null when binary is not on PATH");
    }

    @Test
    void locateReturnsNullWhenShellExitsNonZero() {
        // Shell exits with code 1 and no output
        Path result = ClaudeLocator.locate("/bin/sh", "-c", "exit 1");
        assertNull(result, "should return null when shell exits non-zero");
    }

    @Test
    void locateReturnsNullWhenOutputIsEmpty() {
        // Shell succeeds (exit 0) but prints nothing
        Path result = ClaudeLocator.locate("/bin/sh", "-c", "true");
        assertNull(result, "should return null when output is empty even on exit 0");
    }

    @Test
    void locateReturnsNullWhenOutputIsNotExecutable() {
        // /etc/hosts exists but has mode -rw-r--r-- (not executable)
        Path result = ClaudeLocator.locate("/bin/sh", "-c", "echo /etc/hosts");
        assertNull(result, "/etc/hosts is not executable — should return null");
    }
}
