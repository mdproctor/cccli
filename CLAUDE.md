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
- `DECISIONS.md` — ADR-001 to ADR-018
- `docs/APPKIT_PITFALLS.md` — **read before any Obj-C/AppKit debugging**
- `docs/HANDOFF.md` — session handover (state, next steps, pitfalls)

## Build

```bash
# JVM mode (fast iteration)
mvn install

# Native image + .app bundle (requires GraalVM — not just any Java 22)
# Must skip tests: PtyProcessTest crashes with GraalVM 25 (exit 133 / SIGTRAP).
# Run tests separately first with JDK 26: jenv shell 26 && mvn test
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn install -Pnative -DskipTests
```

Bundle output: `app-macos/target/Claude Desktop CLI.app`

## Test

```bash
jenv shell 26
mvn test
```

**Required:** `surefire reuseForks=false` in `app-core/pom.xml` — Panama FFM PTY I/O corrupts JVM state between test classes on macOS AArch64 (SIGTRAP exit 133 symptom).

Note: Requires JDK 26 active via jenv. Native builds still require GraalVM 25 (`jenv shell graalvm64-25`).

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose.

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** mdproctor/cccli
**Changelog:** GitHub Releases (run `gh release create --generate-notes` at milestones)

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — when the user says "implement", "start coding",
  "execute the plan", "let's build", or similar: check if an active issue or epic
  exists. If not, run issue-workflow Phase 1 to create one **before writing any code**.
- **Before writing any code** — check if an issue exists for what's about to be
  implemented. If not, draft one and assess epic placement (issue-workflow Phase 2)
  before starting. Also check if the work spans multiple concerns.
- **Before any commit** — run issue-workflow Phase 3 (via git-commit) to confirm
  issue linkage and check for split candidates. This is a fallback — the issue
  should already exist from before implementation began.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
  If the user explicitly says to skip ("commit as is", "no issue"), ask once to confirm
  before proceeding — it must be a deliberate choice, not a default.
