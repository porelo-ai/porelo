# B712 — An Unknown Build Where A Watch Never Starts

```json
{
  "$schema": "issue-1.json",
  "id": "B712",
  "title": "An Unknown Build Where A Watch Never Starts",
  "state": "Implementing",
  "size": "S",
  "priority": "Medium",
  "rank": 66,
  "blockedBy": [],
  "covers": "A task whose project follows builds, but whose build watch did not start, tells its agent at each push that no build will be reported, as a project that follows no builds does.",
  "openQuestions": 0
}
```

**Why this size.** One condition at the start and its test.

Filed 2026-11-13 from building the hand-in at the push, which tells an agent at once when no build of its push will
come: a push held at the gate for a person, or a push in a project that follows no builds. The gate is told whether
builds are followed from the project alone. When the project names builds but the watch then fails to start (no
credential for the forge, the build reader not installed, the helper failing), the gate still thinks a build will be
reported. No `unknown` is handed in, so the agent waits for a report that never comes, the same fault in a rarer
case.

**What must be true.** The gate is told that builds are followed only when the watch actually runs.

## Plan

- Decide the gate's builds flag after the build watch has started (or failed), not from the project alone. On a
  resume, the same.
- Red first: a start whose build watch fails starts its gate as one that follows no builds, and a push hands in
  `unknown` with its reason.

## Acceptance

- The test holds and fails without the change.
