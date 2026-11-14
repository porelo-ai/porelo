# B620 — A Task Hands Back Its Result File

```json
{
  "$schema": "issue-1.json",
  "id": "B620",
  "title": "A Task Hands Back Its Result File",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
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
  and its state; the record goes with the task's next start, and outlives its removal until then.
- Red first: a `TaskResultTest` over a stand-in task: a file is handed back whole, one over 1 MiB as `too-large`, a
  symbolic link as `unreadable`, none as `missing`, and it is still answered after the container is removed.
- `doc/commands.md` and the Tasks1 interface's page. Changelog entry under *Added*.

## Acceptance

- The tests above, seen red first.
- A discussion task, reading every repository of its project, on a rented host writes `idea.json`, ends, and the window reads its
  content from the person's computer.

## As built

- `TaskResult` (app-base) keeps a task's result under `$XDG_STATE/results/<container>/`, owner-only, outside the
  task's record: the name, the box (`box/`, `rwxrwxrwx`, mounted at `/run/porelo/result` only when a start named a
  file), and what was taken (`taken.json` with name, state and bytes; `taken` with the text, only when handed). Kept
  outside `tasks/<container>/` on purpose: the task's record goes with its removal, and a record still there refuses
  the next start of the name.
- Taking moves the file out of the box into the owner-only directory first, reads it there without following a link,
  at most 1 MiB + 1, and moves it back unless the agent wrote a new one meanwhile: a link or a pipe swapped in during
  the read is never followed or opened. Too large reports the size from the file's attributes. Text that is not UTF-8,
  or holds a control character other than tab, line feed and carriage return, is `unreadable`: such a character is
  written six bytes long on the wire, and 1 MiB of them would pass the 4 MiB a varlink message may have.
- Taken at Done (daemon), at the end of a prompt run (`TaskLaunch`), once by a listing when the agent ended or the
  container stopped (`TaskInventory`), and at the removal (`TaskControl`). A file handed back is kept as it was; any
  other state is taken again at the next end.
- Plan changed on one point: the removal takes the file once more and keeps what was taken, rather than deleting it,
  so the record is still answered after a prompt run with `rm` removed the task. A start that brings the task back
  clears what was taken and keeps the name and the box; a new creation of the name forgets everything; `prune`
  removes a removed task's record once it settled; a start that failed before its container existed forgets it.
- Contract: `Start.result`, `type TaskResult (name, state, bytes)` on `Task.result` and `AgentEnded.result`,
  `method Result(task) -> (name, state, bytes, content: ?string)`, and `error NoResultNamed(task)` for a task started
  without `result`, beside `NoSuchTask`. Before anything was taken `Result` answers `missing` with bytes 0. Not
  offered through a task's MCP start, and `Result` is not among the methods a task's endpoint may carry.
- `doc/reach.md` and `Reach` gained a row under *Produce* for the file a task hands back.
- After agy's read-only review (gemini-4-argon-high), each finding checked against the code:
  - `Result` and `TaskResult.answer` take a task's name only (`TaskResult.named`): one path element, no `..`. A name
    with a directory part was enough before, by its `porelo-` prefix, to read a directory beside the results.
  - The taken file is put back by a hard link, which refuses a file the agent wrote meanwhile. The rename before
    replaced it.
  - A sibling task's MCP `List` without Logs or Tail no longer shows a task's `result`, as it shows nothing else its
    run said.
  - Rejected, by design: a file left in the box from an earlier run is that run's file at the next run's end. A take
    after `too-large` or `unreadable` takes again.
- From Machines' VM round on fd07b3f4 (2026-11-12), three points, each checked against the contract:
  - `porelo task status --json` gave no `result`, though `List` and `Result` carry it. It now adds `result` (name,
    state, bytes) for a task started with one.
    `TaskStatusIdentityTest.statusJsonSaysWhatWasTakenOfTheFileATaskHandsBack` was red first.
  - `Tasks1.Result` takes the container name, not the short name. That is as designed: like `Stop`, `Remove`,
    `Tail` and every other method naming one task, it takes what `List` answers as `name`.
  - The container stays up after `Done`. That is as designed: `Done` records how the work ended and "neither stops
    nor removes the container" (its contract). The file is taken at `Done` all the same.
