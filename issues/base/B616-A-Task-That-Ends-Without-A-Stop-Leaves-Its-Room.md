# B616 — A Task That Ends Without A Stop Leaves Its Room

```json
{
  "$schema": "issue-1.json",
  "id": "B616",
  "title": "A Task That Ends Without A Stop Leaves Its Room",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 20,
  "blockedBy": ["B615"],
  "covers": "A task whose container ends without `task stop` (it exits, crashes, or the machine was off) leaves its room on a central homeserver as a stopped task does: `porelod` finds every enrolled task that is not running, at its start, while it runs and when the vault opens, and has it leave."
}
```

**Why this size.** a sweep in `porelod` over the tasks enrolled at each transport against the containers that run,
run at the daemon's start, on a timer and when the vault opens; [B615](README.md#b615)'s leave reused; unit tests with
a stand-in transport, a fake podman and a clock.

Filed 2026-11-11 from [B615](README.md#b615)'s open point. Accepted by the operator 2026-11-11.

**What must be true.** A task that ends without `porelo task stop` stays a member of its project's room today. That
covers a container that exits on its own, one that crashes, and a machine that was turned off or restarted. "@room"
and "All agents" then wait for an answer from it. Such a task leaves the room as a stopped task does after
[B615](README.md#b615): only on a central homeserver (a non-empty `PORELO_MATRIX_LOGIN`), and its next start brings it
back.

## How porelo notices

- **No event to rely on.** A container's end is reported by no hook that holds the vault. The poststop hook runs
  without the person's vault, and `retire` needs the task's secrets from it. A machine that was off sends nothing at
  all.
- **So `porelod` compares what is enrolled with what runs.** A task is enrolled at a transport while its secrets
  (`task/<container>`) or its entry in the transport's addresses file are kept. For every such task whose container is
  not running, `porelod` runs [B615](README.md#b615)'s leave. A left task's entries are released, so the next sweep
  finds it no more, and a sweep that runs twice does nothing twice.
- **When it sweeps.**
  - At the daemon's start. This covers the machine that was off: the first start after it sweeps.
  - On a timer while it runs, of the order of a minute, read from the same container list `Watch` reads.
  - When the vault opens. A locked vault reads no secret, so the sweep leaves nothing then and is run again when the
    vault opens.
- **When the machine was off.** The room lists the tasks until this machine's `porelod` starts again; nothing can take
  them out while the machine is off. A rehearsal or a person who counts running tasks in that time is told by the
  members' answer of [B615](README.md#b615)'s transport no better than by porelo.
- A task stopped by `porelo panic` is swept the same way, so a panic itself still asks no transport.

## Plan

- `TaskConversations.sweep(context)`: the enrolled tasks per project and transport, the containers that run, and
  `leave` for each enrolled one that does not run. Failures are logged once per task and retried at the next sweep.
- `porelod` runs it at start, on a timer, and after the vault opens.
- Red first: a stand-in transport with a central login, a task enrolled and its container gone: one sweep retires it
  once; a running task is not touched; the account's own homeserver is not asked; a locked vault retires nothing and
  the sweep after it opens does.
- `doc/transports.md`, the `retire` row; changelog under *Fixed*.
