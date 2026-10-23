# B261 — A Stop Of Everything Says What Still Runs

**Status:** Review: published since `0.5.1~snapshot.323`; green on the VM in its acceptance suite.
**Size:** M - grown from S: the removal that deleted its records over a running container, the shutdown's choice of
tasks, and four callers that ignored what survived came with it.
**Estimate:** 180 min from 90, no like issue measured.

**What must be true.** `porelo task stop --all`, `porelo panic` and `porelo task stop TASK` end 0 only when no stopped
task's container still runs. A container that survives its stop is named on standard error, and the command ends 70,
as a surviving helper does today.

## Why

A host's `down` runs `porelo task stop --all` and goes on to `vault clear` on 0 ([B258](README.md#b258)).
`Podman.stop` discards the exit code of `podman stop`, and the result counts only surviving helpers. A review
reproduced `Podman.stop` returning normally after an exit of 125. So a container whose stop failed would be reported
as stopped while it still runs. `vault clear` refusing past a running task with a token stays the guard behind it.

## Decided

- A container counts as stopped only when `podman container inspect` of its recorded id answers a terminal state
  (`exited`, `stopped`, `created`), and as removed only when it answers that there is no such container. `stopping`,
  `paused`, `restarting`, a running or unknown state and a failed inspection all count as still running.
- `task stop --all`, `panic` and the daemon's `Panic` choose the tasks to stop from the recorded task identities, not
  from what the runtime lists as up, so a container an earlier stop left `stopping`, or a renamed one, is stopped too.
- A removal that is not confirmed keeps every record of the task: its state, its durable container id, its session,
  secrets, conversation account and mailbox. They are what lets a later stop, `stop --all`, `vault clear` or removal
  find it. It answers `removed: false`, and `clear` then leaves the account's transport state alone.
- `task stop`, `task remove`, `clear`, `project unfollow` and an attached session's end name each survivor on standard
  error as `porelo: still running: …` and end 70.
- `Stop`'s `surviving: int` in the interface stays as it is, for [B262](README.md#b262).
