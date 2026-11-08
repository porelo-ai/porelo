# [B515](README.md#b515) — A Start That Did Not Stream Answers With The Run's Result

**Status:** Idea. Filed 2026-11-08 from the local MCP test with a prompt.
**Size:** M - an optional `result` on `Start`'s final reply, read by the agent's plugin from its own run, a field in
the agent API, and the plugins' part in their own repositories.

**What must be true.** A `Start` with a prompt that did not ask to stream gets back, in its one final reply, what the
run answered. An agent using `Start` as an MCP tool then sees the result without having to know to call `Tail` or
`Logs`.

## Why

In the coordinator's local test on 2026-11-08, a headless Claude Code called `Start` over porelo-mcp's stdio with a
prompt and no progress token. The task ran, and its `task.log` held the agent's result ("main, 5 files in the
repository root ..."), but the reply carried `exitCode: 0` and `output: []`. The caller never saw what the run
answered.

## Analysis

Read on 2026-11-08:

- `Start`'s final reply (`15-task.varlink`) has `output`, "everything that was printed, for a caller that did not ask
  to stream". An unattended run's agent writes to the task's log, not to the start's output, so `output` is empty.
- **It is porelo's side:**
  - Only the daemon knows the run ended, and only the agent knows which of its output is its answer: Claude Code's
    `result`, another agent's last message.
  - By the one architectural rule, porelo cannot parse an agent's output itself. The agent API gains a way for the
    plugin to say the run's result.
  - porelo-mcp then shows `result` as the tool's text, which is a small change on Agent MCP's side.

## Plan

- **The agent API:** an optional reading of the run's result, from the run's output, that the plugin implements. A
  plugin without it gives none, and `Start` then carries the run's last lines instead, bounded and marked as such.
- **The contract:** `Start`'s final reply gains `result: ?string`, bounded to 16 KiB with `resultCut: ?bool`. It is
  only added; Tasks1 only grows.
- **porelo-mcp:** the tool's text is `result` when present.
- **The plugins:** porelo-claude-code and the others implement the reading in their repositories, once the agent API
  is published.
- **Red first:** a stub agent's run whose final reply carries its result, and one without the reading that carries
  its last lines.
