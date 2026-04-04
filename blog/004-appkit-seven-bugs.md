# Seven Things That Don't Work in AppKit (And What Does)

*Part 4 of an evolving series on building a native macOS app with Java, Panama FFM, and Claude.*

---

Building the split-pane UI for Claude Desktop CLI took one session and produced seven distinct bugs. None of them crashed the app. Most of them produced no error at all. They just silently didn't work — which, it turns out, is the hardest kind of bug to fix.

Here's what happened, what I tried, and what actually worked.

---

## The Setup

We're building a macOS desktop app where Java talks to native AppKit via an Objective-C bridge, using Panama FFM. The window has two panes: an output area on top, a text input on the bottom. When you type and press Enter, Java receives the text via a Panama upcall, processes it, and sends a response back to the output pane via a Panama downcall.

Sounds straightforward. It was not.

---

## Bug 1: NSSplitView silently swallows all events

The natural choice for a two-pane layout is NSSplitView. I created one, set it as `window.contentView`, added the output view and input field. The window appeared. The split looked right. Nothing responded to clicks or keypresses.

No error. No warning. Just silence.

I spent time trying `recalculateKeyViewLoop`, explicit `makeFirstResponder:` calls, different view hierarchies inside the split. Nothing worked.

The fix came from stripping everything back to the minimum possible test: one NSTextField, added directly to `window.contentView` without replacing it. That worked immediately. So the rule is:

**Never replace `window.contentView`. Add views to it.**

When you call `window.contentView = myNewView`, you break an internal connection in AppKit between the window and its responder chain. The existing content view (a plain NSView AppKit creates for you) has this connection set up correctly. Replacing it destroys it. There's no error, no log — it just stops working.

NSSplitView is particularly bad as a contentView replacement. It doesn't just break keyboard routing — it breaks mouse routing too. Everything that isn't the window chrome (traffic lights, title bar) becomes unresponsive.

---

## Bug 2: WKWebView renders nothing in a JVM process

The output pane was designed for WKWebView with xterm.js. The WebView appeared, had the correct size, wasn't hidden. I loaded HTML. I called `evaluateJavaScript:`. The completion handler reported no error. But nothing appeared on screen, ever.

The `WKNavigationDelegate` methods — `didFinishNavigation:`, `didFailNavigation:` — never fired. As far as ObjC was concerned, everything succeeded. As far as the screen was concerned, nothing happened.

This one required web research to understand. WKWebView renders in a separate process called `WebContent`. In a proper `.app` bundle, macOS sets up the IPC between your app and the WebContent process automatically. In a JVM process launched from a terminal without a bundle, this IPC silently fails. The WebContent process either doesn't start or can't communicate back. Your calls appear to succeed but their effects never arrive.

The fix for development: replace WKWebView with NSTextView. It's entirely in-process. It works. WKWebView will be used in production, where the app runs as a proper `.app` bundle and the subprocess model works correctly.

This is one of the clearest examples of "works in production, silent failure in development" I've encountered.

---

## Bug 3: The GCD block that never ran

This one took the longest to understand because it explained every other timing problem we hit.

When I needed to do something after the AppKit run loop started — set initial focus, fix the cursor, update the display — the natural approach was `dispatch_async(dispatch_get_main_queue(), block)` or `dispatch_after(delay, main_queue, block)`. These blocks would be queued. They would never execute.

Here's why. The way `myui_start()` works, it calls `[NSApp run]` from inside a `dispatch_async(main_queue, ...)` block. From GCD's perspective, that outer block is still executing — `[NSApp run]` doesn't return until the app terminates. GCD serializes the main queue. It won't dispatch new blocks onto it while one is already running.

AppKit events (typing, clicking, window delegate callbacks) still work because `[NSApp run]` pumps CFRunLoop directly, completely bypassing GCD. But `dispatch_async` and `dispatch_after` go through GCD and hit the lock.

Once I understood this, everything else made sense:
- Why the focus-after-startup dispatch never ran ✓
- Why a 200ms deferred call never executed ✓
- Why updating the output display via dispatch never worked ✓

**The rule:** If you need to do something after the run loop starts, use an AppKit delegate method (`windowDidBecomeKey:`, `applicationDidFinishLaunching:`). If you need to update UI from inside a Panama upcall, update synchronously — don't dispatch.

---

## Bug 4: Text submitted but never appeared

With the output pane switched to NSTextView, the chain looked like this: user presses Enter → Panama upcall → Java receives text → Java calls `bridge.appendOutput()` → Panama downcall → ObjC appends to NSTextView.

Java logged `"Input received: hello"`. The NSTextView didn't update.

This was Bug 3 in disguise. The ObjC append function was dispatching to the main queue via `dispatch_async`. The block was queued. The block never ran.

Fix: check the thread and update synchronously when already on the main thread.

```objc
if ([NSThread isMainThread]) {
    [theOutputView setString:updated]; // synchronous
} else {
    dispatch_async(dispatch_get_main_queue(), ^{
        [theOutputView setString:updated];
    });
}
```

The call comes from an AppKit event handler (NSTextField action → ObjC callback → Panama upcall → Java → Panama downcall → ObjC), so it's always on the main thread. Dispatch is never needed here. Once that was clear, the update was one line.

---

## Bug 5: NSTextField cursor invisible until first Enter

The text field worked — keystrokes appeared as I typed. But there was no blinking cursor. The field looked dead. After pressing Enter once, the cursor appeared and stayed.

This is a documented AppKit bug. When an empty NSTextField gains first responder status programmatically, AppKit installs the field editor correctly (keystrokes work) but never starts the insertion-point blink timer. The cursor is there; it just doesn't blink and isn't drawn. After Enter, the field transitions through a non-empty state during the action handler, which triggers a full re-focus cycle that starts the timer properly.

The canonical fix is a no-op string assignment after `makeFirstResponder:`:

```objc
[inputField setStringValue:@" "];
[inputField setStringValue:@""];
```

This tickles the field editor into starting the blink timer without visibly changing anything. But every attempt to schedule this via `dispatch_after` failed (Bug 3 again). The only place it works is an AppKit delegate method:

```objc
- (void)windowDidBecomeKey:(NSNotification *)notification {
    if (self.inputField) {
        [notification.object makeFirstResponder:self.inputField];
        [self.inputField setStringValue:@" "];
        [self.inputField setStringValue:@""];
        self.inputField = nil; // run once
    }
}
```

`windowDidBecomeKey:` fires inside the run loop, at the exact moment the window gains keyboard focus. It's the right hook — not a timer, not a dispatch, not a deferred call.

---

## Bug 6: Placeholder text invisible on dark background

NSTextField's `placeholderString` property uses the system's default placeholder colour — a semi-transparent grey intended for light backgrounds. Against a dark `#1E1E1E` background, it's essentially invisible.

Fix: use `placeholderAttributedString` with an explicit colour:

```objc
inputField.placeholderAttributedString = [[NSAttributedString alloc]
    initWithString:@"Type a message and press Enter…"
        attributes:@{ NSForegroundColorAttributeName:
            [NSColor colorWithRed:0.50 green:0.50 blue:0.50 alpha:1.0] }];
```

A medium grey (50%) is readable on dark, without looking washed out.

---

## Bug 7: The JVM vs native image threading difference

This came earlier but belongs in the list. In JVM mode (`mvn quarkus:dev`), Quarkus calls `@QuarkusMain.run()` on a worker thread — the OS main thread is busy elsewhere. In native image (GraalVM compiled binary), Quarkus calls `run()` synchronously on the OS main thread.

AppKit requires the main thread for all UI. So `myui_start()` needs to handle both cases. If called from a worker thread, dispatch setup to the main thread via GCD and block the worker. If called from the main thread, use `CFRunLoopRun()` instead (to avoid the GCD serialisation trap that `dispatch_semaphore_wait` would create on the main thread).

```objc
if ([NSThread isMainThread]) {
    dispatch_async(dispatch_get_main_queue(), ^{
        // ... setup + [NSApp run] ...
        CFRunLoopStop(CFRunLoopGetCurrent());
    });
    CFRunLoopRun();
} else {
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_main_queue(), ^{
        // ... setup + [NSApp run] ...
        dispatch_semaphore_signal(done);
    });
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
}
```

The same code path needs to handle the same task in two different threading contexts. The branch on `[NSThread isMainThread]` is the key.

---

## What This Cost and What It Bought

Seven bugs. Most of them silent. Most of them producing behaviour that looked like success from one angle and failure from another.

In traditional development this would cost days. With Claude helping diagnose, most bugs resolved in minutes once the right diagnostic information was collected — the right NSLog in the right place, stripping back to the minimal test, checking one assumption at a time.

The patterns that kept appearing: silent failures, assumptions about what "should work" in AppKit that turned out to be wrong, and the GCD/AppKit threading model being subtler than expected. Once the GCD serialisation issue was understood (Bug 3), it retrospectively explained three other bugs.

None of these are obvious from documentation. They're the kind of thing you learn by doing. I'm writing them down so the next person — or the next Claude session — doesn't have to learn them again.

---

*Next: Plan 3 — PTY management and spawning the actual `claude` subprocess.*
