# WKWebView + xterm.js Terminal Renderer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace NSTextView with WKWebView + xterm.js so Claude Code's full ANSI colour, cursor movement, and scrollback render correctly inside the `.app` bundle.

**Architecture:** The ObjC bridge detects at runtime whether xterm.js resources are present in the `.app` bundle and selects WKWebView or NSTextView accordingly — WKWebView is production only (APPKIT_PITFALLS.md §3: it silently fails in JVM mode). PTY bytes are base64-encoded and written to xterm.js via `evaluateJavaScript`. A page-ready buffer prevents lost output during xterm.js initialisation. Java detects bundle mode via a new `myui_is_bundle()` C function and skips ANSI stripping when in WKWebView mode.

**Tech Stack:** xterm.js 5.x (vendored, no npm at runtime), WKWebView, Panama FFM, Quarkus Native, macOS .app bundle

**GitHub epic:** #1

---

## Critical: Testing requires native builds

WKWebView does NOT work in JVM mode — only inside a proper `.app` bundle (APPKIT_PITFALLS.md §3). Every integration test in this plan requires:

```bash
mvn install -Pnative
open "app-macos/target/Claude Desktop CLI.app"
```

This takes ~3–4 minutes. Java unit tests (`mvn test`) are still fast and cover Java-side logic.

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `mac-ui-bridge/resources/xterm/index.html` | Create | xterm.js bootstrap page |
| `mac-ui-bridge/resources/xterm/xterm.js` | Create (vendored) | xterm.js 5.x minified |
| `mac-ui-bridge/resources/xterm/xterm.css` | Create (vendored) | xterm.js default styles |
| `mac-ui-bridge/include/MyMacUI.h` | Modify | Add `myui_is_bundle()` declaration |
| `mac-ui-bridge/src/MyMacUI.m` | Modify | WKWebView, page-ready buffer, `myui_is_bundle()`, `myui_evaluate_javascript()` |
| `app-macos/src/main/java/.../bridge/gen/MyMacUI_h.java` | Modify | Add `myui_is_bundle()` Panama binding |
| `app-macos/src/main/java/.../bridge/MacUIBridge.java` | Modify | Add `isInBundle()` and make `evaluateJavaScript()` real |
| `app-macos/src/main/java/.../Main.java` | Modify | Skip `AnsiStripper.strip()` in bundle mode |
| `app-macos/src/main/resources/entitlements.plist` | Create | WKWebView JIT entitlement (applied if needed) |
| `scripts/bundle.sh` | Modify | Copy `xterm/` resources + entitlements signing |
| `app-core/src/main/java/.../AnsiStripper.java` | Delete | No longer needed |
| `app-core/src/test/java/.../AnsiStripperTest.java` | Delete | No longer needed |

---

## Task 1: Vendor xterm.js and create the HTML bootstrap page

**Issues:** Refs #3
**Files:**
- Create: `mac-ui-bridge/resources/xterm/index.html`
- Create: `mac-ui-bridge/resources/xterm/xterm.js` (vendored)
- Create: `mac-ui-bridge/resources/xterm/xterm.css` (vendored)

- [ ] **Step 1: Download xterm.js 5.x via npm**

```bash
# From project root — no npm config needed, just a temp install
npm install --prefix /tmp/xterm-tmp xterm@5
mkdir -p mac-ui-bridge/resources/xterm
cp /tmp/xterm-tmp/node_modules/xterm/lib/xterm.js  mac-ui-bridge/resources/xterm/
cp /tmp/xterm-tmp/node_modules/xterm/css/xterm.css mac-ui-bridge/resources/xterm/
```

Verify:
```bash
ls -lh mac-ui-bridge/resources/xterm/
# Expected: xterm.js (~320 KB), xterm.css (~4 KB)
```

- [ ] **Step 2: Create `mac-ui-bridge/resources/xterm/index.html`**

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    html, body { width: 100%; height: 100%; background: #1e1e1e; overflow: hidden; }
    #terminal { width: 100%; height: 100%; }
  </style>
  <link rel="stylesheet" href="xterm.css">
</head>
<body>
  <div id="terminal"></div>
  <script src="xterm.js"></script>
  <script>
    window.term = new Terminal({
      theme: {
        background:    '#1e1e1e',
        foreground:    '#d4d4d4',
        cursor:        '#d4d4d4',
        black:         '#000000', red:         '#cd3131',
        green:         '#0dbc79', yellow:      '#e5e510',
        blue:          '#2472c8', magenta:     '#bc3fbc',
        cyan:          '#11a8cd', white:       '#e5e5e5',
        brightBlack:   '#666666', brightRed:   '#f14c4c',
        brightGreen:   '#23d18b', brightYellow:'#f5f543',
        brightBlue:    '#3b8eea', brightMagenta:'#d670d6',
        brightCyan:    '#29b8db', brightWhite: '#e5e5e5'
      },
      fontFamily: 'Menlo, Monaco, "Courier New", monospace',
      fontSize: 13,
      cursorBlink: true,
      scrollback: 5000
    });
    window.term.open(document.getElementById('terminal'));
    window.term.resize(120, 24);
  </script>
</body>
</html>
```

- [ ] **Step 3: Commit**

```bash
git add mac-ui-bridge/resources/
git commit -m "feat: vendor xterm.js 5.x and create terminal bootstrap page

Refs #3"
```

---

## Task 2: Update bundle.sh to copy xterm resources into the .app

**Issues:** Closes #3
**Files:**
- Modify: `scripts/bundle.sh`

- [ ] **Step 1: Add Resources directory copy to bundle.sh**

In `scripts/bundle.sh`, add `RESOURCES_DIR` variable and copy step. Add after the existing variable declarations (after line 13 `RESOURCES_DIR=...`):

```bash
RESOURCES_DIR="$CONTENTS/Resources"
XTERM_RESOURCES="$PROJECT_DIR/mac-ui-bridge/resources/xterm"
```

Add `"$RESOURCES_DIR"` to the `mkdir -p` call (currently line 41):

```bash
mkdir -p "$MACOS_DIR" "$FRAMEWORKS_DIR" "$RESOURCES_DIR"
```

After the `cp "$INFO_PLIST" "$CONTENTS/Info.plist"` line, add:

```bash
# Copy xterm.js terminal resources
if [ -d "$XTERM_RESOURCES" ]; then
    cp -r "$XTERM_RESOURCES" "$RESOURCES_DIR/xterm"
    echo "==> Copied xterm resources"
else
    echo "ERROR: xterm resources not found at: $XTERM_RESOURCES"
    exit 1
fi
```

- [ ] **Step 2: Verify the bundle structure is correct**

```bash
mvn package -pl app-macos -am -Pnative -q
bash scripts/bundle.sh
find "app-macos/target/Claude Desktop CLI.app/Contents/Resources" -type f
```

Expected output:
```
app-macos/target/Claude Desktop CLI.app/Contents/Resources/xterm/index.html
app-macos/target/Claude Desktop CLI.app/Contents/Resources/xterm/xterm.js
app-macos/target/Claude Desktop CLI.app/Contents/Resources/xterm/xterm.css
```

- [ ] **Step 3: Commit**

```bash
git add scripts/bundle.sh
git commit -m "feat: copy xterm.js resources into .app bundle under Contents/Resources/xterm

Closes #3"
```

---

## Task 3: Add WKWebView to the ObjC bridge with NSTextView fallback

**Issues:** Refs #2
**Files:**
- Modify: `mac-ui-bridge/include/MyMacUI.h`
- Modify: `mac-ui-bridge/src/MyMacUI.m`

### Background

APPKIT_PITFALLS.md §2: **never replace `window.contentView`** — add WKWebView as a subview of the existing contentView.
APPKIT_PITFALLS.md §3: WKWebView only works inside a proper `.app` bundle; `myui_is_bundle()` detects this at runtime by checking whether xterm/index.html exists in NSBundle.mainBundle.
APPKIT_PITFALLS.md §1: GCD `dispatch_async` never fires while `[NSApp run]` is inside a dispatch block — use `performSelectorOnMainThread:` for cross-thread WKWebView calls.

WKWebView loads xterm.js asynchronously. Output arriving before the page finishes loading must be buffered and flushed in `webView:didFinishNavigation:`.

- [ ] **Step 1: Add `myui_is_bundle()` to `mac-ui-bridge/include/MyMacUI.h`**

Add before the `#ifdef __cplusplus` closing block:

```c
/**
 * Returns 1 if running inside a proper .app bundle with xterm resources present,
 * 0 otherwise (JVM dev mode). Used by Java to decide whether to strip ANSI sequences.
 */
int myui_is_bundle(void);
```

- [ ] **Step 2: Rewrite `mac-ui-bridge/src/MyMacUI.m`**

Replace the entire file with the following. Key changes from the existing file:
- `#import <WebKit/WebKit.h>` added
- `theOutputView` and `theWebView` are both retained as static globals; only one is non-nil at runtime
- `pageReady`, `pendingOutput`, `pendingInitialText` manage the page-load buffer
- CCCAppDelegate now implements `WKNavigationDelegate`
- `doAppend()` routes to whichever view is active
- `myui_is_bundle()` and real `myui_evaluate_javascript()` implemented

```objc
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

/* ── AppDelegate ─────────────────────────────────────────────────────────── */

@interface CCCAppDelegate : NSObject
    <NSApplicationDelegate, NSWindowDelegate, WKNavigationDelegate>
@property (nonatomic, assign) WindowClosedCallback   onClosed;
@property (nonatomic, assign) TextSubmittedCallback  onTextSubmitted;
@property (nonatomic, assign) StopClickedCallback    onStop;
@property (nonatomic, weak)   NSTextField           *inputField;
- (void)appendToOutput:(NSString *)str;
- (void)applyPassiveMode:(NSNumber *)value;
- (void)evaluateJS:(NSString *)js;
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
}

@end

/* ── Module-level state ───────────────────────────────────────────────────── */

static CCCAppDelegate  *appDelegate       = nil;
static NSTextView      *theOutputView     = nil;  /* dev mode (JVM, NSTextView) */
static WKWebView       *theWebView        = nil;  /* prod mode (.app bundle)    */
static BOOL             pageReady         = NO;
static NSMutableArray  *pendingOutput     = nil;  /* buffered before page ready */
static NSString        *pendingInitialText = nil; /* initial text for xterm.js  */

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
        WKWebViewConfiguration *config = [[WKWebViewConfiguration alloc] init];
        config.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];

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
        NSBundle *bundle  = [NSBundle mainBundle];
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
        outputText.minSize            = NSMakeSize(0, outputScroll.contentSize.height);
        outputText.maxSize            = NSMakeSize(FLT_MAX, FLT_MAX);
        outputText.verticallyResizable   = YES;
        outputText.horizontallyResizable = NO;
        outputText.autoresizingMask   = NSViewWidthSizable;
        outputText.textContainer.containerSize    =
            NSMakeSize(outputScroll.contentSize.width, FLT_MAX);
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

static void doAppendFromString(NSString *str) {
    doAppend(str);
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
```

- [ ] **Step 3: Build the dylib**

```bash
cd mac-ui-bridge && make clean && make
```

Expected: `build/libMyMacUI.dylib` created with no errors.

- [ ] **Step 4: Commit**

```bash
git add mac-ui-bridge/include/MyMacUI.h mac-ui-bridge/src/MyMacUI.m
git commit -m "feat: add WKWebView output pane with NSTextView fallback for dev mode

Detects bundle mode via myui_is_bundle() at runtime. WKWebView buffers
output until xterm.js page loads via WKNavigationDelegate.

Refs #2"
```

---

## Task 4: Add myui_is_bundle() Panama binding and MacUIBridge.isInBundle()

**Issues:** Closes #2
**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java`
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java`
- Modify: `app-macos/src/test/java/dev/mproctor/cccli/bridge/MacUIBridgeTest.java`

- [ ] **Step 1: Write a failing test for isInBundle()**

In `app-macos/src/test/java/dev/mproctor/cccli/bridge/MacUIBridgeTest.java`, add:

```java
@Test
void isInBundle_returnsFalse_whenDylibNotInBundle() {
    // Running under JVM test mode — myui_is_bundle() returns 0
    // This test documents the expected behaviour without loading the real dylib
    // (MacUIBridgeTest already stubs loadDylib via the existing test infrastructure)
    MacUIBridge bridge = new MacUIBridge() {
        @Override
        String resolveDylibPath(String executablePath) {
            return System.getProperty("cccli.dylib.path",
                    "../mac-ui-bridge/build/libMyMacUI.dylib");
        }
    };
    // isInBundle() will be false in JVM test mode (no Resources/xterm/index.html)
    assertThat(bridge.isInBundle()).isFalse();
}
```

- [ ] **Step 2: Run test to confirm it fails (method does not exist yet)**

```bash
mvn test -pl app-macos -Dtest=MacUIBridgeTest#isInBundle_returnsFalse_whenDylibNotInBundle
```

Expected: compilation failure — `isInBundle()` method not found.

- [ ] **Step 3: Add `myui_is_bundle()` Panama binding to MyMacUI_h.java**

In `app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java`, add before the closing `}` of the class (after the last `myui_evaluate_javascript` block):

```java
    private static class myui_is_bundle {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
            MyMacUI_h.C_INT
        );

        public static final MemorySegment ADDR = MyMacUI_h.findOrThrow("myui_is_bundle");

        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    /**
     * {@snippet lang=c :
     * int myui_is_bundle(void)
     * }
     */
    public static int myui_is_bundle() {
        var mh$ = myui_is_bundle.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("myui_is_bundle");
            }
            return (int) mh$.invokeExact();
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
```

- [ ] **Step 4: Add `isInBundle()` to MacUIBridge.java**

Add after the `loadDylib()` method in `app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java`:

```java
/**
 * Returns true if running inside a proper .app bundle with xterm.js resources.
 * When true, WKWebView is active and ANSI sequences must NOT be stripped before
 * calling appendOutput() — xterm.js handles them natively.
 * When false, NSTextView is active (dev/JVM mode) and ANSI must be stripped first.
 */
public boolean isInBundle() {
    return MyMacUI_h.myui_is_bundle() != 0;
}
```

- [ ] **Step 5: Run test to confirm it passes**

```bash
mvn test -pl app-macos -Dtest=MacUIBridgeTest
```

Expected: PASS (all MacUIBridgeTest tests pass including the new one).

- [ ] **Step 6: Commit**

```bash
git add app-macos/src/main/java/dev/mproctor/cccli/bridge/gen/MyMacUI_h.java \
        app-macos/src/main/java/dev/mproctor/cccli/bridge/MacUIBridge.java \
        app-macos/src/test/java/dev/mproctor/cccli/bridge/MacUIBridgeTest.java
git commit -m "feat: add myui_is_bundle() Panama binding and MacUIBridge.isInBundle()

Java can now detect at startup whether it is running inside a .app bundle
(WKWebView mode) or JVM dev mode (NSTextView mode), to decide whether
to strip ANSI sequences before routing PTY output.

Closes #2"
```

---

## Task 5: Smoke-test WKWebView loads in the bundle (validate #2 + #3 end-to-end)

**Issues:** Refs #7 (entitlements investigation starts here)
**Files:** None — this is a test-only task.

This task verifies WKWebView actually renders in the `.app` bundle before committing to the full PTY routing change (Task 7). It also gathers information for the entitlements decision (#7).

- [ ] **Step 1: Build the native bundle**

```bash
mvn install -Pnative
```

Expected: BUILD SUCCESS. Bundle at `app-macos/target/Claude Desktop CLI.app`.

- [ ] **Step 2: Launch and observe**

```bash
open "app-macos/target/Claude Desktop CLI.app"
```

Expected: The app opens. The top pane shows a dark terminal area (xterm.js). "Connecting to Claude..." is visible in the xterm.js terminal (rendered via the initial text). PTY output appears as plain text (stripped, because Task 7 hasn't changed the routing yet — ANSI stripping still happens in Java). The app is otherwise fully functional.

If the pane is blank with no content at all:
1. Open Console.app → filter for "Claude Desktop"
2. Look for `WebKit` or `sandbox` errors
3. If you see `WebContent` process launch failures, proceed to Task 6 (entitlements) before Task 7

- [ ] **Step 3: Check Console.app for WebKit errors**

```bash
log stream --predicate 'processImagePath CONTAINS "claude-desktop"' --level debug 2>/dev/null &
open "app-macos/target/Claude Desktop CLI.app"
# Let it run for 10 seconds, then kill the log stream
```

If no WebKit errors appear: note "no entitlements required for ad-hoc signing" for the ADR in Task 6.
If errors appear: document them — they drive the entitlements needed in Task 6.

---

## Task 6: Validate and configure WKWebView codesigning entitlements

**Issues:** Closes #7
**Files:**
- Create (if needed): `app-macos/src/main/resources/entitlements.plist`
- Modify (if needed): `scripts/bundle.sh`

Background: Ad-hoc signing without `--options runtime` should not require entitlements for WKWebView. But this needs empirical confirmation. This task documents findings as an ADR regardless.

- [ ] **Step 1: Inspect the current bundle signature**

```bash
codesign -d --entitlements - "app-macos/target/Claude Desktop CLI.app" 2>&1
```

Expected with current ad-hoc signing: `[Dict]` with empty or no entitlements, or a warning that no entitlements are embedded.

- [ ] **Step 2: If WKWebView worked in Task 5 smoke test — document and close**

If xterm.js rendered correctly in Task 5 with no WebKit errors in Console.app, no entitlements changes are needed. Create the ADR and close the issue.

Create an entry in `DECISIONS.md`:

```markdown
## ADR-016: WKWebView does not require entitlements under ad-hoc signing

**Context:** Plan 5b adds WKWebView with xterm.js. WKWebView spawns a separate WebContent
process that requires IPC between the host process and the renderer. This can fail without
the correct code signature.

**Decision:** No entitlements file is needed when signing with `codesign --sign -` (ad-hoc)
without `--options runtime`. WKWebView's WebContent process launches and renders correctly.

**Verified:** Tested on macOS [version], Apple Silicon. Console.app showed no WebKit errors.

**If hardened runtime is added later:** Add `com.apple.security.cs.allow-jit` to allow
WebKit's JavaScript JIT compiler, and optionally `com.apple.security.cs.disable-library-validation`.
```

Skip to Step 5.

- [ ] **Step 3: If WKWebView failed — create entitlements.plist**

Create `app-macos/src/main/resources/entitlements.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
    "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- Required for WebKit JIT (JavaScript engine) when using hardened runtime -->
    <key>com.apple.security.cs.allow-jit</key>
    <true/>
</dict>
</plist>
```

- [ ] **Step 4: If entitlements.plist was needed — update bundle.sh**

Add variable after existing declarations:

```bash
ENTITLEMENTS="$PROJECT_DIR/app-macos/src/main/resources/entitlements.plist"
```

Change the codesign line from:
```bash
codesign --sign - --force --deep "$BUNDLE"
```
to:
```bash
codesign --sign - --force --deep --entitlements "$ENTITLEMENTS" "$BUNDLE"
```

Rebuild and re-test:
```bash
mvn install -Pnative
open "app-macos/target/Claude Desktop CLI.app"
```

- [ ] **Step 5: Commit**

```bash
# If no entitlements needed:
git add DECISIONS.md
git commit -m "docs: ADR-016 — WKWebView works without entitlements under ad-hoc signing

Closes #7"

# If entitlements were needed:
git add app-macos/src/main/resources/entitlements.plist scripts/bundle.sh DECISIONS.md
git commit -m "feat: add WKWebView JIT entitlements to .app bundle codesigning

WKWebView requires com.apple.security.cs.allow-jit to spawn its WebContent
process. Added entitlements.plist and updated bundle.sh codesign invocation.
ADR-016 documents the findings.

Closes #7"
```

---

## Task 7: Route PTY bytes to xterm.js — stop ANSI stripping in bundle mode

**Issues:** Closes #5
**Files:**
- Modify: `app-macos/src/main/java/dev/mproctor/cccli/Main.java`

- [ ] **Step 1: Write a failing test**

There is no unit-testable path here — the change is in `run()` which wires PTY to bridge. The integration test is the native build. Proceed to implementation.

- [ ] **Step 2: Modify Main.java to skip ANSI stripping in bundle mode**

In `app-macos/src/main/java/dev/mproctor/cccli/Main.java`, change the `pty.startReader` call.

Current code (line 46–49):
```java
pty.startReader(text -> {
    detector.onOutput();
    bridge.appendOutput(AnsiStripper.strip(text));
});
```

New code:
```java
boolean webViewMode = bridge.isInBundle();
Log.infof("Output mode: %s", webViewMode ? "WKWebView/xterm.js (raw bytes)" : "NSTextView (ANSI stripped)");

pty.startReader(text -> {
    detector.onOutput();
    // In WKWebView mode, xterm.js handles ANSI natively — do NOT strip.
    // In NSTextView dev mode, strip so escape codes don't appear as literal chars.
    bridge.appendOutput(webViewMode ? text : AnsiStripper.strip(text));
});
```

Note: `bridge.isInBundle()` is called once before the reader starts, not on every byte. The result is captured in a local `boolean` to avoid the Panama downcall overhead per read.

- [ ] **Step 3: Verify JVM mode still works**

```bash
mvn test
```

Expected: all tests pass. No compilation errors.

- [ ] **Step 4: Build native and test full color output**

```bash
mvn install -Pnative
open "app-macos/target/Claude Desktop CLI.app"
```

Expected:
- xterm.js terminal renders Claude Code's rich colour output
- Claude's progress spinner, colour-coded responses, bold/italic formatting all work
- Cursor movement (overwrite lines) works correctly
- No raw escape code characters appear (`ESC[`, `\u001B`, etc.)

- [ ] **Step 5: Commit**

```bash
git add app-macos/src/main/java/dev/mproctor/cccli/Main.java
git commit -m "feat: route PTY bytes to xterm.js without ANSI stripping in bundle mode

In WKWebView mode, xterm.js handles ANSI/VT100 natively. Stripping was
needed only for NSTextView which has no terminal emulation.

Closes #5"
```

---

## Task 8: Remove AnsiStripper

**Issues:** Closes #6
**Files:**
- Delete: `app-core/src/main/java/dev/mproctor/cccli/AnsiStripper.java`
- Delete: `app-core/src/test/java/dev/mproctor/cccli/AnsiStripperTest.java`

Only do this task after Task 7 is merged and confirmed working end-to-end.

- [ ] **Step 1: Verify no remaining references to AnsiStripper**

```bash
grep -r "AnsiStripper" --include="*.java" .
```

Expected: no output. If any files reference it, fix them before proceeding.

- [ ] **Step 2: Delete the files**

```bash
rm app-core/src/main/java/dev/mproctor/cccli/AnsiStripper.java
rm app-core/src/test/java/dev/mproctor/cccli/AnsiStripperTest.java
```

- [ ] **Step 3: Verify tests still pass**

```bash
mvn test
```

Expected: all tests pass. AnsiStripperTest is gone; the remaining 55+ tests all green.

- [ ] **Step 4: Commit**

```bash
git add -u
git commit -m "refactor: delete AnsiStripper — xterm.js handles ANSI natively

NSTextView dev mode never needed the full correctness of AnsiStripper anyway.
WKWebView mode does not strip at all. The class has no remaining callers.

Closes #6"
```

---

## Definition of Done

- [ ] `mvn test` passes (all tests green)
- [ ] `mvn install -Pnative` produces `Claude Desktop CLI.app`
- [ ] App launches and xterm.js renders in the output pane
- [ ] Claude Code's colour output, bold text, cursor movement, and spinner all display correctly
- [ ] No `ESC[` literal escape codes visible
- [ ] Console.app shows no WebKit/sandbox errors during launch
- [ ] `grep -r "AnsiStripper" --include="*.java" .` returns nothing
- [ ] ADR-016 written in DECISIONS.md documenting entitlements findings
- [ ] All issues #2, #3, #4, #5, #6, #7 closed; epic #1 closed
