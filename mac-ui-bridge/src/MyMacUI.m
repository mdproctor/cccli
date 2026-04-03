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
        self.onClosed();
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
    dispatch_semaphore_t done = dispatch_semaphore_create(0);

    /* Copy title to heap — the C string may be freed by the caller
       before the block executes on the main thread. */
    char *titleCopy = title ? strdup(title) : strdup("");

    dispatch_async(dispatch_get_main_queue(), ^{
        myui_init_application();
        windowHandle = myui_create_window(titleCopy, width, height, onClosed);
        free(titleCopy);
        [NSApp run];
        dispatch_semaphore_signal(done);
    });

    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    return windowHandle;
}
