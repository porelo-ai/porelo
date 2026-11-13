# B637 — A Loopback Homeserver Is Lent Its Token

```json
{
  "$schema": "issue-1.json",
  "id": "B637",
  "title": "A Loopback Homeserver Is Lent Its Token",
  "state": "Implementing",
  "size": "S",
  "priority": "Low",
  "rank": 1,
  "blockedBy": [],
  "covers": "porelo lends matrix-registration-token.<server> to a homeserver on loopback http, as it does to an https or Unix socket one, so a person's local homeserver can be named for a project; any other plain http address still lends nothing."
}
```

**Why this size.** `LentEntry.server` and its one rule, read by the lending and by whoever names the entry
beforehand, with tests for each form.

Filed 2026-11-12 from the operator's decision on ST314. `LentEntry.server` lends only for a plain https address or a
homeserver's Unix socket. A person's own homeserver on `http://127.0.0.1:<port>` or `http://localhost:<port>` lends
nothing, so it cannot be named for a project.

**What must be true.** A loopback http address (127.0.0.0/8, ::1, localhost) lends for its host and port, under a
server name that cannot be mistaken for an https one of the same host. Any other http address still lends nothing.
The rule stays one rule for the lending and for naming the entry beforehand.
