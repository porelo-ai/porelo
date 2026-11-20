# B814 — The Gates Push Use Case Leaves Its HTTP Handler

```json
{ "$schema": "issue-1.json", "id": "B814", "title": "The Gates Push Use Case Leaves Its HTTP Handler",
  "state": "Accepted", "size": "M", "priority": "High", "rank": 12, "blockedBy": [],
  "covers": "GitHttpServer's handler only maps the request: the push lock, receive-pack, judging, putting a refused ref back and passing on move into a gate service tested without HTTP" }
```

**Why this size.** One HTTP handler in `gate`, whose push path holds the use case today.

**What must be true.** An entry point of porelo (a picocli command, a daemon method, the gate's HTTP handler) only
checks its input, maps it to a record, calls one service method and maps the answer back; the service holds the use
case (locks, revisions, rules, storing, events) without knowing the transport and is tested without it. Behavior
stays as it is. Read in porelo at f0de69ecc, by reading only.

## Plan

- `GitHttpServer.handle` takes the push lock (`tryLock`), runs receive-pack, reads what it wrote, has the arrivals
  judged, puts refused refs back with `update-ref` and passes on. These move into one `GatePush` service in `gate`;
  the handler maps the request to it and its answer to the git protocol's.
- The service's unit test runs over a fake git and a fake judge, without a socket. The existing `GitGateTest`,
  `GateLandedTest` and `GateLimitsTest` keep holding the handler end to end.
