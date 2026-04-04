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
        String initialHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <style>
                  * { box-sizing: border-box; margin: 0; padding: 0; }
                  body {
                    background: #1e1e1e;
                    color: #d4d4d4;
                    font-family: 'SF Mono', Menlo, 'Courier New', monospace;
                    font-size: 13px;
                    padding: 8px;
                    white-space: pre-wrap;
                    word-break: break-all;
                  }
                </style>
                </head>
                <body id="out">Claude Desktop CLI — ready\n</body>
                <script>
                function write(text) {
                  document.getElementById('out').textContent += text;
                }
                </script>
                </html>
                """;

        Log.info("Starting Claude Desktop CLI...");
        bridge.start("Claude Desktop CLI", 900, 600, initialHtml,
                () -> {
                    Log.info("Window closed via upcall — terminating");
                    bridge.terminate();
                },
                text -> {
                    Log.infof("Input received: %s", text);
                    String escaped = text
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\n", "\\n");
                    bridge.evaluateJavaScript("write('> " + escaped + "\\n')");
                });

        Log.info("Application terminated");
        return 0;
    }
}
