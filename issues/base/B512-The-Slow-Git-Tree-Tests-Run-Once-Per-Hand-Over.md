# [B512](README.md#b512) — The Slow Git Tree Tests Run Once Per Hand-Over

**Status:** Backlog. Filed 2026-11-08 at the operator's word; built after [B507](README.md#b507).
**Size:** S - a JUnit tag on the slow classes, a Maven profile that the whole suite turns on, and the Shared Area's
"touched modules" run left without it.

**What must be true.** A run of the touched modules during the work (`-pl <changed> -amd`) skips Organization Git
Tree's slow tests unless the change touches `organization/`. The whole suite before a hand-over runs them all, as now,
and so does CI.

## Why

The operator decided on 2026-11-08 that a commit during the work runs only the tests it changed and those of the
modules built on what it changed, and the whole suite once before a hand-over. In the whole suite of 2026-11-08
(`clean install`, 13:48 min), Organization Git Tree took 4:17. Three classes take most of it:

- `GitTreeWritesContractTest`: 249 s;
- `GitTreeWritesTest`: 165 s;
- `GitTreeStoreTest`: 102 s.

`-amd` pulls them in through `core` or `wire` even when nothing in `organization/` changed.

## Plan

- **Analysis first.** Find what makes the three slow: each test making a git repository from scratch, or the
  `WritesContract` cases each running a full store. Speed them up where that does not change what they prove.
- **The tag.** Tag whatever stays slow `@Tag("slow")`.
- **The profile.** Surefire excludes the tag by default. A profile `whole`, which the hand-over's suite and CI turn on,
  includes it.
- **CI and the release** keep running every test: their commands name the profile.
- **Red first:** a test of the build that fails if a class tagged slow is not run by `-Pwhole`.
