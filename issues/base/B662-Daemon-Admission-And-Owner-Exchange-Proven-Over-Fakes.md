# B662 — Daemon Admission And Owner Exchange Proven Over Fakes

```json
{
  "$schema": "issue-1.json",
  "id": "B662",
  "title": "Daemon Admission And Owner Exchange Proven Over Fakes",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 71,
  "blockedBy": [
    "B665"
  ],
  "covers": "Daemon admission/owner/methods: McpTaskSockets, HostVaultConnection, VaultMethods, TaskMethods, MessagingMethods",
  "openQuestions": 0
}
```

**Size.** M, as the test pyramid audit measured it (2026-11-09), split out by the operator on 2026-11-12 (ST296).

**What must be true.** The flows below are proven first as plain logic behind small interfaces, by unit tests over fakes that run in seconds; only the boundary named under *Retained boundary* stays an integration test, and no higher test that proves a harmful path is removed before its lower proof is seen red, then green.

## Plan

McpTaskSockets, HostVaultConnection, VaultMethods, TaskMethods, MessagingMethods; in-memory registry/owner/scheduler and direct policies. Unit red: two starts race for last place; loser frees no winner reservation; forgetting owner cancels exchange without share disclosure.

**Retained boundary and priority.** Peer numeric uid, descriptor ownership, framing, cancellation. Move branch tables below endpoints; scope/capacity harm and stream cost.

### Flows it proves lower

| Area | Flow | Proven now (test classes) | Logic holder | Fix |
|---|---|---|---|---|
| Daemon and MCP | D1: Limit child starts atomically, enforce project/task scope and strip forbidden run output | `McpTaskSocketsTest`; lower: The tests exercise many branches through a real endpoint; a unit aggregate should own admission and release. | `McpTaskSockets` | [B662](README.md#b662) |
| Daemon and MCP | D2: Reopen task endpoints, reject stale helpers and close or replace sockets in lifecycle order | `McpWiringTest`, `McpSocketsReopenTest`; lower: McpDaemonSocketsTest proves registration with a fake opener; readiness/reopen cleanup still uses processes. | `McpWiring`, `McpTaskSockets` | [B653](README.md#b653); [B662](README.md#b662) |
| Daemon and MCP | D3: Stream only changed task state, tail bounded chunks, replay no old talk and end on client loss | `PoreloDaemonTest`; lower: WatchChangeTest proves equality and StartRepliesTest proves reply order; time-driven delivery still sleeps. | `TaskMethods`, `MessagingMethods`, `PoreloDaemon` | [B660](README.md#b660); [B662](README.md#b662) |
| Vault and identity | V1: Keep and forget a host share in one scope and cancel an unanswered owner exchange | `HostVaultConnectionTest`, `HostVaultSocketTest`; lower: HostVaultKeepTest and HostVaultScopeTest cover selected local rules, not the whole socket cancellation path. | `HostVaultConnection`, `HostVaultOwner`, `VaultMethods` | [B662](README.md#b662) |

### Classes it redesigns

| Class group | What makes it hard | Redesign | Fix |
|---|---|---|---|
| `McpTaskSockets`, `TaskMethods`, `MessagingMethods` | Daemon state, sockets, scope/capacity and streams share wall-clock waits. | Authorization/capacity core, fake registry/subscriber/scheduler; adapter binding. | [B662](README.md#b662) |
| `HostVaultConnection` | Socket worker, peer verification, exchange and cancellation coupled. | OwnerExchange and deadline/cancellation; retain peer/disconnect contract. | [B662](README.md#b662) |

### Waits it replaces

| Kind | Methods | Most numerous classes (methods) | Replacement / fix |
|---|---:|---|---|
| Polling/deadline | 154 | IdentityKeePassXcTest 19; AgentSignInRunsTest 11; TaskRunCommandTest 8; PoreloDaemonTest 7; MessageWatchNoticeTest 6 | KeyStore/AgentKeyView/journal ([B655](README.md#b655)); registry/completion ([B659](README.md#b659)); helper/task plans ([B653](README.md#b653)); event source/scheduler/sink/cancellation ([B660](README.md#b660), [B662](README.md#b662)). RepeatedTest retry and ClearanceWiringListeningTest readiness spend 200 ms ([B664](README.md#b664)/[B653](README.md#b653)). KernelKeyringTest's two methods retain kernel TTL/second boundary and 1,200 ms survival integration ([B661](README.md#b661)). |

## Acceptance

- Each harmful behaviour above has a unit regression seen red first, then the seam or core that makes it green.
- The flows above map their named higher paths to the new unit proof, or keep an explicit irreducible adapter contract.
- The report gives the exact revision, per-level cases and classes, testcase sums and the module's unit run time, measured as [B665](README.md#b665) defines; a duration not measured says so.

## Decided by the operator (2026-11-12)

- Each of the audit's fourteen fixes is its own issue; [B665](README.md#b665) (reproducible levels and times) is built first.
- "Under 60 seconds per module unit run" is a direction that is reported, not a gate.
- The agent drops repeated CLI end-to-end cases itself, once their lower proof is red then green, and lists them in the review.
- The redesigns of `TaskLaunch`, `TreeWalk` and `VaultProxy` run between feature rounds, with no feature issue open in those classes.
