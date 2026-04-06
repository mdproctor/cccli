# Claude Desktop CLI — WKWebView, xterm.js, and the Issue Tracking Foundation

**Date:** 2026-04-07
**Type:** phase-update

---

## What we were trying to achieve: Plan 5b and a foundation for future work

The previous session ended with the `.app` bundle working and 60 passing tests. This one had two goals: implement WKWebView + xterm.js (Plan 5b), and get proper issue tracking in place so future work has somewhere to land.

## What I believed going in: the WebView work would be the hard part

The pitfalls doc already warned that WKWebView silently fails in JVM mode, that `dispatch_async` doesn't work inside `[NSApp run]`, and that replacing `window.contentView` breaks keyboard routing. I had a clear picture of what could go wrong technically.

What I didn't expect was spending meaningful time designing a skill for issue creation.

## Three layers before any code: GitHub, an epic, and a skill for next time

I got Claude set up with the project context. We created the GitHub repo, pushed everything, and immediately asked: how should future work get tracked? The answer was epics and child issues — one GitHub issue per planned task, an umbrella epic for the whole plan.

I wanted that pattern to work automatically for every future project. So before starting Plan 5b, we designed a `github-epic-workflow` skill: Claude prompts before any implementation to ask about issue creation, fills in titles and descriptions from context, and handles the ad-hoc case where a bug surfaces mid-sprint — each one needs a judgment call about whether it belongs to the current epic, another epic, or neither, and the skill confirms with the user each time.

Writing the skill text took longer than expected. Epic #1 was created for Plan 5b, issues #2–#7 covering each task.

## Runtime detection, a page-ready buffer, and base64

The WKWebView implementation had one non-obvious design decision: how to detect at runtime whether to use WKWebView or fall back to NSTextView in JVM dev mode. Compile-time flags would require two dylib builds. We went with `myui_is_bundle()` — looks for `Resources/xterm/index.html` in `NSBundle.mainBundle`. Present means bundle mode; absent means dev mode.

The second constraint: the PTY reader thread starts before `setupUI()` creates the WebView. xterm.js takes a moment to load after `loadFileURL:`. Any PTY output arriving before `didFinishNavigation:` fires would be lost. We buffered it — an `NSMutableArray` accumulates until the page-ready flag sets, then flushes in order.

PTY bytes go to xterm.js via `Uint8Array.from(atob('<base64>'), c => c.charCodeAt(0))`. Arbitrary binary output in, typed array out, xterm.js handles the UTF-8. No injection problem.

## Eight tasks, three commit message fixes

We executed Plan 5b using subagent-driven development — a fresh Claude instance per task, spec compliance review, then code quality review.

The recurring issue: every implementer subagent used internal task numbers (#4, #8) in commit messages instead of GitHub issue numbers (#2, #6). Spec review caught it every time. Three commits needed amending. Not a code problem — a process gap between "task in the plan" and "issue on GitHub."

One thing Claude caught unprompted: the plan placed module-level ObjC statics before the `@interface` they referenced, which the compiler rejects. The Task 3 implementer added an `@class CCCAppDelegate` forward declaration, noted it, and moved on.

## Verifying the WebView without a screen

Smoke-testing WKWebView from a terminal session is awkward without visual inspection. But WKWebView's renderer runs as a separate XPC process. If `ps aux | grep WebKit.WebContent` shows a new PID after launch, the renderer is alive and IPC is working. We launched the bundle, checked — new WebContent PID appeared, no WebKit errors in the system log.

No entitlements needed for ad-hoc signing without hardened runtime. ADR-016.

`AnsiStripper` is deleted. xterm.js handles it.
