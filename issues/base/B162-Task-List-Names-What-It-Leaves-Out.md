# B162 — Task List Names What It Leaves Out

**Status:** Review.
**Size:** M - the CLI's list, status and logs, and the daemon's `List` and `Watch` with a contract field.

**What must be true.** `porelo task list` never says "No tasks." while containers named like tasks are there: it names
the ones it does not list, and why.

## Why

A task is listed only when its container's live id is the one Porelo recorded when it made it (`container.id` in the
task's state); a container named like a task with no such id is not one, and nothing of Porelo's acts on it. That rule
came on 2026-10-04, and nothing records an id for a task made before it. Such a task, stopped, with its `task.json`
and `resume.json` still there, was left out without a word: the list said "No tasks.", which read as "nothing to lose",
and a container prune removed the work in five of them unseen.

## Built so far

- `task list` names every container named like a task that it does not list, says that Porelo holds no container id for
  it and that removing it removes what is in it; with nothing listed it says "No tasks Porelo acts on." instead of
  "No tasks." (`TaskLifecycleCommandsTest`, seen red first).

- `task status` and `task logs` on such a container say what it is - named like a task, no container id, removing it
  removes what is in it, `podman cp` saves its workspace - instead of "there is no task called"
  (`TaskLifecycleCommandsTest`, seen red first).
- The daemon's `List` and `Watch` answer `unrecognized` beside `tasks`, the same names, from the same code as the CLI
  (`TaskInventory.unrecognized`); a change in them is a change for `Watch` (`PoreloDaemonTest`, seen red first).

## Plan

Built and on main, nothing left but the operator's test. One source, `TaskInventory.unrecognized`
(`apps/app-model/.../TaskInventory.java`), feeds `TaskListCommand`, `TaskStatusCommand`, `TaskLogsCommand` and the
daemon's `List` and `Watch` (`daemon/.../TaskMethods.java`, `unrecognized` in `15-task.varlink`);
`TaskLifecycleCommandsTest` and `PoreloDaemonTest` were seen red first.

## Decided

- **No adopting** (2026-10-10): a container without the id Porelo recorded when it made it is never taken on as a task,
  not even once by a command a person runs; the 2026-10-04 rule stays closed. `task list`, `task status` and the
  daemon's list say what such a container is and that removing it removes what is in it. Saving its workspace is the
  person's own `podman cp`.
