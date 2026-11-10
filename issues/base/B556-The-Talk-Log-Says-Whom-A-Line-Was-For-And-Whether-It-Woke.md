# [B556](README.md#b556) — The Talk Log Says Whom A Line Was For And Whether It Woke

**Status:** Implementing, since 2026-11-10. Filed 2026-11-09 for the messenger's part of the automated rehearsal, which judges a person's room
line in each task; for after the move.
**Size:** S - three fields more in an answer porelo already gives, read from what it already keeps.
**Blocked by:** -.

**What must be true.** `porelo talk log <task> --json` answers, for each message that reached the task, whom it was
meant for and whether it woke the task's agent: the names in `to`, `via` (`room` or `direct`) and `all` as the message
carried them, and `woken` (true when the agent was told of it, false when it was kept without waking it). A person's
line is matched to the client's own by its message id, which already carries the transport's event id. Nothing a
message says is shown, as now.

## Why

A rehearsal judges each task by porelo's own record: a line delivered to every task, but answered only by the one it
names. Today `talk log` prints what happened to a message and with whom, never whom it was meant for nor whether the
agent was woken; the `to`, `via` and `all` stand only in the message's own file, and the wake only in the mailbox's
`wake-announced` beside the record. A judge without them can only read the answers in the room.

## Plan

- `talk log --json`: one object per record line, as the table's columns, plus `to`, `via`, `all` from the delivered
  message's metadata and `woken` from `wake-announced`. The table stays as it is.
- Red first: a person's room line naming one task reads `to` that task and `woken` false in another task's log; a line
  marked all reads `all` true and `woken` true in each.
