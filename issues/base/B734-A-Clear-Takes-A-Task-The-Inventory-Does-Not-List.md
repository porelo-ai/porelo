# B734 — A Clear Takes A Task The Inventory Does Not List

```json
{
  "$schema": "issue-1.json",
  "id": "B734",
  "title": "A Clear Takes A Task The Inventory Does Not List",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 50,
  "blockedBy": [],
  "covers": "A project clear finds a container of the project that the task inventory does not list, such as a running task whose ownership cannot be verified: it names it and refuses, or with force removes it and takes its records whole.",
  "openQuestions": 0
}
```

**Why this size.** One more source of tasks in `ProjectDeletion.prepare`, its removal in the forced path, and tests
in `ClearingTest`. Estimate: 45 minutes (Core's median for S).

**What must be true.** A project clear never leaves a container of the project behind because the task inventory
did not list it. Without force it names such a container and removes nothing; with force it stops and removes it and
takes its records together, so no name is left whose ownership cannot be verified.

Read at the stack's head: `ProjectDeletion.prepare` takes the project's tasks from `TaskInventory.tasks()`, which
goes through `Podman.poreloTasks()`. That lists only containers whose id matches the recorded one. A running task
whose identity record is already missing, or whose recorded id no longer matches, is in `Podman.taskCandidates()`
but not in the inventory. A forced clear then removes the project around it and leaves its container, its
reservation and its task record. Since the gate's lookup skips another task's unverifiable name, this no longer
stops another start, but the container keeps running and the name stays taken.

## Plan

- `ProjectDeletion.prepare`: beside the inventory's tasks, read `Podman.taskCandidates()` and keep those whose
  project label names the project and which the inventory did not list. Without force they count as running and
  are named in the refusal; the preview lists them under what it removes.
- The forced path: each such container is stopped and removed by its id, then its runtime folder, task record and
  reservation are taken together, the same way as for a listed task. A container that cannot be removed fails the
  clear before anything else is touched.
- First red, in `ClearingTest`: a forced clear of a project with a running container of its label whose identity
  record is missing leaves that container and its task record; it must leave neither.

## Acceptance

- A forced clear with an unlisted running container of the project leaves no container of the project's label and
  no task record, reservation or runtime folder of that name.
- A clear without force names that container and changes nothing.
- A container of another project's label is never touched.
