/* mac-ui-bridge/src/MyMacUI.m */
#import <Cocoa/Cocoa.h>
#include <string.h>
#include "MyMacUI.h"

/* ── AppDelegate ─────────────────────────────────────────────────────────── */

@interface CCCAppDelegate : NSObject <NSApplicationDelegate, NSWindowDelegate>
@property (nonatomic, assign) WindowClosedCallback onClosed;
@end

@implementation CCCAppDelegate

- (void)applicationDidFinishLaunching:(NSNotification *)notification {
    /* Nothing needed — window is created via myui_create_window */
}

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return YES;
}

- (void)windowWillClose:(NSNotification *)notification {
    if (self.onClosed) {
        WindowClosedCallback cb = self.onClosed;
        self.onClosed = NULL; /* clear before invoking — prevents double-fire on [NSApp terminate:] */
        cb();
    }
}

@end

/* ── Module-level state ───────────────────────────────────────────────────── */

static CCCAppDelegate *appDelegate = nil;

/* ── C ABI implementation ─────────────────────────────────────────────────── */

void myui_init_application(void) {
    [NSApplication sharedApplication];
    appDelegate = [[CCCAppDelegate alloc] init];
    [NSApp setDelegate:appDelegate];
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
}

intptr_t myui_create_window(const char *title,
                             int width,
                             int height,
                             WindowClosedCallback onClosed) {
    NSRect frame = NSMakeRect(0, 0, width, height);
    NSWindowStyleMask style = NSWindowStyleMaskTitled
                            | NSWindowStyleMaskClosable
                            | NSWindowStyleMaskMiniaturizable
                            | NSWindowStyleMaskResizable;

    NSWindow *window = [[NSWindow alloc] initWithContentRect:frame
                                                   styleMask:style
                                                     backing:NSBackingStoreBuffered
                                                       defer:NO];

    NSString *titleStr = [NSString stringWithUTF8String:(title ? title : "")];
    [window setTitle:titleStr];
    [window center];
    [window setDelegate:appDelegate];
    [window makeKeyAndOrderFront:nil];

    appDelegate.onClosed = onClosed;

    [NSApp activateIgnoringOtherApps:YES];

    return (intptr_t)(__bridge void *)window;
}

void myui_run(void) {
    [NSApp run];
}

void myui_terminate(void) {
    [NSApp terminate:nil];
}

intptr_t myui_start(const char *title,
                    int width,
                    int height,
                    WindowClosedCallback onClosed) {

    __block intptr_t windowHandle = 0;
    char *titleCopy = title ? strdup(title) : strdup("");

    if ([NSThread isMainThread]) {
        /*
         * Called from the OS main thread — GraalVM native image, where Quarkus
         * calls QuarkusApplication.run() synchronously on the main thread.
         *
         * We cannot dispatch_async + semaphore_wait here: the semaphore would
         * block the main thread, preventing GCD from ever draining the main queue.
         *
         * Instead: queue the AppKit setup on the main queue, then start CFRunLoop.
         * CFRunLoop drains the main queue, running the block. [NSApp run] takes
         * over as the event loop. When [NSApp terminate] fires, [NSApp run] returns
         * and we stop the outer CFRunLoop.
         */
        dispatch_async(dispatch_get_main_queue(), ^{
            myui_init_application();
            windowHandle = myui_create_window(titleCopy, width, height, onClosed);
            free(titleCopy);
            [NSApp run]; /* blocks until terminate — AppKit event loop */
            CFRunLoopStop(CFRunLoopGetCurrent());
        });
        CFRunLoopRun(); /* processes main queue; returns after CFRunLoopStop */
    } else {
        /*
         * Called from a non-main thread — JVM mode, where Quarkus uses a worker
         * thread for QuarkusApplication.run(). The OS main thread is free to
         * drain the GCD main queue while this worker thread waits on the semaphore.
         */
        dispatch_semaphore_t done = dispatch_semaphore_create(0);

        dispatch_async(dispatch_get_main_queue(), ^{
            myui_init_application();
            windowHandle = myui_create_window(titleCopy, width, height, onClosed);
            free(titleCopy);
            [NSApp run];
            dispatch_semaphore_signal(done);
        });

        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    }

    return windowHandle;
}
