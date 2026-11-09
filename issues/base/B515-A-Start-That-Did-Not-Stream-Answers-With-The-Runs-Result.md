# [B515](README.md#b515) — A Start That Did Not Stream Answers With The Run's Result

**Status:** Review. Filed 2026-11-08 from the local MCP test with a prompt; built the same day, before
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
- **porelo-mcp:** the tool's text is `result` when present.
- **Red first:**
  - a stub agent's run whose final reply carries its answer, with and without `more`;
  - an agent without `last_message`, and a timed-out run, which carry their last lines as `LAST_LINES`;
  - a resumed run that never answers with the previous run's lines, and a refused start that carries no result;
  - a streamed start whose lines include the stub agent's own, and a caller that never reads, whose run still ends at
    its timeout;
  - over a task's socket, a `Start` without `Logs` or `Tail` in the section that carries no result.

## As built

Built on 2026-11-08 on the plan above, with one plan reading and one code reading (agy gemini-4-argon-high), each
finding fixed with a test seen red first.

- **`RunResult`** reads the run's answer at its end from the last 2 MiB of `task.log`, never through a link: the record
  the agent's `last_message` names (`ANSWER`), or else its last 40 lines as the agent shows them (`LAST_LINES`). It
  formats only the log's last lines, in parts far below a Varlink message's limit. A run that showed nothing answers
  nothing. At most 16 KiB of UTF-8, cut at a code point.
- **`RunFollower`** sends the run's lines while it lasts, on a reader and a sender thread with a bounded queue: a caller
  that stops reading loses lines, counted and said in a last `runLine`, and never holds the run.
  - No thread is interrupted, since an interrupted thread entering a socket's write closes it: the agent's, which
    formats the lines, or the caller's, which still waits for the final reply.
  - Its close waits for the sender, so the start writes nothing to the caller while the sender does.
  - It reads in batches of 512 KiB, also at the end, and cuts a line longer than 16 KiB at a code point.
- **`TaskLaunch.runAgentSteps`** forgets an earlier call's result first, empties `task.log` in place (a link is removed,
  never followed) and refuses to run when it cannot, follows the run when the caller streams, and closes the follower
  before it writes anything else.
- **`Start`** carries `result`, `resultKind` and `resultCut` on its final reply in both modes, and streams `runLine`.
- **Over a task's MCP socket** whose section names neither `Logs` nor `Tail`, `Start` carries none of the four, and
  `List` and `Watch` leave out each task's `lastMessage` and `agentEnded`, which are read from the same log.
- **Acceptance:** `task-unattended`'s daemon start checks `"result":"stub: finished"` and `ANSWER`.
- **Held, from the code reading:**
  - A caller that never reads still holds the start's final reply, and with it a task's place among its children at
    once, as every streamed reply did before; the run itself ends at its time.
  - `Tail` across a resumed run, and `AgentEnding`'s cache by size, read a log emptied and grown again as before:
    emptying it in place is what `podman exec` already did.
  - The follower still runs for a caller whose section strips its lines: a cost, not a leak.

