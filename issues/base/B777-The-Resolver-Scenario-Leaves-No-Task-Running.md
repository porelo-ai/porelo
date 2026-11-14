# B777 — The Resolver Scenario Leaves No Task Running

```json
{
  "$schema": "issue-1.json",
  "id": "B777",
  "title": "The Resolver Scenario Leaves No Task Running",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 134,
  "blockedBy": [],
  "covers": "The scenario \"the resolver is the system's dnsmasq, whatever PATH finds first\" removes its task when it ends, so a later run in the same account starts its own",
  "openQuestions": 0
}
```

**Why this size.** S: a cleanup in one scenario or the kit's step that starts a task left running. Estimate: 38
minutes (Core's median for S).

**What must be true.** A scenario that starts a task leaves none behind, whether it passes or fails.

Filed 2026-11-14 under ST361, from the VM runs that found the red main's cause.

## Why

After a run of `task-guarded.feature`'s resolver scenario on the VM, `porelo-guarded_fixedpath` was still running.
The next run of the same scenario in that account failed at its start with "porelo: porelo-guarded_fixedpath is
already running" (exit 65), which reads like a fault of the code under test.

## Plan

1. Find why the task the scenario starts with "a task called ... is started ... and left running" outlives it: the
   kit's cleanup for a left-running task, or the scenario's own end.
2. Remove it at the scenario's end, failed or not, as the other scenarios' tasks are.

## Red-first test

Test scripts get no red-first (the operator's rule 5). On the VM: the scenario twice in a row in one account, both
green, and no `porelo-guarded_fixedpath` afterwards.

## Risks

None beyond the scenario.

## Blockers

None.

## Acceptance

- The scenario twice in a row on the VM, both green, no container left.

## Open questions

None.
