package dev.mproctor.cccli.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Creates Panama upcall stubs via direct static MethodHandle lookup.
 *
 * We avoid jextract's WindowClosedCallback.allocate() here because it uses
 * MethodHandles.privateLookupIn() + findVirtual() on a generated interface,
 * which GraalVM native image cannot resolve at runtime. findStatic() on our
 * own registered class is reliable in native image.
 *
 * Each stub must be kept alive for as long as the native code may call it.
 * Pass the owning Arena to control the stub lifetime.
 */
public final class Callbacks {

    private static final FunctionDescriptor VOID_VOID = FunctionDescriptor.ofVoid();

    // Static holder — single window for Phase 1
    private static volatile Runnable windowClosedHandler;

    /**
     * Creates a native function pointer (C: void (*)(void)) that calls handler
     * when the window is closed by the user.
     *
     * @param arena   controls the lifetime of the stub; must outlive the window
     * @param handler called on the AppKit main thread when the window closes
     * @return a MemorySegment representing the native function pointer
     */
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

    /**
     * Called from Objective-C on the AppKit main thread when the window closes.
     * Must be public and static — registered in reflect-config.json for native image.
     */
    public static void onWindowClosed() {
        Runnable handler = windowClosedHandler;
        if (handler != null) {
            handler.run();
        }
    }

    private Callbacks() {}
}
