# B792 — What the Reading of the Read-Only Start Left

```json
{
  "$schema": "issue-1.json",
  "id": "B792",
  "title": "What the Reading of the Read-Only Start Left",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [],
  "covers": "The given files' bound holds for the whole Start message, CanStart refuses a read-only start beside from or upstream as Start does, and the read-only start's guards each have a test",
  "openQuestions": 0
}
```

**Why this size.** S: one bound and one check moved to where they hold, and four guard tests. Estimate: 38 minutes
(Core's median for S).

**What must be true.** What a read-only start with given files refuses, CanStart refuses alike, and nothing just under
a bound fails later where the bound did not look.

Filed 2026-11-15 from Sonnet's reading of the read-only start with given files, as its remainder (a reading's
findings beyond its round become an issue of their own).

## Why

- `TaskGiven.refusal` bounds the given map's own encoding by the 4 MiB a varlink message may carry, but the Start
  message carries the other parameters beside it: a map just under the bound can still overflow the message.
- `StartCheck`'s read-only check does not refuse `from` or `upstream`, which the launch refuses.
- No test shows that a given file cannot be written through a link or onto a swapped file, that a read-only task gets
  no gate through the daemon's or a task's MCP start, or that the message bound holds.
- To be checked: the one-repository workspace is read from the gate's mirror after `seed`; whether "main as this
  machine takes it" ever shows what tasks pushed, which it must not.

## Plan

1. The bound leaves room for the rest of the message (a fixed margin, or the encoded message measured where it is
   built), with its test just under and just over.
2. `StartCheck` refuses `readOnly` beside `from` or `upstream` with `READ_ONLY_REFUSED`, as `TaskLaunch` does.
3. The guard tests above, each seen red by a deliberate break.
4. Read the seed path and say what the workspace shows; fix it if it can show a task's push.

## Red-first test

- A given map whose Start message would pass 4 MiB: refused with 64. Fails today: accepted.
- CanStart with `readOnly` and `from`: `READ_ONLY_REFUSED`. Fails today: answered as a start.

## Acceptance

- The red-first tests pass, each seen red first.
