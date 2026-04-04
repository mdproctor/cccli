package dev.mproctor.cccli;

import dev.mproctor.cccli.bridge.MacUIBridge;
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
        /* In development (JVM mode), this string is displayed as initial plain text
         * in the NSTextView output pane. In production, it will be HTML for xterm.js. */
        String initialHtml = "Claude Desktop CLI — ready\n";

        Log.info("Starting Claude Desktop CLI...");
        bridge.start("Claude Desktop CLI", 900, 600, initialHtml,
                () -> {
                    Log.info("Window closed via upcall — terminating");
                    bridge.terminate();
                },
                text -> {
                    Log.infof("Input received: %s", text);
                    bridge.appendOutput("> " + text + "\n");
                });

        Log.info("Application terminated");
        return 0;
    }
}
