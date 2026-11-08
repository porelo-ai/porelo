# [B502](README.md#b502) — What A Machine Lets A Task Reach

**Status:** Review.
**Size:** M - a method in the egress contract part (`25-egress`), its record, and a test reading the same code
`ReachDocumentTest` reads.

**What must be true.** A client of the daemon asks what a task on this machine can reach, hold, spend and produce -
the rows `doc/reach.md` states, with this machine's own values - and per task where a task widened or narrowed them,
each row saying whether it is a control or an accident-catcher.

## Plan

- `Reach(task?)` in `25-egress.varlink`, its values read off the code `doc/reach.md` is held to, so the page and the
  method cannot drift apart.
- Red first: a unit test that a widened task's row answers the widened value.
- As built: `Reach` in `app-egress` holds the page's rows in its words, every number from the constant the code
  enforces (`NftRuleset`, `VaultProxy`, `Limits`, `GitHttpServer.AT_ONCE`, `TaskCertificates.FILE`);
  `ReachDocumentTest` compares each table row - part, subject, bound, kind - with the page, and the five paths the
  page says are not bounded by subject and sentence, as a third kind `NOT_BOUNDED`. `EgressMethods` renders it as
  `ReachRow`, whose fields `InterfaceDescriptionTest` compares with the record.
- The page names the dropped credential headers in sorted order, since the constant is a set, and the gate's
  `4 requests at once` as a number.
- Per task: the run's granted names (`GrantedNames`, what `WidenTask` added and `NarrowTask` left) on the two name
  rows, the clearance mode on the undeclared-reach row, and a handed-in authority on the TLS row; per machine, a
  configured `task-ca-certificates.pem`.
- Not answered per task or per machine: a task's CPU limit is the project file's and recorded nowhere a task's
  state holds it; the gate scope's `1 GiB`, `256 tasks` and CPU weight are literal in the task area's
  `GateWiring.LIMITS`, which the egress area cannot read, so they stay words in the row.

## Acceptance

- Every row of `doc/reach.md` is answered, machine-wide and per task.
