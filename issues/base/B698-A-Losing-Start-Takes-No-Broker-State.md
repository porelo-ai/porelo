# B698 — A Losing Start Takes No Broker State

```json
{
  "$schema": "issue-1.json",
  "id": "B698",
  "title": "A Losing Start Takes No Broker State",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 52,
  "blockedBy": [],
  "covers": "Of two starts racing for one task name, the one that loses starts no vault broker in the winner's state directory and touches no vault.pid, socket or token of it.",
  "openQuestions": 0
}
```

**Why this size.** The order of a start's steps against the name reservation, a reproduction with two racing starts,
and the move of the broker after the reservation, or into a directory of the start's own.

Filed 2026-11-13 from [B384](README.md#b384): in a name race, the losing start's broker uses the same `vault.pid`,
socket and token file as the winner's, before its own create runs. The name reservation should keep the loser out
before it gets that far; whether it does at that step was not read.

**What must be true.** A start that loses the name to another touches nothing of the winner's helpers.

## Plan

- Read the start's order: where the reservation is taken, and where the broker starts.
- Red first: two starts of one name, the second held between its reservation and its create, see the winner's
  broker files unchanged.
- Fix: the broker starts after the reservation is held, or in a directory of the start's own until it is.

## Acceptance

- Two racing starts leave the winner's broker files as the winner wrote them; a test fails while the loser writes them.
