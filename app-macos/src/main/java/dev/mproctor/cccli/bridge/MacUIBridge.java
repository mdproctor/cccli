package dev.mproctor.cccli.bridge;

import dev.mproctor.cccli.bridge.gen.MyMacUI_h;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * Thin Java facade over the Objective-C MyMacUI bridge.
 *
 * Loads libMyMacUI.dylib at startup via System.load(). The dylib path is
 * resolved from system property "cccli.dylib.path".
 *
 * All methods that call AppKit must be invoked on the main thread.
 */
@ApplicationScoped
public class MacUIBridge {

    private static final String DYLIB_PATH_PROP = "cccli.dylib.path";
    private static final String DYLIB_PATH_DEFAULT = "../mac-ui-bridge/build/libMyMacUI.dylib";

    // Arena lives for the application lifetime — keeps upcall stubs alive
    private final Arena arena = Arena.ofAuto();

    @PostConstruct
    void loadDylib() {
        String pathStr = System.getProperty(DYLIB_PATH_PROP, DYLIB_PATH_DEFAULT);
        Path path = Path.of(pathStr).toAbsolutePath();
        Log.infof("Loading dylib from: %s", path);
        System.load(path.toString());
        Log.info("libMyMacUI.dylib loaded successfully");
    }

    /**
     * Initialize NSApplication. Call once before any other method, on the main thread.
     */
    public void initApplication() {
        MyMacUI_h.myui_init_application();
    }

    /**
     * Create and show a window. Returns an opaque window handle.
     *
     * @param title    window title bar text
     * @param width    initial width in points
     * @param height   initial height in points
     * @param onClosed called when the user closes the window (on AppKit main thread)
     * @return opaque window handle
     */
    public long createWindow(String title, int width, int height, Runnable onClosed) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment titleSeg = temp.allocateFrom(title);
            MemorySegment callback = Callbacks.createWindowClosedCallback(arena, onClosed);
            return MyMacUI_h.myui_create_window(titleSeg, width, height, callback);
        }
    }

    /**
     * Start the AppKit event loop. Blocks until terminate() is called.
     * Must be called on the main thread.
     */
    public void run() {
        MyMacUI_h.myui_run();
    }

    /**
     * Terminate the application cleanly.
     */
    public void terminate() {
        MyMacUI_h.myui_terminate();
    }

    @PreDestroy
    void close() {
        arena.close();
    }
}
