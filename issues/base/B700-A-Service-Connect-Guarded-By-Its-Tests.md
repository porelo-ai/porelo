# B700 — A Service Connect Guarded By Its Tests

```json
{
  "$schema": "issue-1.json",
  "id": "B700",
  "title": "A Service Connect Guarded By Its Tests",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 54,
  "blockedBy": [],
  "covers": "Each of `service connect`'s path and namespace guards has a test that fails without it, its relay is tested with a large binary stream and a service that stays open, and a request cut short is a failure even when the service closes first.",
  "openQuestions": 0
}
```

**Why this size.** Test fixtures that report real owners and namespaces, a handful of tests, and one ordering in the
relay.

Filed 2026-11-13 from a reading of `porelo service connect`, on its own branch, and its follow-up:

- Every successful connection in `ServiceConnectCommandTest` injects uid 0 and a replacement attribute reader, so
  removing the `uid_map` check or `NOFOLLOW_LINKS` leaves the tests green.
- Only the socket's immediate parent is made unsafe; an unsafe ancestor further up is not tested. The 0777 case does
  not catch narrowing the `0022` check to `0002`. Every fixture socket is owned by root, so wrongly requiring a
  root-owned socket would pass.
- The relay is tested only with small text: no large binary stream, no backpressure, no service that stays open after
  the input ends.
- A stdin failure recorded after the service already closed still ends 0.

**What must be true.** Each guard has its own failing test without it, and a request cut short ends 70 whichever side
closes first.

## Plan

- Fixtures: an attribute reader per directory, with a test per guard: `uid_map`, no-follow, an unsafe grandparent,
  group write alone, and a socket owned by the service account.
- Relay: a 16 MiB binary round trip through a slow reader, and a service open after the input ends, which the command
  waits for.
- `ServiceConnectCommand`: the upload's failure decides the exit before the relay's end does.

## Acceptance

- Each guard's test fails while that guard is removed; the cut-short request ends 70 in both orders.
