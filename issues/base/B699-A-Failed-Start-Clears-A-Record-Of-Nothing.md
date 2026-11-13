# B699 — A Failed Start Clears A Record Of Nothing

```json
{
  "$schema": "issue-1.json",
  "id": "B699",
  "title": "A Failed Start Clears A Record Of Nothing",
  "state": "Backlog",
  "size": "S",
  "priority": "Low",
  "rank": 53,
  "blockedBy": ["B384"],
  "covers": "A failed start that made no container of its own, finding the name's record pointing at a container that is gone, removes that record instead of leaving it behind.",
  "openQuestions": 0
}
```

**Why this size.** One branch in the cleanup [B384](README.md#b384) changed, and a test.

Filed 2026-11-13 from [B384](README.md#b384): a failed start now cleans up only the id its own create recorded, and
touches nothing when the record on disk names a live container. When that container is gone, the stale record stays.

**What must be true.** The record of a container that no longer exists is removed by the failed start's cleanup; one
naming a live container is still left alone.

## Plan

- `TaskLaunch`'s cleanup: with no own id, read the record's container; when podman knows no such container, forget the
  record through `TaskReservations`.
- Red first: a failed start with a stale record leaves none; one with a live container's record still leaves it.

## Acceptance

- Both cases hold; a test fails while the stale record stays.
