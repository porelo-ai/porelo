# B733 — One Unverifiable Task Refuses Only Its Own Gate

```json
{
  "$schema": "issue-1.json",
  "id": "B733",
  "title": "One Unverifiable Task Refuses Only Its Own Gate",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 10,
  "blockedBy": [],
  "covers": "A retained task name whose ownership cannot be verified refuses only a lookup of that task, never another task's gate; and a project clear leaves a running task's records whole, so it cannot make one unverifiable.",
  "openQuestions": 0
}
```

**Why this size.** One condition in `TaskHistory.find`, one guard in the project clear, and tests for both. Estimate: 45 minutes (Core's median for S).

Found on the test VM in a walk: a task of a project was still running, and a forced `project clear` of that project did not take it. A new task of the same project then failed to start. Its gate refused with "historical ownership cannot verify the surviving task files; list tasks before acting", from `TaskHistory.find` through `GateServeCommand.signing`, and the workspace could not reach the gate. Only removing both tasks by hand let a new start work.

Read at the stack's head: `TaskHistory.find(paths, project, task)` walks every retained name and throws for the first one whose identity record is missing while a reservation and a task record or container state survive. It throws before it compares that name's project and task with the ones asked for. So one such name refuses every lookup, and with it every task's gate. The reservation it holds already names that name's project and task. The likely way a name gets there is the clear: it takes the identity record of a task that still runs and leaves its task record or container state.

A second shape, with no task left running: a removed task's identity record said `RETIRED`, and its task-record
folder had been made again a few minutes after the removal, holding only an empty `.wake.lock`. A late wake made it,
and from then on `TaskHistory.find` refused every gate. [B610](README.md#b610)'s follow-up stops that wake: no lock and
no late wake makes a removed task's folders again. This issue keeps the gates of other tasks open whatever name is
left.

## Plan

- `TaskHistory.find`: a name whose ownership cannot be verified refuses only when the reservation kept for it names the project and task asked for. Otherwise it is skipped. A name with no reservation either is skipped, as before.
- The project clear: a running task's identity record, task record and container state are taken together or not at all. With `--force`, the task is stopped and removed through the removal path first. Without it, the clear names the running task and leaves its records whole.
- Red first: a lookup of task B is not refused by an unverifiable name of task A, and is still refused for A itself. A forced clear of a project with a running task leaves no name `TaskHistory.find` cannot verify.

## Acceptance

- With one unverifiable retained name, of a running task or a retired one, a new task of the same project starts and
  its gate signs.
- A lookup of the unverifiable task itself still refuses, with the same words.
- A forced project clear with a running task leaves either all of that task's records or none.
