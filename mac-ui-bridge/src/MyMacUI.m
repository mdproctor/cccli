/* mac-ui-bridge/src/MyMacUI.m */
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#include <string.h>
#include "MyMacUI.h"

/* ── Forward declarations ────────────────────────────────────────────────── */

static void doAppend(NSString *str);
static void doWebViewWrite(NSString *str);

static NSTextField    *theInputField;
static NSButton       *theStopButton;

/* Forward-declare the class so module-level statics can reference it before
 * the full @interface definition appears below.                              */
@class CCCAppDelegate;

/* ── Module-level state ───────────────────────────────────────────────────── */
/* Declared before @implementation so evaluateJS: and webView:didFinishNavigation:
 * can reference them without forward-declaring each variable separately.     */

static CCCAppDelegate        *appDelegate        = nil;
static NSTextView            *theOutputView      = nil;  /* dev mode (JVM, NSTextView) */
static WKWebView             *theWebView         = nil;  /* prod mode (.app bundle)    */
static BOOL                   pageReady          = NO;
static NSMutableArray        *pendingOutput      = nil;  /* buffered before page ready */
static NSString              *pendingInitialText = nil;  /* initial text for xterm.js  */
static WindowResizedCallback  resizedCallback    = NULL; /* registered via myui_set_resize_callback */
static TextChangedCallback    textChangedCallback  = NULL;
static KeyPressedCallback     keyPressedCallback   = NULL;
static BOOL                   slashModeActive      = NO;

/* ── AppDelegate ─────────────────────────────────────────────────────────── */

@interface CCCAppDelegate : NSObject
    <NSApplicationDelegate, NSWindowDelegate, WKNavigationDelegate,
     WKScriptMessageHandler, NSTextFieldDelegate>
@property (nonatomic, assign) WindowClosedCallback   onClosed;
@property (nonatomic, assign) TextSubmittedCallback  onTextSubmitted;
@property (nonatomic, assign) StopClickedCallback    onStop;
@property (nonatomic, weak)   NSTextField           *inputField;
- (void)appendToOutput:(NSString *)str;
- (void)applyPassiveMode:(NSNumber *)value;
- (void)evaluateJS:(NSString *)js;
- (void)applySlashMode:(NSNumber *)value;
- (void)applyInputText:(NSString *)text;
@end

@implementation CCCAppDelegate

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
    return YES;
}

- (void)windowDidBecomeKey:(NSNotification *)notification {
    /* Apply the empty-field cursor-blink fix exactly once, at the moment the
     * window becomes key and the run loop is live. (APPKIT_PITFALLS.md §4)  */
    if (self.inputField) {
        NSWindow *w = notification.object;
        [w makeFirstResponder:self.inputField];
        [self.inputField setStringValue:@" "];
        [self.inputField setStringValue:@""];
        self.inputField = nil;
    }
}

- (void)appendToOutput:(NSString *)str {
    doAppend(str);
}

- (void)applyPassiveMode:(NSNumber *)value {
    BOOL passive = value.boolValue;
    theInputField.enabled = !passive;
    theStopButton.hidden  = !passive;
    if (!passive) {
        NSWindow *w = theInputField.window;
        if (w) [w makeFirstResponder:theInputField];
    }
}

- (void)applySlashMode:(NSNumber *)value {
    slashModeActive = value.boolValue;
}

- (void)applyInputText:(NSString *)text {
    if (theInputField) {
        [theInputField setStringValue:text];
    }
}

/* NSTextFieldDelegate — fires on every keystroke that changes field content.
 * Already on AppKit main thread — update synchronously. (APPKIT_PITFALLS.md §5) */
- (void)controlTextDidChange:(NSNotification *)notification {
    NSTextField *field = notification.object;
    if (field == theInputField && textChangedCallback) {
        textChangedCallback(field.stringValue.UTF8String);
    }
}

- (void)evaluateJS:(NSString *)js {
    if (theWebView) {
        [theWebView evaluateJavaScript:js completionHandler:^(id result, NSError *error) {
            if (error) NSLog(@"[MyMacUI] JS error: %@", error);
        }];
    }
}

- (void)windowWillClose:(NSNotification *)notification {
    if (self.onClosed) {
        WindowClosedCallback cb = self.onClosed;
        self.onClosed = NULL;
        cb();
    }
}

- (void)windowDidResize:(NSNotification *)notification {
    /* Already on AppKit main thread. Guard: only when WKWebView is ready.
     * requestAnimationFrame defers fit() until after WebView reflows,
     * avoiding a stale offsetWidth/Height read. */
    if (pageReady && theWebView) {
        [theWebView evaluateJavaScript:@"requestAnimationFrame(()=>window.fitAddon.fit())"
                     completionHandler:nil];
    }
}

/* WKScriptMessageHandler — receives {cols, rows} from term.onResize in JS */
- (void)userContentController:(WKUserContentController *)userContentController
      didReceiveScriptMessage:(WKScriptMessage *)message {
    if ([message.name isEqualToString:@"termSize"] &&
            [message.body isKindOfClass:[NSDictionary class]]) {
        NSDictionary *body = (NSDictionary *)message.body;
        int cols = [body[@"cols"] intValue];
        int rows = [body[@"rows"] intValue];
        /* Guard against FitAddon reporting (0,0) during page teardown or zero-size container */
        if (cols > 0 && rows > 0) {
            WindowResizedCallback cb = resizedCallback;
            if (cb) cb(cols, rows);
        }
    }
}

- (void)textFieldSubmit:(NSTextField *)sender {
    NSString *text = [sender.stringValue
                      stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
    if (text.length > 0 && self.onTextSubmitted) {
        self.onTextSubmitted(text.UTF8String);
    }
    sender.stringValue = @"";
}

- (void)stopButtonClicked:(NSButton *)sender {
    if (self.onStop) self.onStop();
}

/* WKNavigationDelegate — fires when xterm.js page has fully loaded */
- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    pageReady = YES;
    /* Write the initial text first, then flush buffered PTY output */
    if (pendingInitialText) {
        doWebViewWrite(pendingInitialText);
        pendingInitialText = nil;
    }
    for (NSString *str in pendingOutput) {
        doWebViewWrite(str);
    }
    pendingOutput = nil;
    /* Initial fit — sets PTY size to match actual window via term.onResize → WindowResizedCallback */
    [theWebView evaluateJavaScript:@"window.fitAddon.fit()" completionHandler:nil];
}

@end

/* ── Internal helpers ─────────────────────────────────────────────────────── */

/* Writes str to xterm.js via base64 — must be called on the AppKit main thread */
static void doWebViewWrite(NSString *str) {
    NSData   *data = [str dataUsingEncoding:NSUTF8StringEncoding];
    NSString *b64  = [data base64EncodedStringWithOptions:0];
    /* Use Uint8Array so xterm.js receives raw bytes and handles UTF-8 itself */
    NSString *js   = [NSString stringWithFormat:
        @"window.term.write(Uint8Array.from(atob('%@'),function(c){return c.charCodeAt(0)}));",
        b64];
    [theWebView evaluateJavaScript:js completionHandler:nil];
}

static void doAppend(NSString *str) {
    if (theWebView) {
        if (!pageReady) {
            /* Buffer until didFinishNavigation fires */
            if (!pendingOutput) pendingOutput = [NSMutableArray array];
            [pendingOutput addObject:str];
        } else {
            doWebViewWrite(str);
        }
    } else if (theOutputView) {
        /* NSTextView dev path — str has already been ANSI-stripped by Java */
        NSString *updated = [(theOutputView.string ?: @"") stringByAppendingString:str];
        [theOutputView setString:updated];
        [theOutputView scrollToEndOfDocument:nil];
    }
}

static void setupUI(NSWindow *window,
                    const char *initialText,
                    TextSubmittedCallback onTextSubmitted,
                    StopClickedCallback   onStop) {
    /* KEY: add views to the EXISTING contentView — never replace it.
     * Replacing contentView breaks keyboard event routing. (APPKIT_PITFALLS.md §2) */

    NSView  *root   = window.contentView;
    CGFloat  w      = root.bounds.size.width;
    CGFloat  h      = root.bounds.size.height;
    CGFloat  inputH = 36.0;
    CGFloat  outH   = h - inputH - 1;

    root.wantsLayer = YES;
    root.layer.backgroundColor =
        [NSColor colorWithRed:0.12 green:0.12 blue:0.12 alpha:1.0].CGColor;

    /* ── Output pane (top): WKWebView or NSTextView ────────────────────────── */
    NSRect outputRect = NSMakeRect(0, inputH + 1, w, outH);

    if (myui_is_bundle()) {
        /* Production: WKWebView + xterm.js */
        WKUserContentController *controller = [[WKUserContentController alloc] init];
        [controller addScriptMessageHandler:appDelegate name:@"termSize"];

        WKWebViewConfiguration *config = [[WKWebViewConfiguration alloc] init];
        config.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];
        config.userContentController = controller;

        WKWebView *webView = [[WKWebView alloc] initWithFrame:outputRect
                                                configuration:config];
        webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        webView.navigationDelegate = appDelegate;
        [root addSubview:webView];
        theWebView = webView;

        /* Store initial text to write after page loads */
        if (initialText && strlen(initialText) > 0) {
            pendingInitialText = [NSString stringWithUTF8String:initialText];
        }

        /* Load xterm.js from the app bundle Resources/xterm/ */
        NSBundle *bundle   = [NSBundle mainBundle];
        NSURL    *indexURL = [bundle URLForResource:@"index"
                                      withExtension:@"html"
                                       subdirectory:@"xterm"];
        NSURL    *xtermDir = [indexURL URLByDeletingLastPathComponent];
        /* loadFileURL:allowingReadAccessToURL: is required for file:// in WKWebView */
        [webView loadFileURL:indexURL allowingReadAccessToURL:xtermDir];

    } else {
        /* Development: NSTextView (WKWebView fails without bundle — APPKIT_PITFALLS.md §3) */
        NSScrollView *outputScroll = [[NSScrollView alloc] initWithFrame:outputRect];
        outputScroll.autoresizingMask    = NSViewWidthSizable | NSViewHeightSizable;
        outputScroll.hasVerticalScroller = YES;
        outputScroll.drawsBackground     = YES;
        outputScroll.backgroundColor     = [NSColor colorWithRed:0.12 green:0.12
                                                            blue:0.12 alpha:1.0];

        NSTextView *outputText = [[NSTextView alloc]
            initWithFrame:NSMakeRect(0, 0, outputScroll.contentSize.width,
                                           outputScroll.contentSize.height)];
        outputText.minSize               = NSMakeSize(0, outputScroll.contentSize.height);
        outputText.maxSize               = NSMakeSize(FLT_MAX, FLT_MAX);
        outputText.verticallyResizable   = YES;
        outputText.horizontallyResizable = NO;
        outputText.autoresizingMask      = NSViewWidthSizable;
        outputText.textContainer.containerSize       =
            NSMakeSize(outputScroll.contentSize.width, FLT_MAX);
        outputText.textContainer.widthTracksTextView = YES;
        outputText.editable              = NO;
        outputText.selectable            = YES;
        outputText.richText              = NO;
        outputText.font                  = [NSFont monospacedSystemFontOfSize:13
                                                                       weight:NSFontWeightRegular];
        outputText.textColor             = [NSColor colorWithRed:0.84 green:0.84
                                                            blue:0.84 alpha:1.0];
        outputText.backgroundColor       = [NSColor colorWithRed:0.12 green:0.12
                                                            blue:0.12 alpha:1.0];
        outputText.automaticSpellingCorrectionEnabled = NO;
        theOutputView = outputText;
        outputScroll.documentView = outputText;
        [root addSubview:outputScroll];

        if (initialText) {
            [outputText setString:[NSString stringWithUTF8String:initialText]];
        }
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
    inputField.placeholderAttributedString = [[NSAttributedString alloc]
        initWithString:@"Type a message and press Enter…"
            attributes:@{ NSForegroundColorAttributeName:
                [NSColor colorWithRed:0.50 green:0.50 blue:0.50 alpha:1.0] }];
    inputField.target = appDelegate;
    inputField.action = @selector(textFieldSubmit:);
    [root addSubview:inputField];
    inputField.delegate = appDelegate;
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

    appDelegate.inputField      = inputField;
    appDelegate.onTextSubmitted = onTextSubmitted;
    appDelegate.onStop          = onStop;

    /* NSEvent local monitor — installed once at startup, never removed.
     * Active only when slashModeActive=YES. Converts AppKit function key codes
     * (arrows) to ANSI escape sequences before routing to KeyPressedCallback.
     * Returns nil to consume the event (NSTextField never sees it). */
    [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskKeyDown
                                          handler:^NSEvent*(NSEvent *event) {
        if (!slashModeActive) return event;

        NSString *chars = event.characters;
        if (!chars || chars.length == 0) return event;

        NSString *toSend = chars;
        if (chars.length == 1) {
            unichar ch = [chars characterAtIndex:0];
            switch (ch) {
                case NSUpArrowFunctionKey:    toSend = @"\x1b[A"; break;
                case NSDownArrowFunctionKey:  toSend = @"\x1b[B"; break;
                case NSRightArrowFunctionKey: toSend = @"\x1b[C"; break;
                case NSLeftArrowFunctionKey:  toSend = @"\x1b[D"; break;
                default: break;
            }
        }

        if (keyPressedCallback) {
            keyPressedCallback(toSend.UTF8String);
        }
        return nil;
    }];
}

/* ── C ABI implementation ─────────────────────────────────────────────────── */

int myui_is_bundle(void) {
    NSString *path = [[NSBundle mainBundle] pathForResource:@"index"
                                                     ofType:@"html"
                                                inDirectory:@"xterm"];
    return (path != nil) ? 1 : 0;
}

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
     * executes inside a dispatch_async block. (APPKIT_PITFALLS.md §1)         */
    [appDelegate performSelectorOnMainThread:@selector(applyPassiveMode:)
                                 withObject:@((BOOL)passive)
                              waitUntilDone:NO];
}

void myui_set_slash_mode(int active) {
    if ([NSThread isMainThread]) {
        slashModeActive = (BOOL)active;
    } else {
        [appDelegate performSelectorOnMainThread:@selector(applySlashMode:)
                                     withObject:@((BOOL)active)
                                  waitUntilDone:NO];
    }
}

void myui_set_input_text(const char *text) {
    NSString *str = text ? [NSString stringWithUTF8String:text] : @"";
    if ([NSThread isMainThread]) {
        if (theInputField) [theInputField setStringValue:str];
    } else {
        [appDelegate performSelectorOnMainThread:@selector(applyInputText:)
                                     withObject:str
                                  waitUntilDone:NO];
    }
}

void myui_append_output(const char *text) {
    if (!text) return;
    NSString *str = [NSString stringWithUTF8String:text];
    if ([NSThread isMainThread]) {
        doAppend(str);
    } else {
        /* performSelectorOnMainThread: — not dispatch_async. (APPKIT_PITFALLS.md §1) */
        [appDelegate performSelectorOnMainThread:@selector(appendToOutput:)
                                     withObject:str
                                  waitUntilDone:NO];
    }
}

void myui_load_html(const char *html) { (void)html; }

void myui_evaluate_javascript(const char *script) {
    if (!script) return;
    NSString *js = [NSString stringWithUTF8String:script];
    if ([NSThread isMainThread]) {
        [appDelegate evaluateJS:js];
    } else {
        [appDelegate performSelectorOnMainThread:@selector(evaluateJS:)
                                     withObject:js
                                  waitUntilDone:NO];
    }
}

void myui_set_resize_callback(WindowResizedCallback cb) {
    resizedCallback = cb;
}

intptr_t myui_start(const char *title,
                    int width,
                    int height,
                    const char *initialHtml,
                    WindowClosedCallback  onClosed,
                    TextSubmittedCallback onTextSubmitted,
                    StopClickedCallback   onStop,
                    TextChangedCallback   onTextChanged,
                    KeyPressedCallback    onKeyPressed) {
    textChangedCallback = onTextChanged;
    keyPressedCallback  = onKeyPressed;

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
