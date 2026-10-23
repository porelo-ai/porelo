# B261 — A Stop Of Everything Says What Still Runs

**Status:** Backlog.
**Size:** S - the stop checks each container's state after stopping it, and names a survivor; tests.
**Estimate:** 90 min, no like issue measured.

**What must be true.** `porelo task stop --all`, `porelo panic` and `porelo task stop TASK` end 0 only when no stopped
task's container still runs. A container that survives its stop is named on standard error, and the command ends 70,
as a surviving helper does today.

## Why

A host's `down` runs `porelo task stop --all` and goes on to `vault clear` on 0 ([B258](README.md#b258)).
`Podman.stop` discards the exit code of `podman stop`, and the result counts only surviving helpers. A review
reproduced `Podman.stop` returning normally after an exit of 125. So a container whose stop failed would be reported
as stopped while it still runs. `vault clear` refusing past a running task with a token stays the guard behind it.

## To be read before building

- `TaskControl.stop` and whatever it checks after the container's stop; only `Podman.stop` and `TaskPanic` are read.
- Whether the daemon's `Panic` and `Stop` answer the same, since they share `TaskPanic` and `TaskControl`.
