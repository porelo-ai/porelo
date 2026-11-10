# [B556](README.md#b556) — The Talk Log Says Whom A Line Was For And Whether It Woke

<<<<<<< HEAD
**Status:** Implementing, since 2026-11-10. Filed 2026-11-09 for the messenger's part of the automated rehearsal, which judges a person's room
=======
**Status:** Review. Filed 2026-11-09 for the messenger's part of the automated rehearsal, which judges a person's room
>>>>>>> 10151d97e (Say in the talk log whom a line was for and whether it woke)
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

## As built

`TalkLogCommand` adds `--json`: one object per record line in the original order, with the table's `at`, `event`,
`peer`, `message` and `detail`, the record's `id`, and `to`, `via`, `all` and `woken`. Audience metadata is read from
the retained message, including one moved from `inbox/new` to `inbox/cur`; wake status comes only from this task's
`wake-announced`, independently of whether that message remains. `messageMissing: true` marks a gone message and
leaves `to`, `via` and `all` null. Nothing is written into the mailbox and no message text is printed. The table
renderer is unchanged.

Retained files are matched by the recorded message id when present, so reuse of a file name cannot substitute a
new message's audience for an old one. Audience fields are validated before rendering: recipient names, a room or
direct route, and a boolean all flag; malformed metadata refuses without printing its content.

Command tests were seen red for a named room line delivered without waking another task, an all-agents line in
each task's log, a missing message, and a direct line without a recorded wake. The exact table test was seen red
with a deliberate header change, which was restored. Tests use isolated mailbox files and a fake machine runner.
The code review's two findings were reproduced with failing tests for reused file names and malformed audience
metadata, then fixed; neither finding required a new mailbox record.
