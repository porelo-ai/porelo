# B146 — One Stop Of Every Task On A Node

**Status:** Review.
**Size:** M - a command, a daemon method and a journal across `app-task` and `daemon`; built and pushed.

**What must be true.** One command stops every task of an account on a machine at once, and the stop is journalled.

## Why

An incident needs everything stopped now; today each task is stopped by name.

**Guideline points it answers:** AISVS 9.1.3, 12.4.3. From a review of security guidelines, 2026-10-09.

## The shape

`sokar task stop --all`, also as a daemon method; each task's work kept as `task stop` keeps it; one journal line with who and when.

## Built

- `sokar task stop --all`, the same operation as `sokar panic`, refused beside a task name; each task stopped as
  `task stop` stops it (`TaskPanic`).
- One line per stop of everything in `stops.log` in the state directory: time, user, host, `via=cli|daemon`, what
  stopped, what survived - written even when nothing ran; a failed write never holds the stop back
  (`PanicCommandTest`, seen red first).
- The daemon's `Panic` answers the line as `journalled` (`SokarDaemonTest`).
- Tokens stay in the vault, as `task stop` keeps them: `doc/decisions.md`, "A stop of every task keeps its tokens".

## Plan

- Built and on `origin/main` since 2026-10-11 ("Stop every task with task stop --all as panic does, and journal each
  stop of everything with who and when").
- Left: the operator's test - `sokar task stop --all` with two tasks running, then the line in `stops.log`.
- Revoking the tokens is decided against: the stop keeps them, as `task stop` does; revoking stays `vault revoke`
  per task (`doc/decisions.md`).

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- None.
