# AppKit + Panama FFM + Quarkus: Pitfalls Reference

This document captures hard-won knowledge from building a native macOS app using
Objective-C bridge + Panama FFM + Quarkus Native. Every entry was discovered the
hard way. Read this before debugging AppKit/Panama problems.

---

## 1. GCD main queue blocks do not drain when [NSApp run] is inside a dispatch_async

### Symptom
`dispatch_async(dispatch_get_main_queue(), block)` and `dispatch_after(...)` are
called but the block **never executes**. No crash, no error — the block simply
sits in the queue forever.

### Context
This setup runs `[NSApp run]` inside a `dispatch_async(main_queue, ...)` block:

```objc
dispatch_async(dispatch_get_main_queue(), ^{
    myui_init_application();
    windowHandle = myui_create_window(...);
    setupUI(...);
    [NSApp run];              // ← blocks here until app terminates
    dispatch_semaphore_signal(done);
});
dispatch_semaphore_wait(done, ...);
```

From GCD's perspective, the outer block is **still executing** (it won't return
until `[NSApp run]` returns). GCD's main queue is a serial queue — it will not
dispatch new blocks onto it while one is already running. So any `dispatch_async`
or `dispatch_after` targeting the main queue silently queues and waits forever.

### What was tried (all failed)
- `dispatch_async(main_queue, focusBlock)` — never ran
- `dispatch_after(50ms, main_queue, focusBlock)` — never ran
- `dispatch_after(200ms, main_queue, focusBlock)` — never ran
- `[window performSelector:afterDelay:0]` — never ran
- `window.initialFirstResponder = inputField` — ran but too early, wrong timing

### What works
**AppKit events ARE processed** — `[NSApp run]` pumps CFRunLoop directly, bypassing GCD. Use AppKit delegate methods instead of dispatch for any post-startup initialization:

- `windowDidBecomeKey:` — fires when window gains keyboard focus (run loop is live)
- `applicationDidFinishLaunching:` — fires when NSApp finishes launching
- `NSTimer scheduledTimerWithTimeInterval:` — fires via CFRunLoop, not GCD

For UI updates called from within AppKit event handlers (Panama upcall context),
update **synchronously on the current thread** — do NOT dispatch:

```objc
void myui_append_output(const char *text) {
    NSString *str = [NSString stringWithUTF8String:text];
    if ([NSThread isMainThread]) {
        doAppend(str);   // synchronous — we are already on the main thread
    } else {
        dispatch_async(dispatch_get_main_queue(), ^{ doAppend(str); });
    }
}
```

### Why AppKit events still work
`[NSApp run]` processes AppKit events via CFRunLoop directly. Keyboard input,
mouse clicks, window delegate methods, and NSTimer all go through CFRunLoop —
not through GCD's main queue. Only `dispatch_async`/`dispatch_after` targets
the GCD main queue and is blocked.

---

## 2. Replacing window.contentView breaks keyboard event routing

### Symptom
After calling `window.contentView = myNewView`, mouse clicks and keyboard events
no longer reach any subview inside `myNewView`. Clicks on buttons do nothing.
NSTextField receives no input. The window close button (traffic light) still works.

### What was tried (all failed)
- `window.contentView = NSSplitView` — blocked all events to NSSplitView subviews
- `window.contentView = NSView container` — blocked all events to container subviews
- `[window recalculateKeyViewLoop]` after replacement — no effect
- `[window makeFirstResponder:subview]` after replacement — no effect

### What works
**Never replace `window.contentView`.** Add all views to the existing content view:

```objc
NSView *root = window.contentView;   // keep the existing view
[root addSubview:webView];
[root addSubview:inputField];
```

The existing `window.contentView` (a plain `NSView` created by AppKit) has the
correct event routing chain. Replacing it breaks an internal AppKit connection
between the window and its responder chain.

### NSSplitView specifically
NSSplitView set as `window.contentView` is particularly bad — it blocks ALL
events (mouse AND keyboard) to subviews. NSSplitView added as a subview of the
existing contentView also has event issues. **Do not use NSSplitView as the
top-level container.** Use manual layout with `addSubview:` and `autoresizingMask`.

---

## 3. WKWebView subprocess fails silently in a non-bundle JVM process

### Symptom
WKWebView is created, has the correct frame, is not hidden. `loadHTMLString:`
is called — no error. `evaluateJavaScript:` is called — completion handler fires
with `nil` error and `nil` result. But **nothing renders**. `WKNavigationDelegate`
methods (`didFinishNavigation:`, `didFailNavigation:`) never fire.

### Root cause
WKWebView spawns a separate `WebContent` process. In a JVM process launched from
a terminal (no `.app` bundle), the host process does not have the required sandbox
entitlements and app bundle structure for the IPC between host and WebContent to
work. The WebContent process either fails to start or fails to communicate back.
Everything appears to succeed from the ObjC side, but no content is displayed.

### What was tried (all failed)
- Setting `navigationDelegate` — delegate never called
- `WKWebViewConfiguration.websiteDataStore = nonPersistentDataStore` — no effect
- Various HTML structures — no effect
- Logging JS errors in completion handler — no errors reported, just nil result
- WKUserScript injection — scripts never executed

### What works
In development (JVM mode / `mvn quarkus:dev`), **replace WKWebView with NSTextView**
for the output pane. NSTextView is entirely in-process, no subprocess, works
perfectly.

WKWebView DOES work inside a proper `.app` bundle (native image, production build).
Reserve it for production. Design the output pane so the renderer is swappable
(e.g., `myui_append_output` for NSTextView today, xterm.js via WKWebView later).

---

## 4. NSTextField cursor (insertion point) invisible on programmatic focus

### Symptom
NSTextField is the first responder, text input works (keystrokes appear), but
**no blinking cursor is visible** until the user presses Enter once. After Enter,
the cursor appears and continues to work normally.

### Root cause
AppKit bug: when an empty NSTextField gains first responder status
programmatically, the insertion-point blink timer is never started. The field
editor is active (hence keystrokes work), but the cursor drawing loop doesn't
initialise. After Enter, the action fires and the field transitions through a
non-empty state, which causes a full re-focus cycle that starts the timer.

### What was tried (all failed)
- `[window makeFirstResponder:field]` before `[NSApp run]` — cursor not visible
- `window.initialFirstResponder = field` before `[NSApp run]` — cursor not visible
  (window was already key when this was set, so AppKit ignored it)
- `[editor updateInsertionPointStateAndRestartTimer:YES]` via `dispatch_after` —
  dispatch block never ran (see Pitfall 1)
- `[field setStringValue:@" "]; [field setStringValue:@""];` via `dispatch_after`
  — dispatch block never ran (see Pitfall 1)

### What works
Do the cursor fix **inside `windowDidBecomeKey:`** — an AppKit delegate method
that fires inside the run loop at exactly the right moment:

```objc
@property (nonatomic, weak) NSTextField *inputField; // store reference

- (void)windowDidBecomeKey:(NSNotification *)notification {
    if (self.inputField) {
        NSWindow *w = notification.object;
        [w makeFirstResponder:self.inputField];
        // No-op string tickles the field editor into starting the blink timer
        [self.inputField setStringValue:@" "];
        [self.inputField setStringValue:@""];
        self.inputField = nil; // run once only
    }
}
```

Set `appDelegate.inputField = inputField` in your setup function.

---

## 5. Panama upcalls from AppKit event handlers: no dispatch allowed

### Symptom
An ObjC function is called from a Panama upcall (which is called from an AppKit
event handler). The function calls `dispatch_async(main_queue, updateBlock)`.
The `updateBlock` never executes, even though the AppKit event loop is running.

### Root cause
Same as Pitfall 1. The upcall is triggered from within the AppKit event loop,
which is itself running inside a `dispatch_async` block (see architecture).
GCD main queue serialisation prevents `updateBlock` from executing.

### What works
Check the thread and update synchronously when on the main thread:

```objc
if ([NSThread isMainThread]) {
    // Called from AppKit event handler via Panama upcall
    // Update synchronously — dispatch_async will never drain
    [theOutputView setString:newContent];
} else {
    // Called from a background thread — dispatch is fine
    dispatch_async(dispatch_get_main_queue(), ^{
        [theOutputView setString:newContent];
    });
}
```

---

## 6. NSTextField placeholder invisible on dark background

### Symptom
`inputField.placeholderString = @"Type here..."` produces text that is nearly
invisible against a dark background colour.

### Root cause
The default placeholder uses the system placeholder colour (a semi-transparent
grey) which is designed for light backgrounds.

### What works
Use `placeholderAttributedString` with an explicit colour:

```objc
inputField.placeholderAttributedString = [[NSAttributedString alloc]
    initWithString:@"Type a message and press Enter…"
        attributes:@{ NSForegroundColorAttributeName:
            [NSColor colorWithRed:0.50 green:0.50 blue:0.50 alpha:1.0] }];
```

---

## 7. myui_start() threading: main thread vs worker thread behaviour differs

### Symptom
`myui_start()` is called from a Quarkus worker thread (JVM mode) and from the
OS main thread (native image / GraalVM). The same code path fails on one and
works on the other, or vice versa.

### Context
- **JVM mode**: Quarkus calls `@QuarkusMain.run()` on a **worker thread**. The
  OS main thread is blocked in `Quarkus.run()`.
- **Native image**: Quarkus calls `run()` synchronously on the **OS main thread**.

AppKit requires all UI operations on the OS main thread.

### What works
Branch on `[NSThread isMainThread]` in `myui_start()`:

```objc
if ([NSThread isMainThread]) {
    // Native image: already on main thread.
    // Use CFRunLoopRun() to drain the main queue, since [NSApp run] will
    // be called from within a dispatch_async block.
    dispatch_async(dispatch_get_main_queue(), ^{
        // ... setup and [NSApp run] ...
        CFRunLoopStop(CFRunLoopGetCurrent());
    });
    CFRunLoopRun();
} else {
    // JVM mode: worker thread. Dispatch setup to main thread,
    // block this thread until app terminates.
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_main_queue(), ^{
        // ... setup and [NSApp run] ...
        dispatch_semaphore_signal(done);
    });
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
}
```

Note: in the native image path, GCD IS correctly processed because `[NSApp run]`
is called from within a `dispatch_async` block (not from the main thread directly),
so the same Pitfall 1 behaviour applies — use AppKit delegates for post-startup work.

---

## Quick Diagnostic Checklist

When something isn't working in this architecture, check in this order:

1. **Is it a dispatch block that never runs?** → Are you inside the GCD serialisation
   lock (Pitfall 1)? Use AppKit delegates or synchronous updates instead.

2. **Is keyboard/mouse input not reaching a view?** → Did `window.contentView` get
   replaced somewhere? (Pitfall 2) Check view hierarchy with `po window.contentView`
   in lldb.

3. **Is WKWebView blank?** → Running as JVM process, not `.app` bundle? (Pitfall 3)
   Use NSTextView instead during development.

4. **Is NSTextField cursor missing?** → Apply the `windowDidBecomeKey:` + no-op
   string fix (Pitfall 4).

5. **Is a UI update from Java not appearing?** → Call is on main thread from upcall
   context? Use `[NSThread isMainThread]` branch and update synchronously (Pitfall 5).

6. **Is AppKit crashing with "must be on main thread"?** → Check which thread
   `@QuarkusMain.run()` is on and apply the JVM vs native image branch (Pitfall 7).
