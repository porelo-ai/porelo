# B167 — An MCP Gateway On The Host

**Status:** Analyzed.
**Size:** L - a design to decide first (transports, a changed tool list with `sokar-project` [PJ15](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj15)), the broker taught
a protocol, agents' MCP settings in their own repositories, docs and an acceptance step.

**What must be true.** The MCP servers a project names are reached through a gateway on the host, which sees and
journals their tool lists and their calls, and attaches their credentials as the broker does, so the task holds none.

## Why

Sokar does not read MCP traffic today, so a changed tool set goes unnoticed, and an MCP server's credential has to be
in the task - the largest gap `doc/faq.md` and `doc/corporate-security.md` name plainly. The broker's principle carries
over: the task talks to the host, the host adds the secret and sees what passes. Comparable tools run such a gateway.
It is the ground `sokar-project` [PJ15](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj15) (a tool does not change behind the person's back) builds on.

## The shape

- A project names its MCP servers; each is reached only through the gateway, and the firewall lets the task reach the
  gateway, not the server.
- The gateway journals each tool list and each call (tool name, arguments' size, outcome) per task ([B154](README.md#b154)).
- A credential for an MCP server lives in the vault and is attached on the way out.

## Plan

Half of this is there. A remote MCP server can already be a `destination` (`apps/app-base/.../Destination.java`,
`doc/credentials.md`, "More than one credential in a task"): its key is in the vault, the broker (`VaultProxy`)
attaches it, the task holds `SOKAR_TOKEN_<NAME>` and `SOKAR_URL_<NAME>`, and the server's host is not added to what
the task reaches. `VaultProxy` already streams `text/event-stream`, which MCP's HTTP transport answers in. What is
missing is reading the traffic, a server without a key, and the agents being pointed at the gateway.

1. **Red first:** a test in `supervisor` that a JSON-RPC `tools/list` answer and a `tools/call` request passing
   `VaultProxy` for a destination of kind `mcp` leave one journal line each, with the tool names and the arguments'
   size, and none for a destination of another kind.
2. **`Destination`** gains `kind: mcp` (default: none) and may name no key, for a server without one; the token is
   then a stand-in the broker checks and attaches nothing for.
3. **`supervisor/.../McpTraffic.java`:** reads each request body (`initialize`, `tools/list`, `tools/call` with its
   `params.name`) and each answer, as JSON or as server-sent events, without changing a byte. A tool list is kept as
   its names and a digest of each tool's description and schema, so a later list can be compared.
4. **The journal:** per task, a file in the task's state directory (`mcp.log`) beside the clearance journal in
   `TaskPaths`: when, the server, `tools/list` with its digest, or `tools/call` with the tool, the arguments' size and
   the outcome (result or JSON-RPC error). It moves into [B154](README.md#b154)'s log once that is built.
5. **The project file:** `mcp:` in `core/.../project/ProjectReader.java` and `ProjectSchema`, a list of destination
   names; each is added to the task's credentials as `credentials:` does. An agent definition says where it reads its
   MCP servers; Sokar hands the agent the list through `SetupContext`, and each agent repository writes its own
   settings from it. That is a change of `sokar-agent-api` the agent repositories take.
6. **A changed tool list:** journaled with the old and new digests in this step, and shown by `sokar task status`;
   what more happens is [PJ15](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj15)'s.
7. **Docs:** `doc/credentials.md`, `doc/project-file.md`, and `doc/faq.md` and `doc/corporate-security.md` move the
   gap to what is covered, keeping a server started inside the container as not covered.
8. **Acceptance:** a stub MCP server over HTTP on the acceptance machine, named by a project; the stub agent lists its
   tools and calls one through `SOKAR_URL_<NAME>`; `mcp.log` holds both; the server's key is not in the container.

## Acceptance

- Seen to fail first, then green: a task reaches a stub MCP server only through the gateway; its tool list and a call
  are in the journal; the server's token is not in the task.
- `doc/faq.md` and `doc/corporate-security.md` move the gap from "not covered" to what is covered.

## Open questions

- Whether stdio servers are in scope at all, started on the host for a task, or HTTP only as this plan says (the
  operator's).
- What the gateway does with a tool list that changed since the last start beyond journaling it: hold the task, ask
  the person, or refuse the call (the operator's, together with [PJ15](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj15) in `sokar-project`).

## To be checked

- Whether the agents' MCP settings come through `SetupContext` or through a file Sokar writes into the task (Agent
  Core's, with the agent repositories).
