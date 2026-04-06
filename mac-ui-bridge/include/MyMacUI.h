/* mac-ui-bridge/include/MyMacUI.h */
#ifndef MyMacUI_h
#define MyMacUI_h

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Fired on the AppKit main thread when the window is closed. */
typedef void (*WindowClosedCallback)(void);

/** Fired on the AppKit main thread when the user presses Enter in the input pane.
 *  text is a null-terminated UTF-8 string; valid only for the duration of the call. */
typedef void (*TextSubmittedCallback)(const char* text);

/** Fired on the AppKit main thread when the user clicks the Stop button. */
typedef void (*StopClickedCallback)(void);

/** Initialize NSApplication. Must be called first, on the main thread. */
void myui_init_application(void);

/** Create and show a titled, resizable window. Returns an opaque window handle. */
intptr_t myui_create_window(const char* title,
                             int width,
                             int height,
                             WindowClosedCallback onClosed);

/** Start the AppKit event loop. Blocks until myui_terminate() is called. */
void myui_run(void);

/** Terminate the application cleanly. */
void myui_terminate(void);

/**
 * Enter or exit passive mode:
 *   passive=1 → disable input field, show Stop button
 *   passive=0 → enable input field, hide Stop button, restore focus
 * Thread-safe — dispatches to AppKit main thread internally.
 */
void myui_set_passive_mode(int passive);

/**
 * Full entry point. Creates the window with a split pane, loads initial text,
 * starts the AppKit event loop, and blocks until the application terminates.
 *
 * onStop is fired when the user clicks the Stop button (PASSIVE mode only).
 * Safe to call from any thread.
 */
intptr_t myui_start(const char* title,
                    int width,
                    int height,
                    const char* initialHtml,
                    WindowClosedCallback onClosed,
                    TextSubmittedCallback onTextSubmitted,
                    StopClickedCallback onStop);

/** Append plain text to the output pane. Thread-safe. */
void myui_append_output(const char* text);

/** Retained for ABI compatibility — no-op in current implementation. */
void myui_load_html(const char* html);

/** Retained for ABI compatibility — no-op in current implementation. */
void myui_evaluate_javascript(const char* script);

/**
 * Returns 1 if running inside a proper .app bundle with xterm resources present,
 * 0 otherwise (JVM dev mode). Used by Java to decide whether to strip ANSI sequences.
 */
int myui_is_bundle(void);

#ifdef __cplusplus
}
#endif

#endif /* MyMacUI_h */
