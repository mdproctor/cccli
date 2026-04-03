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
 * Full entry point. Dispatches to the main thread, creates the window with a
 * split pane (WKWebView terminal top, NSTextView input bottom), loads initialHtml
 * into the terminal pane, then starts the AppKit event loop.
 *
 * Blocks the calling thread until the application terminates.
 * Safe to call from any thread.
 *
 * initialHtml     - HTML string loaded into the terminal WKWebView at startup.
 * onClosed        - called when the user closes the window.
 * onTextSubmitted - called when the user presses Enter in the input pane.
 */
intptr_t myui_start(const char* title,
                    int width,
                    int height,
                    const char* initialHtml,
                    WindowClosedCallback onClosed,
                    TextSubmittedCallback onTextSubmitted);

/**
 * Load HTML into the terminal WKWebView. Dispatches to the main thread.
 * Safe to call from any thread after myui_start() has set up the window.
 */
void myui_load_html(const char* html);

/**
 * Evaluate a JavaScript string in the terminal WKWebView. Dispatches to
 * the main thread. Safe to call from any thread, including upcall handlers.
 */
void myui_evaluate_javascript(const char* script);

#ifdef __cplusplus
}
#endif

#endif /* MyMacUI_h */
