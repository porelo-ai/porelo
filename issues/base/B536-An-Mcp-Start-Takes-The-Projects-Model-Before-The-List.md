# [B536](README.md#b536) — An MCP Start Takes The Project's Model Before The List Judges It

**Status:** Idea. Filed 2026-11-09 from [B533](README.md#b533)'s code reading, finding 1, as the operator decided it
(the operator's decision of 2026-11-09 on the open points of wave 2, point 3: leave it now, file the change as an Idea).
**Severity:** low: nothing passes that should not; an agent must name a model the project would have chosen anyway.

**What must be true.** A task's MCP `Start` that names no model, for an agent whose `mcp.agents.<agent>` lists models,
starts on the project's (or repository's) default model when that model is on the list, instead of being refused.

## Why

`McpTaskSockets` judges the list on the model the call names, before any file is read; settling the model needs the
provider, which only `TaskLaunch` resolves from the agent's sign-in. The start checks the settled model for every start
with a parent, so the refusal is only stricter than needed.

## Plan, roughly

- The daemon settles the provider and model before judging the list, or the list check moves into `TaskLaunch` for an
  MCP start; `AgentNotAllowed` stays the named answer either way, and its tests follow. Size M.
