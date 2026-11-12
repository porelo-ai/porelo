# B656 — Plugin Lifecycle And Readiness Proven Over Fakes

```json
{
  "$schema": "issue-1.json",
  "id": "B656",
  "title": "Plugin Lifecycle And Readiness Proven Over Fakes",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 65,
  "blockedBy": [
    "B665"
  ],
  "covers": "Plugin lifecycle/readiness: InstalledAgent, InstalledBuildReader, InstalledEcosystem, InstalledEcosystems",
  "openQuestions": 0
}
```

**Size.** M, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

InstalledAgent, InstalledBuildReader, InstalledEcosystem, InstalledEcosystems; process/connection/scheduler ports. Unit red: half ready line then silence times out, closes once, leaks no reader; fake timeout/never-describe/never-accept variants.

**Retained boundary and priority.** One executable/wire exchange and cancellation/unblock; InstalledEcosystemTest/InstalledEcosystemsTest/InstalledAgentTest cost tens of seconds.

### Flows it proves lower

| Area | Flow | Proven now (test classes) | Logic holder | Fix |
|---|---|---|---|---|
| Agents and credentials | A8: Start a plugin, validate ready/describe and clean up after timeout, half reply or protocol refusal | `InstalledAgentTest`, `InstalledBuildReaderTest`, `InstalledEcosystemTest`, `InstalledEcosystemsTest`; lower: Keep executable discovery and one wire exchange as integration; classify all timeout variants as unit policy first. | `InstalledAgent`, `InstalledBuildReader`, `InstalledEcosystem`, `InstalledEcosystems` | [B656](README.md#b656) |

### Classes it redesigns

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `InstalledAgent`, `InstalledBuildReader`, `InstalledEcosystem`, `InstalledEcosystems` | ProcessBuilder discovery/constructors; InstalledAgent starts at 91 and polls readiness. | PluginProcess, framed Connection, scheduler; explicit adapter start. | [B656](README.md#b656) |

## Acceptance

- Each harmful behaviour above has a unit regression seen red first, then the seam or core that makes it green.
- The flows above map their named higher paths to the new unit proof, or keep an explicit irreducible adapter contract.
- The report gives the exact revision, per-level cases and classes, testcase sums and the module's unit run time, measured as [B665](README.md#b665) defines; a duration not measured says so.

## Decided by the operator (2026-11-12)

- Each of the audit's fourteen fixes is its own issue; [B665](README.md#b665) (reproducible levels and times) is built first.
- "Under 60 seconds per module unit run" is a direction that is reported, not a gate.
- The agent drops repeated CLI end-to-end cases itself, once their lower proof is red then green, and lists them in the review.
- The redesigns of `TaskLaunch`, `TreeWalk` and `VaultProxy` run between feature rounds, with no feature issue open in those classes.
