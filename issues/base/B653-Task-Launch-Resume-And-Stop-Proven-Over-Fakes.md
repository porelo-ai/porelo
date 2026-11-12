# B653 — Task Launch, Resume And Stop Proven Over Fakes

```json
{
  "$schema": "issue-1.json",
  "id": "B653",
  "title": "Task Launch, Resume And Stop Proven Over Fakes",
  "state": "Estimated",
  "size": "L",
  "priority": "Medium",
  "rank": 62,
  "blockedBy": [
    "B665"
  ],
  "covers": "Task launch/resume/stop: TaskLaunch, TaskControl, TaskRunner, TaskInventory, CredentialWiring/GateWiring/McpWiring/ClearanceWiring",
  "openQuestions": 0
}
```

**Size.** L, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

TaskLaunch, TaskControl, TaskRunner, TaskInventory, CredentialWiring/GateWiring/McpWiring/ClearanceWiring; plans, journal, helper supervisor, task/credential stores. Unit red: setup fails after creation, all helpers stop, foreign container untouched, held work kept; all T1–T3 refusals/compensations.

**Retained boundary and priority.** One real startup/order/cleanup; VM isolation/reboot. TaskRunCommandTest largest local class; orphan/data-loss harm.

### Flows it proves lower

| Area | Flow | Proven now (test classes) | Logic holder | Fix |
|---|---|---|---|---|
| Tasks | T1: Create, refuse and unwind a task before and after its container starts | `TaskRunCommandTest`, `StartCheckTest`, `StartProgressReviewTest`; lower: Parameters and branch parsing have unit coverage; they do not prove that refusal creates nothing or that a partial launch unwinds. | `TaskLaunch`, `TaskRunner` | [B653](README.md#b653) |
| Tasks | T2: Resume from durable state, retain tokens and session, order helpers and preserve held work | `TaskLifecycleCommandsTest`, `TaskStateTest`, `TaskRefreshTest`, `TaskUsageTest`, `TaskTargetTest`; lower: TaskSessionTest proves session storage; resume effects and rollback still use helpers and the encrypted store. | `TaskControl`, `TaskState`, `TaskSecrets` | [B653](README.md#b653) |
| Tasks | T3: Stop and remove only the recorded container, account for every surviving helper and keep failed work | `TaskStopSurvivorsTest`; lower: TeardownTest proves the lock; survivor decisions still start real processes. | `TaskControl`, `TaskLifecycle`, `Teardown` | [B653](README.md#b653) |
| Daemon and MCP | D2: Reopen task endpoints, reject stale helpers and close or replace sockets in lifecycle order | `McpWiringTest`, `McpSocketsReopenTest`; lower: McpDaemonSocketsTest proves registration with a fake opener; readiness/reopen cleanup still uses processes. | `McpWiring`, `McpTaskSockets` | [B653](README.md#b653); [B662](README.md#b662) |

### Classes it redesigns

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `TaskLaunch` | 4,668 lines; creates purchases, wiring, mailboxes/stores; system time; coupled rollback. | Launch journal; AgentCatalog, CredentialStore, Runtime, HelperSupervisor, Clock. | [B653](README.md#b653) |
| `TaskControl` | 1,199 lines; files, ProcessHandle, ProcessBuilder; stop polling at 624–627. | Resume/stop/remove plans over snapshots; state store and supervisor. | [B653](README.md#b653) |
| `TaskRunner` | Direct helper start at 756; hostResolvers reads host state outside FakeCommandRunner. | HostNetworkSnapshot and HelperSupervisor beside CommandRunner. | [B653](README.md#b653) |
| `TaskInventory` | 1,825 lines; AgentWaiting/quiet-log system time, identity/store reads, screen concurrency. | Facts/status readers, Clock, executor; pure inventory projection. | [B653](README.md#b653) |
| `CredentialWiring`, `GateWiring`, `McpWiring`, `ClearanceWiring` | Construct helpers; sleep while polling sockets/process liveness. | HelperSupervisor/ReadySignal; ready/failed/exited/timed-out values. | [B653](README.md#b653) |

### Waits it replaces

| Kind | Methods | Most numerous classes (methods) | Replacement / fix |
|---|---:|---|---|
| Polling/deadline | 154 | IdentityKeePassXcTest 19; AgentSignInRunsTest 11; TaskRunCommandTest 8; PoreloDaemonTest 7; MessageWatchNoticeTest 6 | KeyStore/AgentKeyView/journal ([B655](README.md#b655)); registry/completion ([B659](README.md#b659)); helper/task plans ([B653](README.md#b653)); event source/scheduler/sink/cancellation ([B660](README.md#b660), [B662](README.md#b662)). RepeatedTest retry and ClearanceWiringListeningTest readiness spend 200 ms ([B664](README.md#b664)/[B653](README.md#b653)). KernelKeyringTest's two methods retain kernel TTL/second boundary and 1,200 ms survival integration ([B661](README.md#b661)). |
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
