# B694 — A Detached Start Said As It Waits

```json
{
  "$schema": "issue-1.json",
  "id": "B694",
  "title": "A Detached Start Said As It Waits",
  "state": "Review",
  "size": "XS",
  "priority": "Low",
  "rank": 48,
  "blockedBy": [],
  "covers": "The commands page says `task start --detach` returns once the task runs, without a session, rather than that it hands over nothing.",
  "openQuestions": 0
}
```

**Why this size.** One sentence in `doc/commands.md`, checked against what the start does.

Filed 2026-11-13 from [B201](README.md#b201): `doc/commands.md` says "`--detach` hands over nothing", but a detached
start waits for the image, the container, the workspace and its helpers before it returns, as [B201](README.md#b201)'s own reading
of the start says.

**What must be true.** The page says what a detached start waits for and that it opens no session.

## Plan

- `doc/commands.md`, the `task start` entry: "`--detach` returns once the task runs, without a session."
- Read `TaskRunCommand`'s detached path first, and say only what it waits for.

## Acceptance

- The sentence matches the detached path; `CommandsDocumentedTest` stays green.

## Built

Read in `TaskRunCommand` first. A new task's detached start returns from the launch's `after.started` (`TaskLaunch`,
`:4066`), which is reached only once the image is built, the container made and started and its helpers up. A stopped
task brought back with `--detach` returns once `startExisting` has resumed it, helpers included. Neither opens a
session.

- `doc/commands.md`, the `task start` entry: "`--detach` returns once the task runs, its image built and its container
  and helpers up, and opens no session", in place of "`--detach` hands over nothing".
- No new test: the sentence only read wrong. `CommandsDocumentedTest` passes on the build box
  (`buildbox-porelo-a944a6e2c453-20261113T182807Z.log`, 1 test, 0 skipped).
