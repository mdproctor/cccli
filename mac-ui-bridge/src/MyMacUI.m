/* mac-ui-bridge/src/MyMacUI.m */
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#include <string.h>
#include "MyMacUI.h"

/* ── AppDelegate ─────────────────────────────────────────────────────────── */

@interface CCCAppDelegate : NSObject <NSApplicationDelegate,
                                      NSWindowDelegate,
                                      NSTextViewDelegate>
@property (nonatomic, assign) WindowClosedCallback onClosed;
@property (nonatomic, assign) TextSubmittedCallback onTextSubmitted;
@end

@implementation CCCAppDelegate

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return YES;
}

- (void)windowWillClose:(NSNotification *)notification {
    if (self.onClosed) {
        WindowClosedCallback cb = self.onClosed;
        self.onClosed = NULL; /* clear before invoking — prevents double-fire */
        cb();
    }
}

/* NSTextViewDelegate — intercept Enter key in the input pane */
- (BOOL)textView:(NSTextView *)textView doCommandBySelector:(SEL)commandSelector {
    if (commandSelector == @selector(insertNewline:)) {
        NSString *raw = textView.string;
        NSString *text = [raw stringByTrimmingCharactersInSet:
                          NSCharacterSet.whitespaceCharacterSet];
        if (text.length > 0 && self.onTextSubmitted) {
            self.onTextSubmitted(text.UTF8String);
        }
        [textView setString:@""];
        return YES; /* handled — do not insert a newline */
    }
    return NO;
}

@end

/* ── Module-level state ───────────────────────────────────────────────────── */

static CCCAppDelegate *appDelegate = nil;
static WKWebView      *theWebView  = nil;

/* ── Internal helpers ─────────────────────────────────────────────────────── */

static void setupSplitPane(NSWindow *window,
                            const char *initialHtml,
                            TextSubmittedCallback onTextSubmitted) {
    CGFloat w = window.contentView.bounds.size.width;
    CGFloat h = window.contentView.bounds.size.height;

    /* ── Terminal pane (top): WKWebView ──────────────────────────────────── */
    WKWebViewConfiguration *wkConfig = [[WKWebViewConfiguration alloc] init];
    WKWebView *webView = [[WKWebView alloc]
        initWithFrame:NSMakeRect(0, 0, w, h * 0.72)
        configuration:wkConfig];
    webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    theWebView = webView;

    /* ── Input pane (bottom): NSScrollView + NSTextView ─────────────────── */
    NSScrollView *inputScroll = [[NSScrollView alloc]
        initWithFrame:NSMakeRect(0, 0, w, h * 0.28)];
    inputScroll.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    inputScroll.hasVerticalScroller = YES;
    inputScroll.hasHorizontalScroller = NO;
    inputScroll.drawsBackground = YES;
    inputScroll.backgroundColor = [NSColor colorWithRed:0.12 green:0.12
                                                   blue:0.12 alpha:1.0];

    NSTextView *inputText = [[NSTextView alloc]
        initWithFrame:inputScroll.bounds];
    inputText.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    inputText.richText = NO;
    inputText.font = [NSFont monospacedSystemFontOfSize:13
                                                 weight:NSFontWeightRegular];
    inputText.textColor = [NSColor colorWithRed:0.84 green:0.84
                                           blue:0.84 alpha:1.0];
    inputText.backgroundColor = [NSColor colorWithRed:0.12 green:0.12
                                                 blue:0.12 alpha:1.0];
    inputText.insertionPointColor = [NSColor colorWithRed:0.84 green:0.84
                                                     blue:0.84 alpha:1.0];
    inputText.automaticSpellingCorrectionEnabled = NO;
    inputText.automaticQuoteSubstitutionEnabled = NO;
    inputText.automaticDashSubstitutionEnabled = NO;
    inputText.delegate = appDelegate;
    inputScroll.documentView = inputText;

    /* ── Split view ──────────────────────────────────────────────────────── */
    NSSplitView *split = [[NSSplitView alloc]
        initWithFrame:window.contentView.bounds];
    split.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    split.vertical = NO; /* horizontal divider — top/bottom split */
    split.dividerStyle = NSSplitViewDividerStyleThin;

    [split addSubview:webView];       /* top */
    [split addSubview:inputScroll];   /* bottom */
    window.contentView = split;

    [split setPosition:h * 0.72 ofDividerAtIndex:0];

    /* ── Load initial HTML ───────────────────────────────────────────────── */
    if (initialHtml) {
        NSString *htmlStr = [NSString stringWithUTF8String:initialHtml];
        [theWebView loadHTMLString:htmlStr baseURL:nil];
    }

    appDelegate.onTextSubmitted = onTextSubmitted;

    /* Give keyboard focus to the input pane on startup */
    [window makeFirstResponder:inputText];
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
