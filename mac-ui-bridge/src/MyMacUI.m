/* mac-ui-bridge/src/MyMacUI.m */
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#include <string.h>
#include "MyMacUI.h"

/* ── AppDelegate ─────────────────────────────────────────────────────────── */

@interface CCCAppDelegate : NSObject <NSApplicationDelegate, NSWindowDelegate>
@property (nonatomic, assign) WindowClosedCallback   onClosed;
@property (nonatomic, assign) TextSubmittedCallback  onTextSubmitted;
@end

@implementation CCCAppDelegate

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return YES;
}

- (void)windowDidBecomeKey:(NSNotification *)notification {
    NSLog(@"[DEBUG] window became KEY");
}

- (void)windowDidResignKey:(NSNotification *)notification {
    NSLog(@"[DEBUG] window lost KEY");
}

- (void)windowWillClose:(NSNotification *)notification {
    if (self.onClosed) {
        WindowClosedCallback cb = self.onClosed;
        self.onClosed = NULL;
        cb();
    }
}

/* NSTextField action — fired when user presses Enter */
- (void)textFieldSubmit:(NSTextField *)sender {
    NSString *text = [sender.stringValue
                      stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
    NSLog(@"[DEBUG] NSTextField submitted: '%@'", text);
    if (text.length > 0 && self.onTextSubmitted) {
        self.onTextSubmitted(text.UTF8String);
    }
    sender.stringValue = @"";
}

@end

/* ── Module-level state ───────────────────────────────────────────────────── */

static CCCAppDelegate *appDelegate = nil;
static WKWebView      *theWebView  = nil;

/* ── Internal helpers ─────────────────────────────────────────────────────── */

static void setupSplitPane(NSWindow *window,
                            const char *initialHtml,
                            TextSubmittedCallback onTextSubmitted) {
    /* NSSplitView blocks keyboard events to subviews when set as contentView.
     * Solution: plain NSView container with WKWebView + NSTextField as
     * direct subviews. No NSSplitView — no event blocking. */

    CGFloat w   = window.contentView.bounds.size.width;
    CGFloat h   = window.contentView.bounds.size.height;
    CGFloat inputH = 36.0;
    CGFloat webH   = h - inputH - 1; /* 1pt separator */

    /* ── Container ───────────────────────────────────────────────────────── */
    NSView *container = [[NSView alloc] initWithFrame:window.contentView.bounds];
    container.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    container.wantsLayer       = YES;
    container.layer.backgroundColor =
        [NSColor colorWithRed:0.12 green:0.12 blue:0.12 alpha:1.0].CGColor;

    /* ── Terminal pane (top): WKWebView ──────────────────────────────────── */
    WKWebViewConfiguration *wkConfig = [[WKWebViewConfiguration alloc] init];
    WKWebView *webView = [[WKWebView alloc]
        initWithFrame:NSMakeRect(0, inputH + 1, w, webH)
        configuration:wkConfig];
    webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    theWebView = webView;
    [container addSubview:webView];

    /* ── Input pane (bottom): NSTextField ───────────────────────────────── */
    NSTextField *inputField = [[NSTextField alloc]
        initWithFrame:NSMakeRect(0, 0, w, inputH)];
    inputField.autoresizingMask  = NSViewWidthSizable | NSViewMaxYMargin;
    inputField.font              = [NSFont monospacedSystemFontOfSize:13
                                                               weight:NSFontWeightRegular];
    inputField.textColor         = [NSColor colorWithRed:0.84 green:0.84
                                                    blue:0.84 alpha:1.0];
    inputField.backgroundColor   = [NSColor colorWithRed:0.12 green:0.12
                                                    blue:0.12 alpha:1.0];
    inputField.drawsBackground   = YES;
    inputField.bezeled           = NO;
    inputField.editable          = YES;
    inputField.selectable        = YES;
    inputField.placeholderString = @"Type a message and press Enter…";
    inputField.target            = appDelegate;
    inputField.action            = @selector(textFieldSubmit:);
    [container addSubview:inputField];

    window.contentView = container;

    [window recalculateKeyViewLoop];
    BOOL ok = [window makeFirstResponder:inputField];
    NSLog(@"[DEBUG] makeFirstResponder result=%@  isKeyWindow=%@",
          ok ? @"YES" : @"NO",
          window.isKeyWindow ? @"YES" : @"NO");

    /* ── Load initial HTML ───────────────────────────────────────────────── */
    if (initialHtml) {
        NSString *htmlStr = [NSString stringWithUTF8String:initialHtml];
        [theWebView loadHTMLString:htmlStr baseURL:nil];
    }

    appDelegate.onTextSubmitted = onTextSubmitted;
}

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

void myui_run(void) { [NSApp run]; }

void myui_terminate(void) { [NSApp terminate:nil]; }

void myui_load_html(const char *html) {
    if (!html) return;
    char *copy = strdup(html);
    dispatch_async(dispatch_get_main_queue(), ^{
        NSString *htmlStr = [NSString stringWithUTF8String:copy];
        [theWebView loadHTMLString:htmlStr baseURL:nil];
        free(copy);
    });
}

void myui_evaluate_javascript(const char *script) {
    if (!script) return;
    char *copy = strdup(script);
    dispatch_async(dispatch_get_main_queue(), ^{
        NSString *scriptStr = [NSString stringWithUTF8String:copy];
        [theWebView evaluateJavaScript:scriptStr completionHandler:nil];
        free(copy);
    });
}

intptr_t myui_start(const char *title,
                    int width,
                    int height,
                    const char *initialHtml,
                    WindowClosedCallback onClosed,
                    TextSubmittedCallback onTextSubmitted) {

    __block intptr_t windowHandle = 0;
    char *titleCopy = strdup(title       ? title       : "");
    char *htmlCopy  = strdup(initialHtml ? initialHtml : "");

    if ([NSThread isMainThread]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            myui_init_application();
            windowHandle = myui_create_window(titleCopy, width, height, onClosed);
            free(titleCopy);
            NSWindow *window = (__bridge NSWindow *)(void *)windowHandle;
            setupSplitPane(window, htmlCopy, onTextSubmitted);
            free(htmlCopy);
            [NSApp run];
            CFRunLoopStop(CFRunLoopGetCurrent());
        });
        CFRunLoopRun();
    } else {
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        dispatch_async(dispatch_get_main_queue(), ^{
            myui_init_application();
            windowHandle = myui_create_window(titleCopy, width, height, onClosed);
            free(titleCopy);
            NSWindow *window = (__bridge NSWindow *)(void *)windowHandle;
            setupSplitPane(window, htmlCopy, onTextSubmitted);
            free(htmlCopy);
            [NSApp run];
            dispatch_semaphore_signal(done);
        });
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    }

    return windowHandle;
}
