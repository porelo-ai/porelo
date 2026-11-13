# B609 — A Resumed Task Takes Its Project's Changed MCP Methods

```json
{
  "$schema": "issue-1.json",
  "id": "B609",
  "title": "A Resumed Task Takes Its Project's Changed MCP Methods",
  "state": "Accepted",
  "size": "S",
  "priority": "Low",
  "rank": 30,
  "blockedBy": [],
  "covers": "A task's MCP socket serves the methods its project file allows now, not the ones allowed when the daemon first opened it, without taking the mounted socket's inode away.",
  "openQuestions": 0
}
```

**Why this size.** One class of the daemon and its test, no contract another repository reads: `McpTaskSockets`
reads the project's `mcp` section again on every open and swaps what its live socket checks against, instead of
returning the socket it has. The interface description, the methods' wire shapes and `McpWiring` stay as they are.
Estimate: 45 minutes (Core's median for S).

Filed 2026-11-11 from [B600](README.md#b600)'s As built, which left it for a later issue.

**What must be true.** Since [B600](README.md#b600), a repeated open keeps the served socket, so a running task's
mount keeps its inode. The cost is that a resumed task keeps the MCP methods its project file allowed when its socket
was first opened, until the daemon restarts. A changed `mcp.methods` should take effect on the next start of the
task (or at once), while the served socket and its inode stay as they are.

## Plan

Read in today's code: `McpTaskSockets.open` (daemon) returns the served socket as soon as it holds the task, before it
reads the project; only a first open reads `ProjectReader.endpoint`. `TaskSocket` keeps that `McpEndpoint` final: its
`method(...)` override registers only the methods the endpoint names (an unregistered one answers `MethodNotFound` from
`VarlinkServer.handle`), the description is cut to them once, and `start(...)`, `readsTheRun` and the agent and limit
checks take the same frozen endpoint. So a resumed task keeps the old methods, the old agents and limits, and even a
socket its project no longer allows at all, while its guide (`McpWiring.told`, read fresh at each start) already lists
the new methods.

The change takes effect at the next open, which is every start and resume (`McpWiring.open`, `McpWiring.resume`); a
running task keeps what it was started with, as its guide does.

1. `open` reads and checks the endpoint (`ProjectReader.endpoint`, `refusals`) before it looks for a served socket. No
   endpoint now: close the task's socket and answer empty, as a first open does, so `resume` says the project allows
   none. A refused method list: throw as today, leaving the served socket untouched.
2. `TaskSocket` keeps its endpoint in a volatile field with a package-private `allow(McpEndpoint)`; a repeated open
   calls it on the served socket and calls `describedBy(cut(...))` again (`VarlinkServer`'s description is volatile).
3. `TaskSocket.method` registers every method `PoreloDaemon.register` offers, wrapped as today, with one check in front
   of each: a name the current endpoint does not carry throws `VarlinkException` named
   `org.varlink.service.MethodNotFound` with `{method}`, the error an unregistered method answers. `start`,
   `readsTheRun` and the `Logs`/`Tail` cut read the current endpoint at the call, not the one the socket was made with.
4. The class comment's "Only what the file allows" says the methods are those of the file at the task's last start.

Red first: a `McpTaskSocketsTest` case opens a task's socket with `Start` and `Stop`, narrows the project's
`mcp.methods` to `Stop`, opens again, and expects `Start` answered `MethodNotFound` and absent from the socket's
description, while the socket's `unix:ino` is unchanged. A second case removes the `mcp` section and expects the
repeated open empty and the socket gone.

## Acceptance

- The red-first cases pass; `anEncodedEndpointSurvivesDaemonRecreationWithoutReplacingALiveSocket` still passes, so a
  repeated open with an unchanged file keeps the inode.
- Seen failing first: a method removed from the file still answered after a repeated open; a method added to the file
  not answered after one; a project with no `mcp` section left still handing its task the old socket.
- A method the file never allowed, and one `refusals` refuses, still answers `MethodNotFound` on every task socket.

## Open questions

None.

## To be checked

- Whether any test or caller reads a task socket's `methodNames()` and expects only the allowed ones; registering
  every method changes that set (the daemon socket's registration tests do not cover task sockets).
- That a start running on the old endpoint while another open swaps it finishes under the endpoint it began with,
  and the next call takes the new one.
