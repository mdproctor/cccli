# Handover — 2026-04-06

**Head commit:** `8d0fcf2` — docs: ADR-012 through ADR-015
**Previous handover:** `git show HEAD~1:docs/HANDOFF.md`

## What Changed This Session

- **Blog entries 001–005 revised** — aligned with updated mandatory-rules.md:
  series subtitles removed, Date/Type metadata added, Claude introduced before
  first "we" in every entry, consecutive "we" varied, paragraph density improved
- **Blog files renamed** — `001-*.md` → `2026-04-04-NN-*.md` convention
- **DESIGN.md created and committed** — reflects Plans 3–5 accurately (was
  untracked and stale). NSTextField/NSTextView corrected, WKWebView dev/prod
  split, Stop button, passive mode, C ABI, all app-core classes documented
- **Design snapshot** — `docs/design-snapshots/2026-04-06-current-architecture.md`
  (first snapshot ever for this project)
- **ADR-012–015** — performSelectorOnMainThread:, PTY ECHO flag, InteractionDetector
  timer approach, AnsiStripper dev-only pattern
- **write-blog skill** updated with mandatory-rules reference and heading rules
- **Knowledge garden** — two submissions: PTY ECHO gotcha, inject-session-instructions
  technique

## State Right Now

Plans 1–5 complete. Native binary (~0.020s startup) spawns `claude` CLI in a PTY,
routes ANSI-stripped output to NSTextView, NSTextField input, PASSIVE mode with
Stop button working. All blog entries clean and style-guide compliant.

**Plan 6 (.app bundle) is the immediate next step** — prerequisite for WKWebView
and removes the `-Dcccli.dylib.path` workaround.

## Immediate Next Step

Write Plan 6 implementation plan: `docs/superpowers/plans/2026-04-06-app-bundle.md`

Key tasks: `.app` directory structure, `@rpath` for dylib, `Info.plist`,
codesigning, Maven exec plugin to assemble bundle post-native-image.

## Open Questions / Blockers

- WKWebView codesigning entitlements needed for WebKit subprocess IPC in .app
- NSTextField → NSTextView for multi-line input (deferred, not blocking)
- Four untracked plan files in `docs/superpowers/plans/` — not urgent

## References

| Context | Where | Retrieve with |
|---------|-------|---------------|
| Design state | `docs/design-snapshots/2026-04-06-current-architecture.md` | `cat` that file |
| Architecture decisions | `DECISIONS.md` (ADR-001 to ADR-015) | `cat` that file |
| Current design | `DESIGN.md` | `cat` that file |
| Latest blog entry | `blog/2026-04-05-01-pty-claude-passive.md` | `cat` that file |
| Technical gotchas | `~/claude/knowledge-garden/GARDEN.md` | index only; detail on demand |
| AppKit pitfalls | `docs/APPKIT_PITFALLS.md` | read before any ObjC debugging |
| Plans | `docs/superpowers/plans/` | `ls` to list |
