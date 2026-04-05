/* mac-ui-bridge/src/MyMacUI.m */
#import <Cocoa/Cocoa.h>
#include <string.h>
#include "MyMacUI.h"

/* ── Forward declarations ────────────────────────────────────────────────── */

static void doAppend(NSString *str);

/* Forward-declare module globals so applyPassiveMode: can reference them
 * before the static definitions appear later in the translation unit.     */
static NSTextField *theInputField;
static NSButton    *theStopButton;

/* ── AppDelegate ─────────────────────────────────────────────────────────── */

@interface CCCAppDelegate : NSObject <NSApplicationDelegate, NSWindowDelegate>
@property (nonatomic, assign) WindowClosedCallback   onClosed;
@property (nonatomic, assign) TextSubmittedCallback  onTextSubmitted;
@property (nonatomic, assign) StopClickedCallback    onStop;
@property (nonatomic, weak)   NSTextField           *inputField;
- (void)appendToOutput:(NSString *)str;
- (void)applyPassiveMode:(NSNumber *)value;
@end

@implementation CCCAppDelegate

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return YES;
}

- (void)windowDidBecomeKey:(NSNotification *)notification {
    /* Apply the empty-field cursor-blink fix exactly once, at the moment the
     * window becomes key and the run loop is live. GCD dispatch blocks cannot
     * fire while [NSApp run] is itself executing inside a dispatch_async block,
     * so AppKit delegate methods are the only safe hook for post-run-loop work. */
    if (self.inputField) {
        NSWindow *w = notification.object;
        [w makeFirstResponder:self.inputField];
        [self.inputField setStringValue:@" "];
        [self.inputField setStringValue:@""];
        self.inputField = nil; /* clear so this only runs once */
    }
}

- (void)appendToOutput:(NSString *)str {
    doAppend(str);
}

- (void)applyPassiveMode:(NSNumber *)value {
    BOOL passive = value.boolValue;
    theInputField.enabled = !passive;
    theStopButton.hidden  = !passive;
    /* Restore keyboard focus to input when becoming interactive */
    if (!passive) {
        NSWindow *w = theInputField.window;
        if (w) [w makeFirstResponder:theInputField];
    }
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

/* NSButton action — fired when user clicks the Stop button */
- (void)stopButtonClicked:(NSButton *)sender {
    if (self.onStop) self.onStop();
}

@end

/* ── Module-level state ───────────────────────────────────────────────────── */

static CCCAppDelegate *appDelegate    = nil;
static NSTextView     *theOutputView  = nil;
/* theInputField and theStopButton are forward-declared above. */

/* ── Internal helpers ─────────────────────────────────────────────────────── */

static void setupUI(NSWindow *window,
                    const char *initialText,
                    TextSubmittedCallback onTextSubmitted,
                    StopClickedCallback   onStop) {
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
    /* Explicit placeholder colour — system default is near-invisible on dark bg */
    inputField.placeholderAttributedString = [[NSAttributedString alloc]
        initWithString:@"Type a message and press Enter…"
            attributes:@{ NSForegroundColorAttributeName:
                [NSColor colorWithRed:0.50 green:0.50 blue:0.50 alpha:1.0] }];
    inputField.target = appDelegate;
    inputField.action = @selector(textFieldSubmit:);
    [root addSubview:inputField];
    theInputField = inputField;

    /* ── Stop button (overlaid right of input, hidden by default) ──────── */
    CGFloat btnW = 60.0;
    NSButton *stopBtn = [[NSButton alloc]
        initWithFrame:NSMakeRect(w - btnW - 8, 4, btnW, 28)];
    stopBtn.autoresizingMask = NSViewMinXMargin | NSViewMaxYMargin;
    stopBtn.title            = @"Stop";
    stopBtn.bezelStyle       = NSBezelStyleRounded;
    stopBtn.target           = appDelegate;
    stopBtn.action           = @selector(stopButtonClicked:);
    stopBtn.hidden           = YES;
    [root addSubview:stopBtn];
    theStopButton = stopBtn;

    /* Store references for delegate callbacks */
    appDelegate.inputField      = inputField;  /* cursor-blink fix, then cleared */
    appDelegate.onTextSubmitted = onTextSubmitted;
    appDelegate.onStop          = onStop;
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

void myui_set_passive_mode(int passive) {
    /* Cannot use dispatch_async — GCD main queue is serialised when [NSApp run]
     * executes inside a dispatch_async block (AppKit pitfall #1).
     * performSelectorOnMainThread: schedules on NSRunLoop instead.         */
    [appDelegate performSelectorOnMainThread:@selector(applyPassiveMode:)
                                 withObject:@((BOOL)passive)
                              waitUntilDone:NO];
}

static void doAppend(NSString *str) {
    if (!theOutputView) return;
    NSString *updated = [(theOutputView.string ?: @"") stringByAppendingString:str];
    [theOutputView setString:updated];
    [theOutputView scrollToEndOfDocument:nil];
}

void myui_append_output(const char *text) {
    if (!text) return;
    NSString *str = [NSString stringWithUTF8String:text];
    if ([NSThread isMainThread]) {
        doAppend(str);
    } else {
        /* performSelectorOnMainThread: instead of dispatch_async — see AppKit pitfall #1. */
        [appDelegate performSelectorOnMainThread:@selector(appendToOutput:)
                                     withObject:str
                                  waitUntilDone:NO];
    }
}

/* myui_load_html and myui_evaluate_javascript are retained in the ABI for
 * future WKWebView integration (Plan 5b, inside a proper .app bundle).    */
void myui_load_html(const char *html) { (void)html; }
void myui_evaluate_javascript(const char *script) { (void)script; }

intptr_t myui_start(const char *title,
                    int width,
                    int height,
                    const char *initialHtml,
                    WindowClosedCallback onClosed,
                    TextSubmittedCallback onTextSubmitted,
                    StopClickedCallback onStop) {

    __block intptr_t windowHandle = 0;
    char *titleCopy = strdup(title       ? title       : "");
    char *htmlCopy  = strdup(initialHtml ? initialHtml : "");

    if ([NSThread isMainThread]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            myui_init_application();
            windowHandle = myui_create_window(titleCopy, width, height, onClosed);
            free(titleCopy);
            NSWindow *window = (__bridge NSWindow *)(void *)windowHandle;
            setupUI(window, htmlCopy, onTextSubmitted, onStop);
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
            setupUI(window, htmlCopy, onTextSubmitted, onStop);
            free(htmlCopy);
            [NSApp run];
            dispatch_semaphore_signal(done);
        });
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    }

    return windowHandle;
}
