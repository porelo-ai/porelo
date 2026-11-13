# B694 — A Detached Start Said As It Waits

```json
{
  "$schema": "issue-1.json",
  "id": "B694",
  "title": "A Detached Start Said As It Waits",
  "state": "Backlog",
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
