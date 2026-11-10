# B609 — A Resumed Task Takes Its Project's Changed MCP Methods

```json
{
  "$schema": "issue-1.json",
  "id": "B609",
  "title": "A Resumed Task Takes Its Project's Changed MCP Methods",
  "state": "Idea",
  "size": "S",
  "priority": "Low",
  "rank": 30,
  "blockedBy": [],
  "covers": "A task's MCP socket serves the methods its project file allows now, not the ones allowed when the daemon first opened it, without taking the mounted socket's inode away."
}
```

**Why this size.** the socket's method set read per request or replaced in place; one daemon test.

Filed 2026-11-11 from [B600](README.md#b600)'s As built, which left it for a later issue.

**What must be true.** Since [B600](README.md#b600), a repeated open keeps the served socket, so a running task's
mount keeps its inode. The cost is that a resumed task keeps the MCP methods its project file allowed when its socket
was first opened, until the daemon restarts. A changed `mcp.methods` should take effect on the next start of the
task (or at once), while the served socket and its inode stay as they are.

## Plan

- `McpTaskSockets` keeps the socket and swaps the allowed methods it checks against on a repeated open, or reads them
  from the project at each call.
- Red first: a `McpTaskSocketsTest` case opens a task's socket, narrows the project's methods, opens again, and
  expects the removed method refused while the socket's inode is unchanged.
