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

        // Detector: bridge.setPassiveMode() is called from the detector's
        // scheduler thread — safe because myui_set_passive_mode() dispatches
        // to the AppKit main thread via performSelectorOnMainThread:.
        InteractionDetector detector = new InteractionDetector(
                state -> bridge.setPassiveMode(state == ClaudeState.PASSIVE));

        pty.startReader(text -> {
            detector.onOutput();
            // Raw bytes — no ANSI stripping. In WKWebView mode (bundle), xterm.js handles
            // ANSI natively. In NSTextView dev mode, escape codes appear as literal chars
            // which is acceptable (dev mode is transient, WKWebView is production).
            bridge.appendOutput(text);
        });

        // Plan 5 wires resize to actual window dimensions.
        pty.resize(24, 120);

        Log.info("Starting Claude Desktop CLI...");
        bridge.start("Claude Desktop CLI", 900, 600,
                "Connecting to Claude...\n",
                () -> {
                    Log.info("Window closed — terminating");
                    detector.forceIdle();
                    detector.close();
                    pty.close();
                    bridge.terminate();
                },
                text -> {
                    if (detector.getState() == ClaudeState.FREE_TEXT) {
                        Log.infof("Sending to claude: %s", text);
                        detector.onSubmit();
                        pty.write(text + "\n");
                    }
                    // In PASSIVE: input field is disabled so this shouldn't fire;
                    // the state check is belt-and-suspenders.
                },
                () -> {
                    Log.info("Stop clicked — sending SIGINT");
                    pty.sendSigInt();
                    detector.forceIdle();
                });

        Log.info("Application terminated");
        return 0;
    }
}
