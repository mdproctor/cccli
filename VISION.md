# Claude Desktop CLI — Vision

> Vision, founding decisions, and rationale behind the architecture. For the current implemented architecture, see `DESIGN.md`.

## Overview

A macOS desktop application that wraps the Claude Code CLI with a superior input/output experience. The top pane displays Claude Code output in a full terminal emulator. The bottom pane is a native text input with OS-quality editing. An interaction detection layer watches the PTY byte stream and upgrades the input surface to match what the CLI is presenting — switching to a list picker when Claude presents choices, a confirmation widget when it asks yes/no, and so on.

---

## Core Design Principles

1. **Claude Code runs unmodified** — spawned as a PTY subprocess; never patched, forked, or dependent on its internals.
2. **The PTY is the source of truth** — all interaction state derives from the byte stream. If detection fails, the user toggles to passthrough and carries on.
3. **The enhanced UI is an upgrade, never a blocker** — graceful degradation is a first-class requirement, not an afterthought.
4. **Platform portability is designed in from day one** — macOS is the first target; module boundaries make Linux and Windows straightforward additions.
5. **`MainWindow` never knows what is underneath it** — it talks only to `SessionProvider` and `OutputRenderer` interfaces. Swapping implementations requires no window changes.

---

## Technology Decisions

### Objective-C for the native bridge

The native macOS UI layer is a minimal, purpose-built Objective-C bridge (`MyMacUI.dylib`) exposing only the AppKit primitives this app needs via a clean C ABI. Objective-C was chosen over Swift because Panama's `jextract` generates bindings from C headers cleanly; Swift's ABI is unstable and name-mangled. This gives stable, reliable Panama FFM bindings with lower risk under GraalVM Native Image upcalls.

### Panama FFM over JNA / pty4j

Both the AppKit bridge and PTY management use the Panama Foreign Function & Memory API (stable since Java 22) rather than JNA-based libraries. JNA relies on runtime reflection and dynamic class loading — patterns GraalVM's static analyser cannot see through without fragile substitution configuration. Panama FFM downcalls and upcalls are first-class in GraalVM Native Image.

### Quarkus Native

Quarkus Native (GraalVM/Mandrel) compiles the Java application to a standalone native binary: fast startup, single `.app` bundle, no bundled JRE. Quarkus simplifies the native image build toolchain and provides CDI. The service and REST features of Quarkus are unused; we take the native image tooling and dependency injection.

### WKWebView + xterm.js for the terminal pane

AppKit has no built-in terminal emulator widget. Implementing one in Objective-C would be a large, fragile codebase that contradicts the minimal bridge principle. WKWebView (WebKit, native-quality rendering on macOS) hosting xterm.js (the industry-standard VT terminal emulator, used by VS Code and GitHub Codespaces) gives essentially perfect ANSI compatibility with zero custom rendering code.

---

## Key Interfaces

```java
// app-core — session lifecycle and event stream
interface SessionProvider {
    void send(String input);
    Flux<SessionEvent> events();
    void close();
}

// app-macos — how events are displayed
interface OutputRenderer {
    void accept(SessionEvent event);
    void clear();
}
```

`MainWindow` wires these together and nothing else. In v1, `SessionProvider` is `PtySessionProvider` and `OutputRenderer` is `XtermJsRenderer`. In future direct API mode, `SessionProvider` becomes `ApiSessionProvider` — the window and input surfaces change nothing.

---

## The Two Modes

```
AppMode.ENHANCED
  └── InteractionMode: FREE_TEXT | SLASH_COMMAND | LIST_SELECTION |
                       FREE_TEXT_ANSWER | CONFIRMATION | PASSIVE

AppMode.PASSTHROUGH
  └── xterm.js has full keyboard focus; input pane collapsed
```

`Cmd+\` toggles between modes. A status badge on the window border shows the current mode.

---

## Roadmap

**v1 — PTY mode (macOS)**
Terminal in WKWebView + xterm.js. Native NSTextView input. Interaction detection via PTY byte stream pattern matching.

**Near-term**
Slash command system. Settings UI (binary path, model, theme). Linux support via a GTK bridge module.

**Medium-term**
Content pane alongside terminal for file diffs and markdown previews. Direct API mode: `ApiSessionProvider` replaces `PtySessionProvider`; structured events replace byte-stream pattern matching, retiring `InteractionDetector` entirely.

**Longer-term**
Voice input and TTS. Multi-session workspace.
