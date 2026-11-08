# [B515](README.md#b515) — A Start That Did Not Stream Answers With The Run's Result

**Status:** Backlog. Filed 2026-11-08 from the local MCP test with a prompt; built first, before
[B514](README.md#b514) (the operator, 15:23Z).
**Size:** M - an optional `result` on `Start`'s final reply, read with what the agent API already declares, and the
run's own lines on a streamed start; no new reading in the agent API and nothing in the plugins' repositories.

**What must be true.** A `Start` with a prompt that did not ask to stream gets back, in its one final reply, what the
run answered. An agent using `Start` as an MCP tool then sees the result without having to know to call `Tail` or
`Logs`. A `Start` that streams ("more") also streams the run's own lines as the agent writes them, not only porelo's
setup, and ends with the same result.

## Why

In the coordinator's local test on 2026-11-08, a headless Claude Code called `Start` over porelo-mcp's stdio with a
prompt and no progress token. The task ran, and its `task.log` held the agent's result ("main, 5 files in the
repository root ..."), but the reply carried `exitCode: 0` and `output: []`. The caller never saw what the run
answered.

The coordinator's second test, at 14:38Z, used a progress token. `Start` streamed 38 lines, all of them porelo's own
setup ("running claude (up to 5 minutes)", "session a fresh one"), then the final reply with `output: []`. The agent's
answer never came, though `Start`'s description says that with "more" it streams lines as they happen.

## Analysis

Read on 2026-11-08, and again in the plan reading (agy gemini-4-argon-high), which changed the plan:

- `TaskLaunch.runAgent` writes the unattended run to the task's `task.log` (`Podman.execute` redirects the agent's
  output there) and prints nothing of it to the start's output. So neither the stream nor `output` carries it.
- **Why `output` was empty:** porelo-mcp always calls `Start` with `more`, so `output` is `[]` on the final reply
  (`TaskMethods`), whether or not the MCP caller sent a progress token; porelo-mcp then drops the streamed lines. A
  caller without `more` gets porelo's setup lines in `output`, never the agent's. So `result` belongs on the final
  reply in both modes.
- **The agent API has the seam already.** An agent's definition declares which record of its run is its last message
  (`session.waiting.last_message`, `Waiting.LastMessage`). `AgentWaiting.lastMessage` reads it into
  `Task.lastMessage`, cut to 500 characters. The stub declares it (`{stub: result}`, text `said`), and Claude Code's
  `{"type":"result","result":...}` is tested in `AgentWaitingTest`. `Agent1.Format` already turns a run's lines into
  readable ones, as `Tail` shows them. So porelo names no agent and the plugins change nothing.

## Plan

- **The result:** at the run's end, `TaskLaunch.runAgentSteps`, which holds the agent, reads the run's last message
  with its `last_message` declaration, bounded to 16 KiB cut at a code point. An agent that declares none, or a run
  that wrote no such record (a timeout, a crash), gives the run's last formatted lines instead, bounded the same way.
- **Only this call's run:** the result is read only when this call ran the agent. A `Start` that found the task
  running, was refused, or brought back a task without running it carries none. `task.log` is emptied before the run,
  so a resumed run never answers with the last run's lines.
- **The stream:** when the caller streams, a follower on its own thread reads `task.log` in bounded pieces while the
  run lasts and sends each line, formatted by the agent as `Tail` sends it. It never blocks the run or its timeout: a
  caller that stops reading loses lines, which are counted and said, rather than holding the run. It reads once more
  after the run ends or times out, and opens the file without following a link.
- **Over a task's MCP socket:** the result and the run's lines are given only when the section also allows `Logs` or
  `Tail`. Otherwise `Start` would hand out what those methods withhold.
- **The contract:** `Start`'s final reply gains `result: ?string`, `resultKind: ?string` (`ANSWER` or `LAST_LINES`) and
  `resultCut: ?bool`. It is only added; Tasks1 only grows. A streamed run line comes as `runLine: ?string`, apart from
  porelo's own `line`, so a caller tells the agent's lines from porelo's.
- **porelo-mcp:** the tool's text is `result` when present (Agent MCP, MC09).
- **Red first:**
  - a stub agent's run whose final reply carries its answer, with and without `more`;
  - an agent without `last_message`, and a timed-out run, which carry their last lines as `LAST_LINES`;
  - a resumed run that never answers with the previous run's lines, and a refused start that carries no result;
  - a streamed start whose lines include the stub agent's own, and a caller that never reads, whose run still ends at
    its timeout;
  - over a task's socket, a `Start` without `Logs` or `Tail` in the section that carries no result.
