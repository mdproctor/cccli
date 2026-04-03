package dev.mproctor.cccli.bridge;

import dev.mproctor.cccli.bridge.gen.WindowClosedCallback;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Creates Panama upcall stubs using jextract-generated helpers.
 *
 * Each stub must be kept alive for as long as the native code may call it.
 * Pass the owning Arena to control the stub lifetime.
 */
public final class Callbacks {

    /**
     * Creates a native function pointer (C: void (*)(void)) that calls handler
     * when the window is closed by the user.
     *
     * @param arena   controls the lifetime of the returned stub; must outlive the window
     * @param handler called on the AppKit main thread when the window closes
     * @return a MemorySegment representing the native function pointer
     */
    public static MemorySegment createWindowClosedCallback(Arena arena, Runnable handler) {
        return WindowClosedCallback.allocate(handler::run, arena);
    }

    private Callbacks() {}
}
