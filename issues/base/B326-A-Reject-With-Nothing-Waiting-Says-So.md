# B326 — A Reject With Nothing Waiting Says So

```json
{
  "$schema": "issue-1.json",
  "id": "B326",
  "title": "A Reject With Nothing Waiting Says So",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 48,
  "blockedBy": [],
  "covers": "A reject of a name with nothing waiting says so and tells no task its work was discarded"
}
```

**What must be true.** `porelo gate reject <name>` for a name with no waiting push says that nothing
waits under that name and exits non-zero; it records no rejection and puts no reason into the
task's inbox, so a task is never told its work was discarded when it pushed none.

Seen on `fedora` (2026-10-29): a reject of a task that had never pushed answered `discarded` and
`told the task it came from`, and `gate pending` then said `nothing pending`.

## Acceptance

- A reject of a name with no incoming ref exits non-zero naming `porelo gate pending`, writes no
  `REJECTED` event and nothing into the task's mailbox.
- A reject of a waiting push behaves as before, including `--force` for one already upstream.
