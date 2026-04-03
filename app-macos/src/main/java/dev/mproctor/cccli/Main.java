package dev.mproctor.cccli;

import dev.mproctor.cccli.bridge.MacUIBridge;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/**
 * Application entry point.
 *
 * run() is called on the main thread in both JVM and native image mode.
 * AppKit requires the main thread for all UI operations, including [NSApp run].
 *
 * RISK: if Quarkus calls run() off the main thread, AppKit will crash with
 *       a pthread_main_np() assertion. This is validated in Task 9.
 */
@QuarkusMain
public class Main implements QuarkusApplication {

    @Inject
    MacUIBridge bridge;

    public static void main(String... args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) {
        Log.info("Starting Claude Desktop CLI...");
        bridge.start("Claude Desktop CLI", 900, 600, () -> {
            Log.info("Window closed via upcall — terminating");
            bridge.terminate();
        });
        Log.info("Application terminated");
        return 0;
    }
}
