# [B507](README.md#b507) — A Task That Starts Tasks Through An MCP Endpoint

**Status:** Analyzed.
**Size:** L - a per-task endpoint process, a section of the project file with its signed limits, the started task's
link to the task that started it in the event journal, and tests on each side; no contract change to `Tasks1` is needed
beyond the journal field.

**What must be true.** A task the signed project file allows is offered an MCP endpoint on loopback, reached as its
gate is reached. Through it the task starts a task (repository, issue, phase, agent, minutes), lists tasks, reads a
task's state and its protocol, and stops a task. Every answer and every refusal is the daemon's: the endpoint holds no
rule of its own. Within the file's limits (tasks at once, longest run, agents and models) a start needs nobody's word.
The endpoint stops only the tasks its own task started. Stopping every task stays a person's act. A task started this
way names the task that started it in the event journal.

## Why

The operator decided that the coordinator works from a task, `porelo-project` PJ103. The part in this repository is
the endpoint, the limits the project file sets, and the journal's record of who started what.

## Analysis

Read in the code on 2026-11-08.

**It runs in the person's own account, with no root.**
- `porelod` is a systemd user unit (`systemd/porelod.service`; `porelo setup` runs
  `systemctl --user enable --now porelod`, `doc/running.md`).
- Rootless podman runs the tasks. A task's firewall is loaded in its namespace through `podman unshare`
  (`shield/src/main/java/org/fuin/porelo/shield/EgressPolicy.java`).
- The gate a task pushes to is a `porelo gate serve` process the start launches as the same user, inside a scope with
  `GateWiring.LIMITS` (`apps/app-task/src/main/java/org/fuin/porelo/app/GateWiring.java`).
- It binds loopback. The container reaches it at `host.containers.internal`, which `LoopbackMapping` points at the
  host's loopback (`TaskWorkspace.containerVisibleHost`, `gate/src/main/java/org/fuin/porelo/gate/GitHttpServer.java`).
- A per-task token is required on every request.
- The endpoint takes the same path: a `porelo task serve-mcp` process per allowed task, started and recorded beside
  the gate's so a resumed task gets it again. It binds loopback with a token of its own, and the task's firewall allows
  it as it allows the gate. It calls `porelod` over the user's own varlink socket. Nothing in this needs root.

**Backed by existing methods.**
- `Start`, `List`, `Status`, the task's protocol (`Tail` and the event journal's `Events`), and `Stop`.
- The endpoint passes the arguments through and returns the daemon's answer. A daemon refusal reaches the agent as an
  MCP error carrying the refusal's name and words.

**Limits, in the project file.**
- A section naming the agents and models a started task may use, the tasks at once, and the longest run.
- It is signed with the file, so changing it is an admin's commit, as every other setting.
- The endpoint counts its own task's running children before a start and refuses past the limit with a named
  refusal. That limit is the file's, so it is not a rule of the endpoint's own.
- `minutes` above the longest run is refused.

**Stopping only its own.**
- The parent is recorded in the started task's profile, beside its branch and prompt.
- `Stop` through the endpoint refuses a task whose recorded parent is not the endpoint's task.
- No endpoint call stops every task.

**The journal.** A start through the endpoint records the starting task's name in its `TASK_STARTED` event, so the
journal and `Events` show who started what.

**Open for the plan, not for the operator:**
- Whether the MCP transport is streamable HTTP or SSE: whichever the shipped agents' MCP clients take today, checked
  per agent.
- Whether the protocol call is the task's log (`Tail`) or its events (`Events`), or both.
