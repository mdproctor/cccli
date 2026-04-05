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
