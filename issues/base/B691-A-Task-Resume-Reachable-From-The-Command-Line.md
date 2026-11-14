# B691 — A Task Resume Reachable From The Command Line

```json
{
  "$schema": "issue-1.json",
  "id": "B691",
  "title": "A Task Resume Reachable From The Command Line",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 45,
  "blockedBy": [],
  "covers": "`task list` and the commands page send a task from before a restart to `porelo task attach`, a command that runs, instead of the removed `task resume`.",
  "openQuestions": 0
}
```

**Why this size.** One hint, one sentence of a page and one test.

Filed 2026-11-13 from checking task names by what the commands do: walking the command tree for every task command showed `TaskResumeCommand`
in no group. `task attach` calls its `resume` directly, but no command line reaches it.

**What must be true.** The hint in `task list` for a task from before a restart names a command that runs:
`porelo task attach <name>`, which offers to copy the workspace out and then to remove the task. `doc/commands.md`
names only `task attach` for it.

**Decided 2026-11-13 (lead), against the first plan.** `task resume` is not registered again. The lifecycle cut
removed it on purpose: attaching is what brings a stopped task back, and `PanicCommandTest` and `TaskRunCommandTest`
pin that no advice names `task resume`. The fault was the hint and the page that still named it, not the missing
registration. `TaskResumeCommand` stays as what `task attach` calls.

## Plan

- `TaskInventory`: the restart hint names `porelo task attach <name>` in place of `porelo task resume <name>`.
- `doc/commands.md`: the passage on a task from before a restart names `task attach` alone.
- Red first: a test in `TaskLifecycleCommandsTest` lists such a task and expects the attach hint and no
  `task resume`.

## Acceptance

- `task list` on a task from before a restart names `porelo task attach <name>` and never `task resume`; the test
  fails on the old hint.

## Built

`TaskInventory`'s start detail for a task from before a restart now ends "or 'porelo task attach <name>' at a terminal
offers both". `doc/commands.md` names `task attach` alone for such a task. `task resume` stays unregistered, as
decided above.

Test: `TaskLifecycleCommandsTest.theListSendsATaskFromBeforeARestartToACommandThatRuns` reads the task's start action
and detail from the inventory. Red on the base: it fails on the attach hint (94 tests, 1 failure). Green on the
change: 94 tests, 0 failures.
