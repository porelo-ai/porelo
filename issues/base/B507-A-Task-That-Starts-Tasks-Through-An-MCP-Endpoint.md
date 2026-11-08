# [B507](README.md#b507) — A Task That Starts Tasks Through An MCP Endpoint

**Status:** Analyzed.
**Size:** L - `porelod` runs one `porelo-mcp` process per allowed task and serves it a per-task socket with the
allowed methods alone, started, resumed and stopped as `gate serve`
is; a `unix:` destination the gateway connects to; a project-file section with the methods and limits allowed; a stop
that takes only the endpoint's own children; a `started` event naming the parent; and the process in `task status`.
The tools themselves are not built here: they are `porelo-mcp`'s.

**What must be true.** A task the signed project file allows reaches Porelo's API as MCP, through the gateway on the
host, as every MCP server reaches a task. Through it the task starts tasks within the file's limits without anybody's
word, lists them, reads their state and protocol, and stops the tasks it started. Every answer and every refusal is the
daemon's, since `porelo-mcp` holds no rule of its own. A task started this way names the task that started it in the
event log. Stopping every task stays a person's act.

## Why

The operator decided on 2026-11-08 that the coordinator works from a task (`porelo-project` PJ103). The MCP side is
its own repository, `porelo-mcp`, with its own agent, Agent MCP. It offers every contract method as an MCP tool,
generated from the Varlink files, and ships as its own DEB/RPM. This issue is porelo's part.

## Analysis

Read in the code on 2026-11-08; reworked after the operator's decisions of the same day.

**Where it runs, and that it needs no root.**
- `porelod` is a systemd user unit, and the start already launches `porelo gate serve` as the same user, in a scope
  with `GateWiring.LIMITS` (`apps/app-task/src/main/java/org/fuin/porelo/app/GateWiring.java`), again on resume.
- `porelo-mcp` is started the same way, one process per allowed task. It listens on a Unix socket,
  `/run/user/<uid>/porelo/mcp/<task>.sock` with mode 0600, and asks for a token on every request. It has no TCP port,
  and nothing runs in the container.
- It never holds the person's socket. `porelod` opens a socket of its own for that one process,
  `/run/user/<uid>/porelo/mcp/<task>.daemon.sock` with mode 0600, bound to the task. It is a second
  `VarlinkServer` (`wire/src/main/java/org/fuin/porelo/wire/varlink/VarlinkServer.java`). `PoreloDaemon.serving`
  builds one today, with methods registered by name. This one carries only the methods the signed project file
  allows, each wrapped to take the caller from the socket it was reached on and never from a field. Its interface
  description is Tasks1 cut down to those methods, so `porelo-mcp` lists the tools it is offered and holds no rule.
  Nothing in this needs root.

**The gateway reaches it as a destination.**
- Per [the decision](../../doc/decisions.md#an-mcp-server-reaches-a-task-only-over-http-through-the-gateway-on-the-host),
  an MCP server reaches a task only over HTTP through the gateway on the host. The gateway is `VaultProxy`
  (`supervisor/src/main/java/org/fuin/porelo/supervisor/VaultProxy.java`). It sends upstream with `HttpClient`.
- `Destination` (`apps/app-base/src/main/java/org/fuin/porelo/app/Destination.java`) takes https alone today
  (line 73). It needs a second kind, `unix:<path>`, taken only for a path under the person's runtime directory. The
  gateway then sends HTTP over that socket instead of opening a connection to a host. The token `porelo-mcp` asks for
  is the destination's key, which the gateway adds, so the agent never holds it.
- The firewall needs no new rule: the traffic stays on the host's side of the gateway.

**Limits, in the project file.**
- A section naming the allowed methods, the agents and models a started task may use, the tasks at once and the
  longest run. Defaults: 6 tasks at once, 240 minutes, Claude Code with Opus or Sonnet, Codex and agy. Each is
  configurable.
- It is signed with the file, so a change is an admin's commit like every other setting.
- The per-task socket serves the allowed methods alone, so `porelo-mcp` offers no other tool without filtering
  anything itself.
- The daemon counts the parent's running children before a start and refuses past the limit, with a named refusal. It
  also refuses `minutes` above the longest run and an agent or model not allowed. These are the file's limits, applied
  where every start is applied.

**Stopping only its own.**
- A start over the per-task socket records that socket's task as the parent in the started task's profile, beside
  its branch and prompt.
- A stop over it refuses a task whose recorded parent is another task. The rule rests on the socket, never on what
  `porelo-mcp` says.
- No method that stops every task is offered.

**The event log.** The log (`55-events.varlink`) has no kind for a start today. A start through `porelo-mcp` records a
`started` event naming the parent in `detail`, so `Events` shows who started what. Within Tasks1 only the comment on
`kind` grows.

**`task status`.** The task shows its `porelo-mcp` process as it shows its gate: running, its socket, or why it is
absent.

**The binding with `porelo-mcp`.** Agreed with Agent MCP in the room before anything is built, and written down by
Agent MCP as the contract between the two: the process's arguments, the socket path, how the token is handed over,
and where the per-task daemon socket lies.

**Open for the plan, not for the operator:**
- Whether the protocol call is the task's log (`Tail`), its events (`Events`), or both. That falls out of which
  methods the file allows.
- Whether the token reaches `porelo-mcp` through its environment, a file of mode 0600, or its standard input. A
  command line is ruled out, since others can read it.
