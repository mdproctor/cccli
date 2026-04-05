package dev.mproctor.cccli;

import dev.mproctor.cccli.bridge.MacUIBridge;
import dev.mproctor.cccli.pty.PtyProcess;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import java.nio.file.Path;

@QuarkusMain
public class Main implements QuarkusApplication {

    @Inject
    MacUIBridge bridge;

    public static void main(String... args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) {
        Path claudePath = ClaudeLocator.locate();
        if (claudePath == null) {
            Log.error("claude binary not found in PATH");
            /* Write error to output pane then exit — bridge isn't started yet,
             * so we can't use appendOutput. Exit with a message to stderr.      */
            System.err.println("""
                    claude not found. Install it with:
                      npm install -g @anthropic-ai/claude-code
                    Then relaunch the app.
                    """);
            return 1;
        }
        Log.infof("Found claude at: %s", claudePath);

        PtyProcess pty = new PtyProcess();
        pty.open();
        pty.spawn(new String[]{claudePath.toString()});

        // Strip ANSI escape codes before display — NSTextView has no terminal
        // emulation. Plan 5b switches to WKWebView + xterm.js which handles
        // ANSI natively; AnsiStripper is removed at that point.
        pty.startReader(text -> bridge.appendOutput(AnsiStripper.strip(text)));

        // Plan 5 wires resize to actual window dimensions.
        pty.resize(24, 120);

        Log.info("Starting Claude Desktop CLI...");
        bridge.start("Claude Desktop CLI", 900, 600,
                "Connecting to Claude...\n",
                () -> {
                    Log.info("Window closed — terminating");
                    pty.close();
                    bridge.terminate();
                },
                text -> {
                    Log.infof("Sending to claude: %s", text);
                    pty.write(text + "\n");
                },
                () -> {}); // TODO Task 4: replace with real stop handler

        Log.info("Application terminated");
        return 0;
    }
}
