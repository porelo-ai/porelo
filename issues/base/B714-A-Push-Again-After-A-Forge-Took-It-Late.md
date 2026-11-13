# B714 — A Push Again After A Forge Took It Late

```json
{
  "$schema": "issue-1.json",
  "id": "B714",
  "title": "A Push Again After A Forge Took It Late",
  "state": "Backlog",
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
