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
        return locate("/bin/zsh", "-l", "-c", "which claude");
    }

    /** Package-private for testing — allows injecting a custom shell command. */
    static Path locate(String... command) {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes()).strip();
            int exit = p.waitFor();
            if (exit != 0 || output.isEmpty()) return null;
            Path path = Path.of(output).toAbsolutePath();
            return Files.isExecutable(path) ? path : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private ClaudeLocator() {}
}
