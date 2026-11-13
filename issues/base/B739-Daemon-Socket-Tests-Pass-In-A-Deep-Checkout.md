# B739 — Daemon Socket Tests Pass In A Deep Checkout

```json
{
  "$schema": "issue-1.json",
  "id": "B739",
  "title": "Daemon Socket Tests Pass In A Deep Checkout",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
  "rank": 50,
  "blockedBy": [],
  "covers": "McpSocketsReopenTest passes in a checkout whose path is long: its task and daemon sockets are bound under a short temporary directory, never under target/, so no socket path passes Linux's 108 bytes.",
  "openQuestions": 0
}
```

**Why this size.** XS: where one test class places its runtime directory, and the same look at the daemon's other
socket tests. Estimate: under an hour, below Core's median for S (45 minutes).

**What must be true.** The daemon's socket tests pass wherever the repository is checked out, however deep its path.

Found on the build machine: in a checkout of about 70 characters, `McpSocketsReopenTest` failed three cases. Two with
"Unix domain path too long" binding a task's socket, and one because a `.daemon.sock` under
`target/ms-<id>/porelo/mcp/` was never made. The socket paths came to about 117 bytes; Linux allows 108. In a short
checkout the same commit is green.

## Plan

- `McpSocketsReopenTest`: its runtime directory under a short temporary directory (as other socket tests do, for
  example under `/tmp` with a short prefix), removed after, never under `target/`.
- The other daemon and app tests that bind sockets under `target/` are read for the same limit and moved alike.
- First red: the test run from a checkout whose path is padded to 80 characters fails as on the build machine; after
  the change it passes.

## Acceptance

- `McpSocketsReopenTest` passes in a checkout of 80 characters or more.
- No test binds a socket under `target/` with a path that grows with the checkout's.
