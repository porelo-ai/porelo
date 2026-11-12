# B661 — Waits And Timestamps On An Injected Clock

```json
{
  "$schema": "issue-1.json",
  "id": "B661",
  "title": "Waits And Timestamps On An Injected Clock",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 70,
  "blockedBy": [
    "B665"
  ],
  "covers": "Clock/deterministic waits: BackupRecords, EventLog, AuthorizationsNeeded, TokenBroker, MessageBudget/MessageRecord/MessageSay, UpstreamProbe, FirstStart cache, Prune, TaskUsage, certificate requests",
  "openQuestions": 0
}
```

**Size.** M, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

BackupRecords, EventLog, AuthorizationsNeeded, TokenBroker, MessageBudget/MessageRecord/MessageSay, UpstreamProbe, FirstStart cache, Prune, TaskUsage, certificate requests; Clock/monotonic ticker. Unit red: same-day quota and exact expiry, no sleep; controlled concurrency replaces delay-speed tests.

**Retained boundary and priority.** Narrow tagged kernel expiry/process-lock checks; timestamp races; apps/app unit testcase sum exceeds a minute.

### Classes it redesigns

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `MessageBudget`, `MessageRecord`, `MessageSay` | Instant.now prevents deterministic midnight/quota/order tests. | Clock at composition; in-memory records and fixed timestamps. | [B661](README.md#b661) |
| `BackupRecords`, `EventLog`, `AuthorizationsNeeded`, `TokenBroker` | Instant.now; order/expiry tests sleep or sample unrelated wall time. | Clock/explicit instants; advancing order/expiry/authorization fakes. | [B661](README.md#b661) |

### Waits it replaces

| Kind | Methods | Most numerous classes (methods) | Replacement / fix |
|---|---:|---|---|
| Sleep | 63 | VaultProxyTest 45; TokenPurchaseTest 12; EventLogTest 2; AgentWaitingTest 1; TaskListingScreensTest 1 | Controlled upstream reads/exchange ([B657](README.md#b657)); Clock for timestamp order, futures/latches/executor for concurrency ([B661](README.md#b661)). Proxy delay 300 ms plus SSE 3,000 ms; token handler 200 ms; screen read 200 ms; timestamp sleeps 5 ms. BackupRecordsTest and AuthorizationsNeededTest also sleep 5 ms. |
| Polling/deadline | 154 | IdentityKeePassXcTest 19; AgentSignInRunsTest 11; TaskRunCommandTest 8; PoreloDaemonTest 7; MessageWatchNoticeTest 6 | KeyStore/AgentKeyView/journal ([B655](README.md#b655)); registry/completion ([B659](README.md#b659)); helper/task plans ([B653](README.md#b653)); event source/scheduler/sink/cancellation ([B660](README.md#b660), [B662](README.md#b662)). RepeatedTest retry and ClearanceWiringListeningTest readiness spend 200 ms ([B664](README.md#b664)/[B653](README.md#b653)). KernelKeyringTest's two methods retain kernel TTL/second boundary and 1,200 ms survival integration ([B661](README.md#b661)). |
| Real clock | 29 | RemoteStartTest 8; UpstreamRecordsTest 5; SignedArrivalsTest 4; EventsCommandTest 2; MessageSignersTest 2 | Clock.fixed/explicit Instant for events, expiry, file/certificate judgments ([B661](README.md#b661)); remote certificate Clock ([B663](README.md#b663)), arrival verifier/facts ([B658](README.md#b658)). |
| Child process/lock | 11 | ProcessCommandRunnerTest 8; OwnerOnlyFilesTest 1; VaultFileTest 1; HelperPidStopTest 1 | Fake Process/lock/deadline decisions ([B661](README.md#b661), [B663](README.md#b663); helper supervision [B653](README.md#b653)); keep narrow real pipe/exit/lock/umask/child-death contracts. |

## Acceptance

- Each harmful behaviour above has a unit regression seen red first, then the seam or core that makes it green.
- The flows above map their named higher paths to the new unit proof, or keep an explicit irreducible adapter contract.
- The report gives the exact revision, per-level cases and classes, testcase sums and the module's unit run time, measured as [B665](README.md#b665) defines; a duration not measured says so.

## Decided by the operator (2026-11-12)

- Each of the audit's fourteen fixes is its own issue; [B665](README.md#b665) (reproducible levels and times) is built first.
- "Under 60 seconds per module unit run" is a direction that is reported, not a gate.
- The agent drops repeated CLI end-to-end cases itself, once their lower proof is red then green, and lists them in the review.
- The redesigns of `TaskLaunch`, `TreeWalk` and `VaultProxy` run between feature rounds, with no feature issue open in those classes.
