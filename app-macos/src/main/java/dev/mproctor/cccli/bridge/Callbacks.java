package dev.mproctor.cccli.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;

/**
 * Creates Panama upcall stubs via direct static MethodHandle lookup.
 *
 * Uses findStatic() on our own class — reliable in GraalVM native image.
 * Do NOT use jextract's WindowClosedCallback.allocate() or
 * TextSubmittedCallback.allocate() — they use privateLookupIn() which
 * fails in native image.
 *
 * All handler methods must be registered in reachability-metadata.json.
 */
public final class Callbacks {

    private static final FunctionDescriptor VOID_VOID =
            FunctionDescriptor.ofVoid();
    private static final FunctionDescriptor VOID_PTR =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

    private static volatile Runnable         windowClosedHandler;
    private static volatile Consumer<String> textSubmittedHandler;

    /** Creates a void(*)(void) upcall stub that calls handler when the window closes. */
    public static MemorySegment createWindowClosedCallback(Arena arena, Runnable handler) {
        windowClosedHandler = handler;
        try {
            MethodHandle mh = MethodHandles.lookup()
                    .findStatic(Callbacks.class, "onWindowClosed",
                            MethodType.methodType(void.class));
            return Linker.nativeLinker().upcallStub(mh, VOID_VOID, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to create window-closed upcall stub", e);
        }
    }

    /** Creates a void(*)(const char*) upcall stub that calls handler with submitted text. */
    public static MemorySegment createTextSubmittedCallback(Arena arena,
                                                             Consumer<String> handler) {
        textSubmittedHandler = handler;
        try {
            MethodHandle mh = MethodHandles.lookup()
                    .findStatic(Callbacks.class, "onTextSubmitted",
                            MethodType.methodType(void.class, MemorySegment.class));
            return Linker.nativeLinker().upcallStub(mh, VOID_PTR, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to create text-submitted upcall stub", e);
        }
    }

    /** Called from Objective-C when the window closes. Registered in reflect-config.json. */
    public static void onWindowClosed() {
        Runnable handler = windowClosedHandler;
        if (handler != null) handler.run();
    }

    /**
     * Called from Objective-C when the user presses Enter in the input pane.
     * textPtr points to a null-terminated UTF-8 C string.
     * Registered in reachability-metadata.json for native image.
     */
    public static void onTextSubmitted(MemorySegment textPtr) {
        Consumer<String> handler = textSubmittedHandler;
        if (handler != null && textPtr != null
                && !MemorySegment.NULL.equals(textPtr)) {
            String text = textPtr.reinterpret(Long.MAX_VALUE).getString(0);
            handler.accept(text);
        }
    }

    private Callbacks() {}
}
