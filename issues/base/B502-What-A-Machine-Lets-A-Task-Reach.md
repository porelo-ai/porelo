# [B502](README.md#b502) — What A Machine Lets A Task Reach

**Status:** Backlog.
**Size:** M - a method in the egress contract part (`25-egress`), its record, and a test reading the same code
`ReachDocumentTest` reads.

**What must be true.** A client of the daemon asks what a task on this machine can reach, hold, spend and produce -
the rows `doc/reach.md` states, with this machine's own values - and per task where a task widened or narrowed them,
each row saying whether it is a control or an accident-catcher.

## Plan

- `Reach(task?)` in `25-egress.varlink`, its values read off the code `doc/reach.md` is held to, so the page and the
  method cannot drift apart.
- Red first: a unit test that a widened task's row answers the widened value.

## Acceptance

- Every row of `doc/reach.md` is answered, machine-wide and per task.
