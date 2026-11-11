# B620 — A Task Hands Back Its Result File

```json
{
  "$schema": "issue-1.json",
  "id": "B620",
  "title": "A Task Hands Back Its Result File",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "A task started with a result file's name hands that file's content back at its end, bounded at 1 MiB, kept on the machine until it is taken, and read by the window through the daemon."
}
```

**Why this size.** a start field, a writable result directory beside the mailbox, the copy at the end into the task's
durable record, one daemon method that answers the content or why there is none; a contract the window reads.

Filed 2026-11-11 as porelo's part of `porelo-project` ST319, "Discuss an idea with an agent in the planner".

**What must be true.** A task started with a result file's name, such as `idea.json`, finds a writable directory
`/run/porelo/result/` and is told so in its brief. When the task ends, by its `Done` or by its agent's exit, the file
is taken out of the task, at most 1 MiB, and kept in the task's durable record on the machine that ran it, also
after its container is removed. The window asks the daemon for it and gets the content, or why there is none:
`missing`, `too-large` (with its size), or `unreadable`. A path alone would mean nothing to the planner, which runs on
another machine.

How it is today, read in the code:

- No daemon method returns a file's content from a task. `Podman.copyOut` copies without a bound; `RunResult` (16 KiB,
  in the Start reply only) and `HostCentral` (1 MiB) bound what they read.
- The mailbox is a host directory mounted writable at `/run/porelo/mail`; a task's record under
  `$XDG_STATE/tasks/<container>/` (`done`) lasts until the next start of that name.
- The window learns of an end through `Watch` and `List` (`agentEnded`, `ended`).

## Plan

- A start field `result` (a file name, no directory part), on the wire, in `TaskLaunch.Request` and as
  `porelo task start --result <name>`; the task gets `/run/porelo/result/` mounted writable and the name in its brief.
- At the end, before the container goes, the host takes `<name>` from that host directory: a regular file, no link,
  read up to 1 MiB + 1, and writes it, or its reason, as `result` in the task's record.
- `Task` and `AgentEnded` carry `result: {name, state, bytes}`; a method `Result(task)` answers the content (UTF-8 text)
  and its state; the record goes with the task's next start or its removal.
- Red first: a `TaskResultTest` over a stand-in task: a file is handed back whole, one over 1 MiB as `too-large`, a
  symbolic link as `unreadable`, none as `missing`, and it is still answered after the container is removed.
- `doc/commands.md` and the Tasks1 interface's page. Changelog entry under *Added*.

## Acceptance

- The tests above, seen red first.
- With [B619](README.md#b619), a discussion task on a rented host writes `idea.json`, ends, and the window reads its
  content from the person's computer.
