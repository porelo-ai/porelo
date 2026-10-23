# B268 — A Start Says Each Step And What It Waits On

**Status:** Accepted. Built together with [B265](README.md#b265) and [B267](README.md#b267).

**What must be true.** A task's start reports each of its steps in plain words as it takes it, says what it waits
on while it waits, and on a failure names the cause with the last error line of the dependency that failed. A start
waiting on the room's homeserver says so, and when the homeserver fails, its last error line is in the refusal. The
daemon carries the same steps, so the interface only shows them and writes no text of its own for them.

## Why

Seen in the operator's test on a rented host on 2026-10-23: while the first task started, the interface showed
"Running." with the last output line "snippet…". Nothing said that the start waited on the room's homeserver, which
had already failed five times. After the transport's three minutes the start ended with "Nothing ran, nothing was
created", and the cause was found only in the host's journal. The operator decided that the interface shows step
texts and writes none: a start's steps come from porelo, where the steps are defined.
