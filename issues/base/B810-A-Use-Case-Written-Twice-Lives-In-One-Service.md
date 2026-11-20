# B810 — A Use Case Written Twice Lives In One Service

```json
{ "$schema": "issue-1.json", "id": "B810", "title": "A Use Case Written Twice Lives In One Service",
  "state": "Accepted", "size": "L", "priority": "High", "rank": 8, "blockedBy": [],
  "covers": "Reject, Approve, the steps before a task's launch, and Say/Tell, each written once in the daemon and once in the CLI, move into one service each that both call; Reject's drift between the two is settled first" }
```

**Why this size.** Four use cases, each in two entry points, one service each, and the drift settled; the first part, since the drift is a defect.

**What must be true.** An entry point of porelo (a picocli command, a daemon method, the gate's HTTP handler) only
checks its input, maps it to a record, calls one service method and maps the answer back; the service holds the use
case (locks, revisions, rules, storing, events) without knowing the transport and is tested without it. Behavior
stays as it is, except Reject's drift, which is settled to one answer. Read in porelo at f0de69ecc, by reading only.

## Plan

- **Reject, first**: `GateMethods` Reject refuses work already upstream, then calls `gate.reject(name, false, true)`,
  logs its own event and writes the person's note; `GateRejectCommand` calls `gate.reject(name, upstreamHas, force)`
  and logs "already upstream" where that applies. One `GateRejecting` service in `app-gate` takes the CLI's
  parameters; the daemon maps to them. The test that goes red first: the daemon's Reject of work already upstream
  answers as the CLI's does.
- **Approve**: `GateMethods` Approve (`gate.approve`, then `EventLog.record`) and `GateApproveCommand` call one service.
- **Start's steps before the launch**: `TaskMethods` Start (`ProjectTaking.take`, `StartCheck.unknownHost` and
  `undeclared`, then existing or new task) and `TaskRunCommand` (project, repository, task name, `TaskBase`) call
  one service in `app-task` that ends in `TaskLaunch`.
- **Say/Tell**: the daemon methods and `TalkSayCommand`/`TalkTellCommand` call one service.
- Each service has a unit test over fakes. The entry points' tests are reduced to the mapping.

## To be checked

- Which of Reject's two answers is right for work already upstream: read both paths and their tests before building.
