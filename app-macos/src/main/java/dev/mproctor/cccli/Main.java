package dev.mproctor.cccli;

import dev.mproctor.cccli.bridge.MacUIBridge;
import dev.mproctor.cccli.pty.PtyProcess;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

@QuarkusMain
public class Main implements QuarkusApplication {

    @Inject
    MacUIBridge bridge;

    public static void main(String... args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) {
        PtyProcess pty = new PtyProcess();

        // Open PTY and spawn test subprocess.
        // Plan 4 replaces /bin/cat with the resolved `claude` binary.
        pty.open();
        pty.spawn(new String[]{"/bin/cat"});

        // Reader runs on a daemon thread. bridge.appendOutput() is thread-safe:
        // myui_append_output() dispatches to the AppKit main thread via dispatch_async.
        pty.startReader(text -> bridge.appendOutput(text));

        // Set an initial terminal size (rows, cols).
        // Plan 5 wires this to the actual window size.
        pty.resize(24, 120);

        Log.info("Starting Claude Desktop CLI...");
        bridge.start("Claude Desktop CLI", 900, 600,
                "Claude Desktop CLI — PTY ready. Type and press Enter.\n",
                () -> {
                    Log.info("Window closed — terminating");
                    pty.close();
                    bridge.terminate();
                },
                text -> {
                    Log.infof("Sending to PTY: %s", text);
                    pty.write(text + "\n");
                });

        Log.info("Application terminated");
        return 0;
    }
}
