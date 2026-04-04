/* mac-ui-bridge/src/MyMacUI.m */
#import <Cocoa/Cocoa.h>
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
    if (text.length > 0 && self.onTextSubmitted) {
        self.onTextSubmitted(text.UTF8String);
    }
    sender.stringValue = @"";
}

@end

/* ── Module-level state ───────────────────────────────────────────────────── */

static CCCAppDelegate *appDelegate    = nil;
static NSTextView     *theOutputView  = nil;

/* ── Internal helpers ─────────────────────────────────────────────────────── */

static void setupUI(NSWindow *window,
                    const char *initialText,
                    TextSubmittedCallback onTextSubmitted) {
    /* KEY: add views to the EXISTING contentView — never replace it.
     * Replacing contentView breaks keyboard event routing.             */

    NSView  *root   = window.contentView;
    CGFloat  w      = root.bounds.size.width;
    CGFloat  h      = root.bounds.size.height;
    CGFloat  inputH = 36.0;
    CGFloat  outH   = h - inputH - 1;

    root.wantsLayer = YES;
    root.layer.backgroundColor =
        [NSColor colorWithRed:0.12 green:0.12 blue:0.12 alpha:1.0].CGColor;

    /* ── Output pane (top): NSScrollView + NSTextView ────────────────────── */
    NSScrollView *outputScroll = [[NSScrollView alloc]
        initWithFrame:NSMakeRect(0, inputH + 1, w, outH)];
    outputScroll.autoresizingMask    = NSViewWidthSizable | NSViewHeightSizable;
    outputScroll.hasVerticalScroller = YES;
    outputScroll.drawsBackground     = YES;
    outputScroll.backgroundColor     = [NSColor colorWithRed:0.12 green:0.12
                                                        blue:0.12 alpha:1.0];

    NSTextView *outputText = [[NSTextView alloc]
        initWithFrame:NSMakeRect(0, 0, outputScroll.contentSize.width,
                                       outputScroll.contentSize.height)];
    outputText.minSize            = NSMakeSize(0, outputScroll.contentSize.height);
    outputText.maxSize            = NSMakeSize(FLT_MAX, FLT_MAX);
    outputText.verticallyResizable   = YES;
    outputText.horizontallyResizable = NO;
    outputText.autoresizingMask   = NSViewWidthSizable;
    outputText.textContainer.containerSize    = NSMakeSize(outputScroll.contentSize.width, FLT_MAX);
    outputText.textContainer.widthTracksTextView = YES;
    outputText.editable           = NO;
    outputText.selectable         = YES;
    outputText.richText           = NO;
    outputText.font               = [NSFont monospacedSystemFontOfSize:13
                                                                weight:NSFontWeightRegular];
    outputText.textColor          = [NSColor colorWithRed:0.84 green:0.84
                                                     blue:0.84 alpha:1.0];
    outputText.backgroundColor    = [NSColor colorWithRed:0.12 green:0.12
                                                     blue:0.12 alpha:1.0];
    outputText.automaticSpellingCorrectionEnabled = NO;
    theOutputView = outputText;
    outputScroll.documentView = outputText;
    [root addSubview:outputScroll];

    if (initialText) {
        NSString *s = [NSString stringWithUTF8String:initialText];
        [outputText setString:s];
    }

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

void myui_append_output(const char *text) {
    if (!text) return;
    char *copy = strdup(text);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (theOutputView) {
            NSString *appended = [NSString stringWithUTF8String:copy];
            NSString *current  = theOutputView.string ?: @"";
            [theOutputView setString:[current stringByAppendingString:appended]];
            [theOutputView scrollToEndOfDocument:nil];
        }
        free(copy);
    });
}

/* myui_load_html and myui_evaluate_javascript are retained in the ABI for
 * future WKWebView integration (Plan 3+, inside a proper .app bundle).
 * They are no-ops in this NSTextView-based development implementation.    */
void myui_load_html(const char *html) { (void)html; }
void myui_evaluate_javascript(const char *script) { (void)script; }

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
            setupUI(window, htmlCopy, onTextSubmitted);
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
            setupUI(window, htmlCopy, onTextSubmitted);
            free(htmlCopy);
            [NSApp run];
            dispatch_semaphore_signal(done);
        });
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    }

    return windowHandle;
}
