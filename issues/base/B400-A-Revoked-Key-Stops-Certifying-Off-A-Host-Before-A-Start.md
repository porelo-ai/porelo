# B400 — A Revoked Key Stops Certifying Off A Host Before A Start

```json
{
  "$schema": "issue-1.json",
  "id": "B400",
  "title": "A Revoked Key Stops Certifying Off A Host Before A Start",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 34,
  "blockedBy": [],
  "covers": "Off a host, a key revoked in the organization stops being believed as a certifier of messages soon after, not only at the project's next start"
}
```

**Why this size.** not estimated.

**What must be true.** On a person's machine, a key revoked in the organization stops being believed as a certifier of a
project's messages soon after the revocation, not only at that project's next start.

Porelo reads an organization's project at a start and refreshes nothing in the background, so off a host a revoked key stays a
believed certifier until then. On a host the host's file answers at once. The operator accepted this for now on 2026-11-02, for
the simpler model.

## Open questions

- What reads it: the messaging path itself at a bounded age, or a small refresh the daemon runs.
- What a machine that cannot reach the forge then does with a message it has.
