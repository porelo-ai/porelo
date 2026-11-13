# B714 — A Push Again After A Forge Took It Late

```json
{
  "$schema": "issue-1.json",
  "id": "B714",
  "title": "A Push Again After A Forge Took It Late",
  "state": "Review",
  "size": "S",
  "priority": "Low",
  "rank": 68,
  "blockedBy": ["B197"],
  "covers": "A task's next push is passed on after an earlier pass-on ended unknown or not held, even when the forge took that earlier push late.",
  "openQuestions": 0
}
```

**Why this size.** One ls-remote before the lease in `HostPush`, and its test.

Filed 2026-11-13 from the reading of Agent Smith's follow-up to [B197](README.md#b197). After a pass-on reaches its
limit, the gate asks the forge what it holds and reports the push as taken, not held, or not known. A forge can still
take the stalled push after that. Then the gate's record of the forge's branch lags the forge, and the agent's next
push, with a new commit on top, is refused by `--force-with-lease` as stale. The agent is told to push again, and that
cannot succeed.

**What must be true.** A push again after a pass-on that ended unknown or not held is leased against what the forge
holds now.

## Plan

- When the task's last pass-on ended unknown or not held, `HostPush` asks the forge with the same bounded `ls-remote`
  before it leases, and leases against that.
- Red first: a forge stand-in that takes a push after the gate gave up, then a second push on top, which today is
  refused as stale.

## Acceptance

- The test holds and fails without the change.

## Built

- **What an unsettled pass-on leaves.** When a push reaches its limit and the forge then says it does not hold the
  push, or does not answer, `HostPush` leaves an empty marker `.<task>.unsettled` beside the task's push record. No
  task name begins with a dot, so it cannot meet a record or a lock.
- **The next push asks the forge first.** With the marker there, the next push asks the forge what its branch holds,
  with the same `ls-remote` as the check after a stall. The ask is bounded by the shorter of 30 s and half the time
  left, so the push keeps the other half. The push then leases on the forge's answer, or pushes with
  `--no-force-with-lease` when the forge holds no branch. A forge that does not answer leaves the lease on the record,
  as before. The marker goes once a push is taken and recorded.
- **A removed task's marker goes with it.** `GitGate.forget` deletes the marker, so a new task of the same name never
  leases on the removed task's branch.
- `doc/running.md`: a push again on a host first asks what the forge's branch holds.
- **Tests:**
  - `HostPushTest.aPushAgainAfterTheForgeTookAStalledPushLateIsLeasedOnWhatTheForgeHoldsNow`, for both a "not held"
    and a silent check after the stall. A stand-in forge takes the stalled push late, and the next push goes through
    on it.
  - `HostPushTest.aPushAgainWhoseForgeDoesNotSayWhatItHoldsLeasesOnTheRecord`.
  - `GitGateTest.forgettingATaskAlsoForgetsWhatItsHostPushLastPushed`, extended.

  They failed on the build box before the change (`buildbox-porelo-e7f20380fa29-20261113T174427Z.log`, with the
  issue's "stale info" refusal, and `buildbox-porelo-f26813f8210c-20261113T174730Z.log`), and pass after it
  (`buildbox-porelo-f6db6e94c654-20261113T174831Z.log`): 136 tests across the gate and host-push tests, 0 skipped.
