# B665 — Test Levels And Times Measured Reproducibly

```json
{
  "$schema": "issue-1.json",
  "id": "B665",
  "title": "Test Levels And Times Measured Reproducibly",
  "state": "Estimated",
  "size": "S",
  "priority": "Medium",
  "rank": 60,
  "blockedBy": [],
  "covers": "Reproducible levels/times: Root/module selection/metadata: separate unit/integration invocations",
  "openQuestions": 0
}
```

**Size.** S, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

Root/module selection/metadata: separate unit/integration invocations; cases/classes/skips/class/testcase/step times. First check: read-only integration over fixture XML with mixed/parallel cases, no formatter-mirroring test. Re-measure module units under 60 s, then integration/end-to-end.

**Retained boundary and priority.** Surefire currently mixes levels; GitGateTest's own end-to-end label conflicts with this classification.

## Acceptance

- Each harmful behaviour above has a unit regression seen red first, then the seam or core that makes it green.
- The flows above map their named higher paths to the new unit proof, or keep an explicit irreducible adapter contract.
- The report gives the exact revision, per-level cases and classes, testcase sums and the module's unit run time, measured as [B665](README.md#b665) defines; a duration not measured says so.

## Decided by the operator (2026-11-12)

- Each of the audit's fourteen fixes is its own issue; [B665](README.md#b665) (reproducible levels and times) is built first.
- "Under 60 seconds per module unit run" is a direction that is reported, not a gate.
- The agent drops repeated CLI end-to-end cases itself, once their lower proof is red then green, and lists them in the review.
- The redesigns of `TaskLaunch`, `TreeWalk` and `VaultProxy` run between feature rounds, with no feature issue open in those classes.
