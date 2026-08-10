# Claude Desktop CLI — Terminal Resize and the Bug That Was Always There

**Date:** 2026-04-07
**Type:** phase-update

---

## What I was trying to achieve: make the window resize actually work

Two candidates for this session. Slash command overlay needed brainstorming before planning — architecture unclear, transparent NSView vs popup, nothing decided. Terminal resize was straightforward by comparison: wire AppKit window resize events through to PTY `TIOCSWINSZ` and xterm.js `term.resize()`. I picked the simpler one.

## What I believed going in: TIOCSWINSZ works, this is just plumbing

The PTY layer already had `PtyProcess.resize(rows, cols)` — it calls `ioctl(masterFd, TIOCSWINSZ, ...)` via Panama FFM. The hard part would be getting the window resize event into Java and the correct col/row values out of xterm.js. I assumed the ioctl itself was fine.

It wasn't.

## The pipeline design: FitAddon knows what Java doesn't

The interesting design decision was who computes the terminal dimensions. FitAddon is the official xterm.js extension for exactly this — it measures the actual rendered character cell at runtime and computes the exact grid. Java pixel math would mean hardcoding font metrics. Internal xterm.js APIs would break on upgrades.

We went with FitAddon. The pipeline:

`windowDidResize:` → `requestAnimationFrame(()=>fitAddon.fit())` → `term.onResize` posts `{cols, rows}` via `WKScriptMessageHandler "termSize"` → `WindowResizedCallback` C function pointer → `pty.resize(rows, cols)`.

The `requestAnimationFrame` deferral matters: `windowDidResize:` fires before the WKWebView has reflowed, so `fitAddon.fit()` would read a stale `offsetWidth` without it.

## Claude came back DONE_WITH_CONCERNS

Task 3 was adding tput-based integration tests — spawn `tput cols` on the PTY and verify it reports the value set by `resize()`. The implementer came back marked `DONE_WITH_CONCERNS`: the tput tests themselves pass, but getting there required fixing a real production bug.

`TIOCSWINSZ` was silently broken. `ioctl()` in Panama FFM was missing `Linker.Option.firstVariadicArg(2)`:

```java
private static final MethodHandle IOCTL = LINKER.downcallHandle(
    LIBC.find("ioctl").orElseThrow(),
    FunctionDescriptor.of(ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
    Linker.Option.firstVariadicArg(2));  // this was missing
```

`ioctl()` is variadic. On AArch64, Panama's non-variadic descriptor uses a different calling convention for pointer arguments. The pointer was landing in the wrong register. The kernel wrote to a garbage address and returned 0. No error, no exception — the PTY just never updated. This had been broken since day one.

The fix is one line. Finding it required tput tests that verify the kernel state end-to-end, which is why the TIOCGWINSZ approach was always going to miss it — Panama FFM's IOC_OUT direction has the same ABI problem, so reading the ioctl back would have silently returned zeros too.

## 58 tests, seven new

Three tput tests verify dimensions round-trip correctly through the kernel. Three verify the `ioctlGetWinsize` API contract — not the exact values (Panama JVM mode can't read them back), just that the API is callable and stable. One bridge smoke test exercises the `setResizeCallback` Panama binding against the live dylib.

The hardcoded `pty.resize(24, 120)` startup call is gone. FitAddon sets the initial size when the page loads, then every window resize keeps both xterm.js and the PTY in sync.
