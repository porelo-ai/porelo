# B737 — A Task's Guide Names Its MCP Endpoint

```json
{
  "$schema": "issue-1.json",
  "id": "B737",
  "title": "A Task's Guide Names Its MCP Endpoint",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 10,
  "blockedBy": [],
  "covers": "A task whose project gives it an MCP endpoint is told so in its guide: where it is, the token it holds, the methods it carries, and for ProposeIdea how to propose an idea and within which bounds, so any agent with a shell can use it.",
  "openQuestions": 0
}
```

**Why this size.** S: one more section in the task guide's text, written only for a task with an endpoint, the
start passing what the endpoint carries, and tests. Estimate: 45 minutes (Core's median for S).

**What must be true.** An agent in a task with an MCP endpoint learns from its guide that it has one and how to call
it, and, where the endpoint carries `ProposeIdea` and the project's `limits.ideas` allows ideas, how to propose an
idea for itself. Without that, no agent finds the endpoint: none registers it as an MCP server of its own.

Found in a walk on the test VM: a project with `mcp.methods: [ProposeIdea]` and `limits.ideas: 1`, a task of agent
pi asked to propose an idea "as your task's instructions describe". Its tools were bash, read and write; its guide
said nothing about the endpoint or ideas, so it wrote the idea as mail. Read at the stack's head: the start serves
the endpoint through the gateway as `$PORELO_URL_PORELO_MCP` with `$PORELO_TOKEN_PORELO_MCP` (MCP over HTTP at that
URL as it stands, a bearer token), and porelo-mcp offers the endpoint's methods as tools generated from the daemon's
interface. `TaskGuideText` has sections for pushing, handed-in files, builds, the mailbox and the start's values,
none for the endpoint, and no agent definition declares an MCP server of the task.

## Plan

- `TaskGuideText`: a section written only when the task has an endpoint: the variables, that a request goes to
  `$PORELO_URL_PORELO_MCP` as it stands with `Authorization: Bearer $PORELO_TOKEN_PORELO_MCP`, MCP's own
  `initialize`, `tools/list` and `tools/call`, and the methods the endpoint carries by name.
- Where it carries `ProposeIdea` and `limits.ideas` is above 0: how to propose an idea for this task (its own name,
  a one-line title of at most 120 characters, what must be true in at most 1000), how many it may propose, and that
  a person takes it; a worked call with `curl`.
- The start passes the endpoint's methods and the ideas bound to the guide; a task without an endpoint gets no
  section.
- First red, in the guide's tests: a task whose endpoint carries `ProposeIdea` with `limits.ideas: 1` has a guide
  naming `$PORELO_URL_PORELO_MCP`, `ProposeIdea` and the bound; one without an endpoint names none of them.

## Acceptance

- The guide of a task with an endpoint names it, its methods and, with ideas allowed, how to propose one.
- On the test VM, a task of an agent with a shell proposes an idea following its guide alone, and the idea is on the
  task's row.
