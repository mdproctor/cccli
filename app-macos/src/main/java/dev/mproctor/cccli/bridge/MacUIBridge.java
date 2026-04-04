package dev.mproctor.cccli.bridge;

import dev.mproctor.cccli.bridge.gen.MyMacUI_h;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Thin Java facade over the Objective-C MyMacUI bridge.
 *
 * Loads libMyMacUI.dylib at startup. All AppKit threading is handled
 * internally by the dylib — callers need not worry about thread affinity.
 */
@ApplicationScoped
public class MacUIBridge {

    private static final String DYLIB_PATH_PROP    = "cccli.dylib.path";
    private static final String DYLIB_PATH_DEFAULT =
            "../mac-ui-bridge/build/libMyMacUI.dylib";

    private final Arena arena = Arena.ofShared();

    @PostConstruct
    void loadDylib() {
        String pathStr = System.getProperty(DYLIB_PATH_PROP, DYLIB_PATH_DEFAULT);
        Path path = Path.of(pathStr).toAbsolutePath();
        Log.infof("Loading dylib from: %s", path);
        System.load(path.toString());
        Log.info("libMyMacUI.dylib loaded successfully");
    }

    /**
     * Launch the application window with a split pane UI.
     * Blocks until the user closes the window or terminate() is called.
     *
     * @param title           window title bar text
     * @param width           initial window width in points
     * @param height          initial window height in points
     * @param initialHtml     HTML loaded into the terminal WKWebView at startup
     * @param onClosed        called when the user closes the window
     * @param onTextSubmitted called when the user presses Enter in the input pane
     */
    public long start(String title, int width, int height,
                      String initialHtml,
                      Runnable onClosed,
                      Consumer<String> onTextSubmitted) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment titleSeg    = temp.allocateFrom(title != null ? title : "");
            MemorySegment htmlSeg     = temp.allocateFrom(initialHtml != null
                                                          ? initialHtml : "");
            MemorySegment closedCb    = Callbacks.createWindowClosedCallback(arena, onClosed);
            MemorySegment submittedCb = Callbacks.createTextSubmittedCallback(arena,
                                                                               onTextSubmitted);
            return MyMacUI_h.myui_start(titleSeg, width, height,
                                        htmlSeg, closedCb, submittedCb);
        }
    }

    /**
     * Load HTML into the terminal pane. Dispatches to the main thread internally.
     * Safe to call from any thread.
     */
    public void loadHtml(String html) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment htmlSeg = temp.allocateFrom(html != null ? html : "");
            MyMacUI_h.myui_load_html(htmlSeg);
        }
    }

    /**
     * Evaluate JavaScript in the terminal pane. Dispatches to the main thread.
     * Safe to call from any thread, including upcall handlers.
     */
    public void evaluateJavaScript(String script) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment scriptSeg = temp.allocateFrom(script != null ? script : "");
            MyMacUI_h.myui_evaluate_javascript(scriptSeg);
        }
    }

    /** Terminate the application cleanly. */
    public void terminate() {
        MyMacUI_h.myui_terminate();
    }

    @PreDestroy
    void close() {
        arena.close();
    }
}
