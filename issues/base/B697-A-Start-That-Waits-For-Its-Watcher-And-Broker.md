# B697 — A Start That Waits For Its Watcher And Broker

```json
{
  "$schema": "issue-1.json",
  "id": "B697",
  "title": "A Start That Waits For Its Watcher And Broker",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 51,
  "blockedBy": ["B384"],
  "covers": "A start waits for its clearance watcher and its vault broker as it waits for its gate and relay, and one that does not come up stops the start with 70 and its cleanup.",
  "openQuestions": 0
}
```

**Why this size.** The same wait [B384](README.md#b384) built, at two more helpers, and the test classes whose starts
need a stand-in for them.

Filed 2026-11-13 from [B384](README.md#b384): its "What must be true" names every helper, but its plan named only the
gate and the relay. On a start, the clearance watcher only warns when it does not come up, and a broker whose token
never appears lets the start go on. A resume already waits for all four.

**What must be true.** A start whose watcher or broker does not record its pid within the helpers' patience stops
that helper, cleans up and ends 70, naming the helper.

## Plan

- `TaskLaunch`: the watcher's and the broker's start through `HelperPid.await`, as `startGate` and `startRelay`.
- Tests: the starts that run a watcher or a broker use [B384](README.md#b384)'s `HelperStandIn`; red first, a watcher and a broker
  that never record their pid each end the start 70.

## Acceptance

- A start with a watcher or broker that never comes up ends 70 with nothing left; a test fails while it goes on.
