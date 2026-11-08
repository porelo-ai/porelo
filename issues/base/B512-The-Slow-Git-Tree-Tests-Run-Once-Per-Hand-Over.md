# [B512](README.md#b512) — The Slow Git Tree Tests Run Once Per Hand-Over

**Status:** Review.
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

## As built

- **Why they are slow is the code under test, not the fixtures.** One run of `GitTreeWritesContractTest` (79
  cases) started 236,185 processes, about 3,000 a case: `git verify-commit` 16,802 times, each with an `ssh-keygen
  find-principals` and an `ssh-keygen verify`, `git cat-file` 94,754, `git diff-tree` 34,252 and `git ls-tree`
  34,031, since every read walks and verifies the organization's history again, one process per question. The
  fixtures' own share was small: 329 `ssh-keygen -t ed25519` (4.6 s) and 1,747 `git init` (15.7 s) of 2,967 s of
  process time. A template repository or keys copied per test would save a few percent, so nothing was changed in
  the tests; making the reads cheaper is a change to `TreeRepository` and would be an issue of its own.
- **The tag and the profile.** `GitTreeWritesContractTest`, `GitTreeWritesTest` and `GitTreeStoreTest` carry
  `@Tag("slow")`. The root pom's surefire configuration has `<excludedGroups>${porelo.tests.excluded}</excludedGroups>`,
  the property `slow` by default and empty under the profile `whole`. A property of its own rather than surefire's
  `excludedGroups`, which failsafe reads too and which would leave out the acceptance suite's `@slow` scenarios.
- **Who turns it on:** CI's "Build and unit tests" (`./mvnw -B verify -s settings.xml -Pwhole`), `AGENTS.md`'s
  build line, `doc/build.md` and `CONTRIBUTING.md`. The release and deploy steps run with `-DskipTests` and the FFM
  check covers `core,shield,vault -am`, which holds no Git Tree test, so neither changed. A run of
  `-pl organization/git-tree` without the profile leaves them out too: a change to `organization/` names `-Pwhole`.
- **The guard:** `WholeSuiteProfileTest` in `apps/app` reads the root pom and fails when a build without `-Pwhole`
  no longer leaves `slow` out, when `-Pwhole` leaves any tag out, when the three classes are no longer tagged, or
  when a run of the whole reactor's tests in `build.yml` does not name `-Pwhole`. Red against the pom and
  `build.yml` before the change (both cases), and red again with `whole` setting the property to `slow`.
- **Measured, Organization Git Tree alone** (`-pl organization/git-tree test`, an otherwise idle machine): before,
  2:00 min and 626 tests (the three classes 116 s, 108 s and 40 s, run in parallel); after, 10.1 s and 235 tests;
  with `-Pwhole`, 1:54 min and 626 tests.
- **Measured, `-pl core -amd test`** (24 modules, one run each): without the profile 5:02 min and 5,535 tests, Git
  Tree 5.2 s; with `-Pwhole` 8:24 min and 5,926 tests, Git Tree 3:06 min. The 391 tests between them are the three
  classes' 204, 108 and 79. Both runs had the one `AgentChoiceTest` failure that depends on the listing order, and ran
  with `-Dmaven.test.failure.ignore=true` so the daemon's tests were timed too.
