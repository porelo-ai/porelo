# [B532](README.md#b532) — A Task Shown By Its Name, Not Its Container

**Status:** Backlog. Filed 2026-11-08 from the third move's findings 10 and 19 (the operator's decision: a task is
shown by the name given at its start, or short by its repository; the container name only in the details).
**Size:** M - three fields on the task row, the name recorded at start, the room's display taken from the repository's
agent; no container is renamed.

**What must be true.** Every client shows a task by the name a person gave it at its start, or else by its short name
from its repository (`pi` for `porelo-pi`), and the container name (`porelo-porelo-pi`) only among its details. In the
project's room a task appears as its agent's name (`Agent Pi`), so a line headed "Agent Pi:" names it.

## Plan

- Today: `TaskNames.fromRepository` strips `^(porelo-)+` from the repository's name, so `porelo-pi` becomes task `pi`
  and `ContainerName.of` makes `porelo-porelo-pi`. The task row (`TaskInventory.Task`, varlink `Task`) has `name` (the
  container) and a `task` derived back from it (`ContainerName.taskIn`); nothing records whether a person chose the
  name. `TransportLifecycle.agentName` makes "Agent Pi" from the task name, which a person-given name like `fix-login`
  turns into "Agent Fix-login".
- **Recorded at start:** `TaskProfile` (task.json) gains `named: ?string`, the name as the person gave it, written
  where `TaskLaunch` writes the profile; a start that took the default from the repository writes none.
- **The row** gains `short` (the task name within the project, as `task` today, kept), `named` (from the profile) and
  `display` (`named`, else `short`), each one edit in `TaskInventory.Task`/`asMap` and one in 15-task.varlink. `name`
  stays the container name, so every client that keys by it keeps working.
- **The room:** `TransportLifecycle.enroll` takes the task's agent (`TaskConversations.enroll` gets it from the
  selected agent at a start and from `TaskProfile.agent()` on resume) and shows "Agent " + the agent's name
  capitalized (`Agent Pi`). Where two running tasks of the project share that agent, the second shows "Agent Pi
  (<short>)" so a head still names one task. A line headed "Agent Pi:" reaches the task enrolled under that display.
- No container, state directory, mailbox, vault entry or socket is renamed: all of them are keyed by the container
  name (TaskPaths, MessagingPaths, `task/<container>/`, `/run/user/<uid>/porelo/<container>/`).
- Red first: a start with the default name records no `named` and shows `display: pi`; a start named `fix` shows
  `display: fix`; the room's display of a `pi` task named `fix` is "Agent Pi".

## Acceptance

- `porelo task list` and the daemon's `List()` show `display` per task as above; the room shows "Agent Pi" and a line
  "Agent Pi: hi" wakes that task. Seen in unit tests and once against a VM.

## Open questions

- "porelo-porelo-pi → porelo-pi": is the container itself to lose the doubled project name, or is it enough that no
  person sees it (this plan)? Renaming the container orphans every task's state and mail made before, and two projects
  could then make one container name.

## To be checked

- Which room heads the transport matches against the display today (the head's match is the transport's), with the
  room's other repository.

## After the plan reading

The build takes these answers; each harm gets a test seen red first.

- **One task owns "Agent Pi".** Choosing the display and enrolling happen under the addresses file's lock; a display
  held by an enrolled task of the project, running or stopped, is never handed to another until that task is removed.
  The second task of the agent is "Agent Pi (<short>)", its short part cut so the whole stays within 64.
- **No person takes an agent's display:** `talk join` and the transport's join refuse a display or person name that
  begins with "Agent " or "agent-", in any case.
- **Every resume path enrolls with the display** (`task resume`, `attach`, `start --restarted`, the daemon's
  `Start` of a kept task), not only `TaskLaunch.startExisting`; where the profile has no agent (a task from before, a
  shell task), the display is "Agent " + its short name.
- **`named` is the caller's word:** `TaskLaunch.Request` carries it only where the start was given a name (the CLI's
  positional, the daemon's `task` parameter before the MCP endpoint fills it in); `withLabel` and `withClearance` keep it.
- **The row:** `short: ?string`, `named: ?string`, `display: string` = `named`, else `short`, else the container name.
  `porelo task list` renders `display` (it renders `TaskInventory.Task` itself, not `asMap`), `task status` shows the
  container among the details; two tasks of one display in two projects are told apart by their project in the list,
  and `TaskTarget` matches this checkout's project first.
- The room's match of "Agent Pi (fix):" and of "Agent Pi:" against that display is the transport's; agreed with the
  transport's agent before building.
