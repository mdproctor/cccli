/* mac-ui-bridge/include/MyMacUI.h */
#ifndef MyMacUI_h
#define MyMacUI_h

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Callback fired on the AppKit main thread when the window is closed.
 */
typedef void (*WindowClosedCallback)(void);

/**
 * Initialize NSApplication. Must be called first, on the main thread.
 */
void myui_init_application(void);

/**
 * Create and show a titled, resizable window centred on screen.
 * Returns an opaque window handle (cast of NSWindow pointer).
 * onClosed is called when the user closes the window.
 */
intptr_t myui_create_window(const char* title,
                             int width,
                             int height,
                             WindowClosedCallback onClosed);

/**
 * Start the AppKit event loop. Blocks until myui_terminate() is called.
 * Must be called on the main thread.
 */
void myui_run(void);

/**
 * Terminate the application cleanly.
 */
void myui_terminate(void);

/**
 * Convenience entry point: dispatches AppKit initialisation, window creation,
 * and the event loop to the main thread via GCD.
 * Blocks the calling thread until the application terminates.
 * Safe to call from any thread (including Quarkus worker threads).
 */
intptr_t myui_start(const char* title,
                    int width,
                    int height,
                    WindowClosedCallback onClosed);

#ifdef __cplusplus
}
#endif

#endif /* MyMacUI_h */
