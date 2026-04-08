package dev.mproctor.cccli;

import dev.mproctor.cccli.InputRouter;
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
        // Do NOT spawn yet — claude must start only after xterm.js is ready,
        // otherwise its entire startup output is written before the WKWebView
        // page has loaded and every byte is silently dropped.

        InteractionDetector detector = new InteractionDetector(
                state -> bridge.setPassiveMode(state == ClaudeState.PASSIVE));

        InputRouter inputRouter = new InputRouter(
                pty::write,
                bridge::setSlashMode,
                bridge::setInputText);

        // Spawn claude immediately. In WKWebView mode the Obj-C bridge buffers output
        // in pendingOutput until didFinishNavigation fires, so nothing is lost.
        // In NSTextView mode output goes straight through.
        pty.spawn(new String[]{claudePath.toString()});
        pty.startReader(text -> {
            detector.onOutput();
            bridge.appendOutput(text);
        });

        // Resize callback: FitAddon fires this once xterm.js is ready (WKWebView mode).
        // In NSTextView mode this never fires — PTY keeps its default size.
        bridge.setResizeCallback((cols, rows) -> {
            Log.debugf("Terminal resized: %d×%d", cols, rows);
            pty.resize(rows, cols);
        });

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
                },
                inputRouter::onTextChanged,   // NEW — fires on each NSTextField change
                inputRouter::onKeyPressed);   // NEW — fires on each key in slash mode

        Log.info("Application terminated");
        return 0;
    }
}
