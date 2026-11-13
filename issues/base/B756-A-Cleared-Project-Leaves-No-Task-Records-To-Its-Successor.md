# B756 — A Cleared Project Leaves No Task Records To Its Successor

```json
{
  "$schema": "issue-1.json",
  "id": "B756",
  "title": "A Cleared Project Leaves No Task Records To Its Successor",
  "state": "Backlog",
  "size": "S",
  "priority": "Low",
  "rank": 77,
  "blockedBy": [],
  "covers": "A project clear takes the hand-in record, the build record and the handed-back file of each of the project's task names, so a later project of the same name shows none of them as its own.",
  "openQuestions": 0
}
```

**Why this size.** S: one more step in `ProjectDeletion` with its preview line, one lookup in `TaskReservations`,
and tests in `ClearingTest`. Estimate: 45 minutes (Core's median for S).

**What was seen.** After `porelo project clear` and the project's repositories deleted, these stay, named by the
container (`porelo-<project>_<task>`): the hand-in record `$XDG_STATE_HOME/porelo/hand-ins/<container>.jsonl`, the
build record `$XDG_STATE_HOME/porelo/builds/<container>.jsonl` and the handed-back file under
`$XDG_STATE_HOME/porelo/results/<container>`. A later project of the same name makes tasks of the same container
names. Read in the code at the stack's head:

- `HandIns.record` reads every run of the name, and `porelo task files <task>` (`TaskFilesCommand`) lists the cleared
  project's hand-ins as the new task's history. `HandIns.deliveredForPush`, which a review's origins will read
  ([B505](README.md#b505)), reads every run too and would name a cleared project's hand-in as where a file of the new
  project came from. `HandIns.summary` and `TaskBuilds.current` filter by the run's container id, so the new task's
  own listing does not inherit them.
- `TaskResult.answer`, which the daemon's task result method reads, answers the cleared project's handed-back file
  for that name until the next start of the name makes its own box.

A task's own removal keeps these on purpose, so the next start of the same task sees its history. A project clear
says it clears what this machine kept for the project, and after it there is no project whose history this is.

Out of scope: the retired claim under `task-identities/` stays. A new start of the same project and task reads it as
its own retired name and starts fresh; without it, any record left under the name would refuse the start as taken,
and a vault prune would lose the scope it may prune. The mailbox of a retired name is [B690](README.md#b690). The
upstream record's lock file `upstream/.<key>.lock` stays as well: it holds nothing and is taken again by the next
record of that key.

**What must be true.** After a project clear, no task of a later project of the same name shows a hand-in, a build or
a handed-back file of the cleared project.

## Plan

- `TaskReservations`: a method, read under the name lock the clear already holds, that returns the container names
  whose retired claim names the project. The project comes from the claim, never from the container name.
- `ProjectDeletion.remove`: after the project's tasks are removed, for each of the project's task names (the
  removed ones and the retired ones), delete the hand-in record, the build record and the result directory
  (`TaskResult.forget`). The hand-in event stays in the event log, which keeps the audit. A delete that fails fails
  the clear, as a mirror's does.
- `ProjectDeletion.plan`: one line per name with such records, so the preview says what goes.
- First red, in `ClearingTest`: a project with a retired task name holding a hand-in record, a build record and a
  result is cleared; `HandIns.record`, the build record and `TaskResult.answer` for that name must be empty.

## Acceptance

- After a clear, `porelo task files` of a new task of the same name lists nothing from the cleared project, and the
  daemon's result method does not answer the cleared project's file; a test fails while either does.
- The retired claims stay, and a start of the same project and task after the clear succeeds.
- Records of another project's task names are not touched.
