# B577 — An Online Task's Pushed Commit Waits On Nobody

```json
{
  "$schema": "issue-1.json",
  "id": "B577",
  "title": "An Online Task's Pushed Commit Waits On Nobody",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 29,
  "blockedBy": [],
  "covers": "An online task whose push passed the gate is never shown as waiting on the person; its push appears as the task's."
}
```

**Why this size.** the task's waiting reason, what Needs you is told, and whose account a task pushes as.

Filed 2026-11-09 under ST290, from the fourth move.

**What must be true.** In class `online`, a task whose push passed the gate to the host's forge is never shown as
waiting on the person for that commit: no "Waits on you: its commit waits at the gate", nothing in *Needs you* for it,
and what porelo reports agrees with the gate's own "Nothing is waiting". A push a task makes appears on the forge as
the task's, not as the person's account.

## Plan

Read in this host stack: an online task retains an incoming ref, and `TaskInventory` reads it with a gatekeeping
gate, so it counts work the online gate's own waiting list excludes. `waitingFor` reads a clearance marker;
this checkout has no separate attention-item implementation.

- Find where the "commit waits at the gate" reason and its *Needs you* item are made for an online task, and derive
  both from the gate's waiting list alone.
- Verify the pusher against the host tool's task-token and person-token paths ([B547](README.md#b547)); use the
  task account's existing token, and record where the person's lent token is used.
- Red first: an online task whose push passed reports no waiting reason and no attention item.

## As built

`TaskInventory` asks `GitGate.pending()` with the task's recorded class, taking the container label before its
sidecar. Its cache distinguishes mirror and gate mode. The same result supplies the review count and, for a
running task, the gate waiting reason. An ordinary online ref supplies neither. Only the task's recorded ref
belongs to it: a name ending in `-rescued` may name another task. Rescued work stays in the gate's review queue.
An existing clearance question keeps its own reason, and a stopped task stays stopped without a question.

The daemon's List and Watch answers serialize those same task values. This checkout contains no attention-item
implementation: the daemon's existing review signals are corrected here, but the Needs you rendering cannot
be tested in this repository.

The host already issues `porelo-task-<task>` tokens for `porelo-<login>` through `porelo-host task start`.
`TaskLaunch` keeps that token in the task's vault entries; its certified host gate selects `--host-push`, whose
`HostPush` reads the task token at each push and refuses if it is missing. The host's judge checks the authenticated
pusher; it does not change the account. The person's lent token is used by the separate generic forwarding and
person-command path, including explicit approval. No token kind, sudo verb, or production credential-path change
is needed on this stack. Actual forge attribution remains to be checked on a host.

Unit regressions cover passed online refs, held guarded/offline refs and clearing them, mixed recorded classes in
one mirror, clearance questions, stopped-task sidecar fallback, and independent tasks whose names end in
`-rescued`. Six retained cases failed on behavior before the implementation; two clearance cases failed after a
deliberately inverted marker condition, and two name-collision cases reproduced the review finding before its fix.

The final focused run passed all 56 app cases, including all 10 new inventory cases, and all four daemon change
comparison cases. The requested selector ran 80 gate cases: 58 passed and 22 could not bind sockets in the sandbox.
Two daemon socket cases stopped at the existing socket-directory ownership guard. No VM, acceptance suite, or
real forge attribution check ran here.
