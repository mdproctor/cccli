# The Split Pane: Seven Bugs and What They Taught Us

*Part 3 of an evolving series.*

---

## The Goal

Prove the Java ↔ native UI communication pipeline end-to-end: type in a native input field, press Enter, Java receives it via Panama upcall, Java sends text back to the display pane. Simple idea. Seven bugs.

---

## Bug 1: NSSplitView blocks keyboard events

The original plan used NSSplitView to split the window into terminal (top) and input (bottom) panes. The split view was set as `window.contentView`. Result: no mouse events, no keyboard events, nothing reached any subview.

The fix was discovered by stripping everything back to the absolute minimum — an NSTextField added directly to the existing `window.contentView` without replacing it. That worked immediately. The rule: **never replace `window.contentView`**. Add views to it.

---

## Bug 2: WKWebView's subprocess silently fails in a JVM process

The terminal pane was planned as WKWebView + xterm.js. The WebView appeared (correct size, correct position), JavaScript evaluated without errors, but nothing ever rendered. The `WKNavigationDelegate` never fired.

Root cause: WKWebView spawns a separate `WebContent` subprocess. In a JVM process launched from a terminal (no `.app` bundle), the IPC between host and WebContent silently fails. `evaluateJavaScript:` returns success but the result goes nowhere.

Fix for now: replace WKWebView with a plain NSTextView for the output pane. Works perfectly, no subprocess, no WebKit. WKWebView + xterm.js returns in Plan 3 when the app runs as a proper `.app` bundle — where WebKit's subprocess model actually works.

---

## Bug 3: dispatch_async blocks don't drain when [NSApp run] is inside a dispatch_async

This one took the longest to find. We call `myui_start()` from a `dispatch_async(main_queue, ^{ setupUI(); [NSApp run]; })` block. From GCD's perspective, that outer block is still executing — `[NSApp run]` never returns until the app terminates. So GCD's main queue serialisation locks out any new blocks. `dispatch_async(main_queue, ...)` and `dispatch_after(...)` calls simply queue up and never execute.

AppKit events (typing, clicking, window delegates) still work because `[NSApp run]` pumps the CFRunLoop directly, bypassing GCD entirely.

**Consequence:** Any code that needs to run "after the run loop starts" must use AppKit delegate methods, not dispatch. Any synchronous UI update called from an AppKit event handler must update the view directly, not via dispatch.

This single insight explains bugs 4, 5, and 7 below.

---

## Bug 4: Text submitted by user never appeared in output pane

Java received the text (logged correctly), called `bridge.appendOutput()`, which called the ObjC `myui_append_output()` function. The function dispatched a block to the main queue to update NSTextView. The block never ran (see Bug 3).

Fix: detect if we're already on the main thread and update synchronously:

```objc
if ([NSThread isMainThread]) {
    doAppend(str);        // synchronous — called from AppKit event handler
} else {
    dispatch_async(dispatch_get_main_queue(), ^{ doAppend(str); });
}
```

---

## Bug 5: Cursor invisible until first Enter

NSTextField received keystrokes (text appeared as typed) but showed no cursor. After pressing Enter — which triggers the action, clears the field, and transitions through a non-empty editing state — the cursor appeared.

This is a known AppKit bug: an empty NSTextField that gains first responder programmatically never starts the insertion-point blink timer. The canonical fix is a no-op string assignment after `makeFirstResponder:`:

```objc
[inputField setStringValue:@" "];
[inputField setStringValue:@""];
```

But every attempt to schedule this via `dispatch_after` failed (Bug 3 again). The fix: do it in `windowDidBecomeKey:`, an AppKit delegate method that fires inside the run loop at exactly the right moment.

---

## Bug 6: Placeholder text invisible on dark background

The NSTextField placeholder (`placeholderString`) uses the system's default placeholder colour — a semi-transparent grey that's near-invisible against a dark background. Fix: use `placeholderAttributedString` with an explicit colour.

---

## Bug 7: Cursor still missing (dispatch_after not running)

A `dispatch_after(50ms, main_queue, block)` was tried to defer the cursor fix until after the run loop started. It never ran. Bug 3, again. Confirmed by adding NSLog inside the dispatch block — it never appeared in the output.

---

## What Was Validated

By the end of the session:

- NSTextField input → Enter → Panama upcall → Java ✅
- Java → Panama downcall → ObjC → NSTextView update ✅  
- Text appears in output pane on Enter ✅
- Cursor visible from window open ✅
- Close window → clean upcall → terminate ✅
- Works in JVM mode and native image (0.020s startup) ✅

---

## The Pattern

Every bug in this session taught the same lesson from a different angle: the threading model of this architecture (GCD + AppKit + Panama FFM + Quarkus) has sharp edges. Once you understand that GCD main queue serialisation locks out dispatch blocks when `[NSApp run]` is nested inside one, everything else follows. Synchronous updates, AppKit delegates, no dispatch. That's the rule.

---

*Next: PTY integration — spawning `claude` as a subprocess and routing its output to the display pane.*
