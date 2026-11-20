# B813 — Keep, Organization Creation And Gate Serving Hand Their Work To Services

```json
{ "$schema": "issue-1.json", "id": "B813", "title": "Keep, Organization Creation And Gate Serving Hand Their Work To Services",
  "state": "Accepted", "size": "M", "priority": "High", "rank": 11, "blockedBy": [],
  "covers": "KeepCommand's lock, newer-record rule and staged write, OrgCreateCommand's refusal, write and settling, and GateServeCommand's tie of passing on to the landed record move into services" }
```

**Why this size.** Three commands in `app`, `app-organization` and `app-gate`.

**What must be true.** An entry point of porelo (a picocli command, a daemon method, the gate's HTTP handler) only
checks its input, maps it to a record, calls one service method and maps the answer back; the service holds the use
case (locks, revisions, rules, storing, events) without knowing the transport and is tested without it. Behavior
stays as it is. Read in porelo at f0de69ecc, by reading only.

## Plan

- `KeepCommand`: `FileLocks.holding`, the newer-record rule by `updatedAt` (`replaces`), and the staged write moved
  into place go into one service.
- `OrgCreateCommand`: the refusal of an existing organization, the write and `OrgAccess.settle`.
- `GateServeCommand`: `gate.passOn` tied to `recordLanded`, and the event log hooked in.
- Each service has a unit test over fakes; the commands' tests are reduced to the mapping.
