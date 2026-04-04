/* mac-ui-bridge/src/MyMacUI.m */
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#include <string.h>
#include "MyMacUI.h"

/* ── AppDelegate ─────────────────────────────────────────────────────────── */

@interface CCCAppDelegate : NSObject <NSApplicationDelegate, NSWindowDelegate,
                                      WKNavigationDelegate>
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

/* WKNavigationDelegate — fires when page finishes loading */
- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    NSLog(@"[DEBUG] WKWebView page load finished — running test JS");
    [webView evaluateJavaScript:@"document.getElementById('out') ? 'element found' : 'element NOT found'"
              completionHandler:^(id result, NSError *error) {
        NSLog(@"[DEBUG] test JS result: %@  error: %@", result, error.localizedDescription);
    }];
}

- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    NSLog(@"[DEBUG] WKWebView navigation FAILED: %@", error.localizedDescription);
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
    /* KEY LESSON: replacing window.contentView breaks keyboard event routing.
     * The minimal test proved that adding to the EXISTING contentView works.
     * Solution: keep the default contentView, add all views to it. */

    NSView  *root    = window.contentView;
    CGFloat  w       = root.bounds.size.width;
    CGFloat  h       = root.bounds.size.height;
    CGFloat  inputH  = 36.0;
    CGFloat  webH    = h - inputH - 1;

    root.wantsLayer = YES;
    root.layer.backgroundColor =
        [NSColor colorWithRed:0.12 green:0.12 blue:0.12 alpha:1.0].CGColor;

    /* ── Terminal pane (top): WKWebView ──────────────────────────────────── */
    WKWebViewConfiguration *wkConfig = [[WKWebViewConfiguration alloc] init];
    WKWebView *webView = [[WKWebView alloc]
        initWithFrame:NSMakeRect(0, inputH + 1, w, webH)
        configuration:wkConfig];
    webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    theWebView = webView;
    [root addSubview:webView];

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
    [root addSubview:inputField];

    [window makeFirstResponder:inputField];

    /* ── Load initial HTML ───────────────────────────────────────────────── */
    NSLog(@"[DEBUG] webView frame: %@  hidden: %d  theWebView: %@",
          NSStringFromRect(webView.frame), webView.hidden, theWebView);
    if (initialHtml) {
        NSString *htmlStr = [NSString stringWithUTF8String:initialHtml];
        NSLog(@"[DEBUG] calling loadHTMLString, length=%lu", (unsigned long)htmlStr.length);
        webView.navigationDelegate = appDelegate;
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
    NSLog(@"[DEBUG] myui_evaluate_javascript called: %s  theWebView=%@", script, theWebView);
    char *copy = strdup(script);
    dispatch_async(dispatch_get_main_queue(), ^{
        NSString *scriptStr = [NSString stringWithUTF8String:copy];
        [theWebView evaluateJavaScript:scriptStr completionHandler:^(id result, NSError *error) {
            if (error) NSLog(@"[DEBUG] JS error: %@", error.localizedDescription);
            else NSLog(@"[DEBUG] JS result: %@", result);
        }];
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
