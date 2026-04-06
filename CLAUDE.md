---
type: java
---

# Claude Desktop CLI

A native macOS app wrapping Claude Code in a proper terminal emulator. Built with Quarkus Native, Panama FFM, and an Obj-C/AppKit bridge. The UI is an NSWindow with a WKWebView (xterm.js) for terminal output and an NSTextField for input.

## Architecture

- `app-core/` — Java: PTY management (Panama FFM), process lifecycle, UI bridge calls
- `app-macos/` — Java: Quarkus Native entry point, CLI wiring
- `mac-ui-bridge/` — Obj-C: AppKit window, WKWebView, C API (`myui_*`) called from Java via Panama FFM
- `scripts/bundle.sh` — assembles and codesigns `Claude Desktop CLI.app`

Key docs:
- `DESIGN.md` — current architecture
- `DECISIONS.md` — ADR-001 to ADR-015
- `docs/APPKIT_PITFALLS.md` — **read before any Obj-C/AppKit debugging**
- `docs/HANDOFF.md` — session handover (state, next steps, pitfalls)

## Build

```bash
# JVM mode (fast iteration)
mvn install

# Native image + .app bundle
mvn install -Pnative
```

Bundle output: `app-macos/target/Claude Desktop CLI.app`

## Test

```bash
mvn test
```

**Required:** `surefire reuseForks=false` in `app-core/pom.xml` — Panama FFM PTY I/O corrupts JVM state between test classes on macOS AArch64 (SIGTRAP exit 133 symptom).

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** mdproctor/cccli
**Changelog:** GitHub Releases (run `gh release create --generate-notes` at milestones)

**Automatic behaviours (Claude follows these when this section is present):**
- Before starting any significant task, check if it spans multiple concerns.
  If it does, help break it into separate issues before beginning work.
- When staging changes before a commit, check if they span multiple issues.
  If they do, suggest splitting the commit using `git add -p`.
- All commits must reference an issue: `Refs #N` (ongoing) or `Closes #N` (done).
  Never commit without an issue reference unless the change is truly trivial
  (e.g. fixing a typo).
