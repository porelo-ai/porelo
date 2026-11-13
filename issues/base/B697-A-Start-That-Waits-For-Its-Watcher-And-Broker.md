# B697 — A Start That Waits For Its Watcher And Broker

```json
{
  "$schema": "issue-1.json",
  "id": "B697",
  "title": "A Start That Waits For Its Watcher And Broker",
  "state": "Review",
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

## Built

By the start commit, a watcher that never listened and a broker that never wrote its token already ended the start
with 70. What was missing was waiting for each helper's pid, as the gate and the relay are waited for.

- `ClearanceWiring.startClearance`: once the watcher's socket is there, the start waits through `HelperPid.await` for
  `state/watcher.pid`, within `GateWiring.HELPER_PATIENCE`. The deadline is counted from the watcher's start. With no
  pid, the watcher is ended through its handle and the start says "the clearance watcher did not come up". It then
  ends 70 with its cleanup. The wait for the socket stays, since the watcher misses connections made before it
  listens.
- `CredentialWiring.startVault`: once the broker's token is there, the start waits for `state/vault.pid` in the same
  way. With no pid, the broker is ended, and the start says "the credential proxy did not come up" and ends 70 through
  `BrokerDown`.
- Tests:
  - `OwnPorelo.watcher(registry, recordsItsPid)` replaces the fixed stand-in; `broker(...)` takes `recordsItsPid`.
  - New: `aStartWhoseBrokerNeverRecordsItsPidFailsAndLeavesItNotRunning` and
    `aStartWhoseWatcherNeverRecordsItsPidFailsAndLeavesNoHelperRunning`. Each helper ignores TERM, and nothing is left
    running.
  - Both failed on the build box before the change (`buildbox-porelo-d46155ba8b9d-20261113T171002Z.log`) and pass
    after it (`buildbox-porelo-7f42217a4606-20261113T171238Z.log`). The green run covers TaskRunCommandTest (287
    tests), StartCheckTest and the wiring tests, with 0 skipped.
- Not covered: the repository broker (`startRepositories`) still waits only for its socket, and a start goes on
  without it when it fails. That is filed apart.
