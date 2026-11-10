# [B566](README.md#b566) — An Ended Run Says What Its Agent Answered

**Status:** Implementing, since 2026-11-10. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** M - grew from S on 2026-11-10: the answer (built, unpushed, `cf5115887` on `core-st294w1`), and `Done` with an
outcome and a reason answered as `Task.ended`; the contract, the durable record, the inventory and the task socket.
**Blocked by:** -.

**What must be true.** A task whose agent's run ended carries `AgentEnded.answer`: the agent's last message, as the
record its definition declares as the last message gives it, else "". A long answer keeps its end, where a work
order asks for the last line, and says that it was cut.

A task says how its work ended in a form a program reads, so that a planner outside porelo (porelo-planner, ST311)
needs nothing of porelo but the task functions. `Done(task, outcome: ?(DONE, BLOCKED), why: ?string)`: the agent's
own declaration through its task socket, as today, now with an outcome (DONE when omitted, as before) and a reason. It
is answered as `Task.ended: ?(outcome: DONE|BLOCKED|FAILED, why: string, at: string)`. FAILED is never declared; it is
derived from `agentEnded` (`finished: false`) when nothing was declared. The operator accepted this extension on
2026-11-10.

## Plan

Read in `AgentEnding` (`app-model`), `RunResult` (`app-task`), `AgentWaiting.lastMessage`, `Waiting.LastMessage`,
`TaskDone` (`app-base`), `TaskInventory`, `TaskMethods` (`Done`), `McpTaskSockets` (`Done` among `WRITING`) and
`15-task.varlink`.

Slice 1, the answer (built as `cf5115887`, to be taken onto main as it is):
- `AgentEnding` takes, from the lines it already reads, the record the agent declares as its last message
  (`Waiting.LastMessage.of`), once per log size, and keeps its last 16 KiB, cut at a code point.
- `AgentEnding.Ended` gains `answer` and `answerCut`; `15-task.varlink` `AgentEnded.answer` and `answerCut`.

Slice 2, the declared end:
- `TaskDone` records `{outcome, why, at}` instead of a bare mark, in the same private durable task record outside
  the mailbox mount; a record from before reads as DONE with `why` "". `why` is at most 1 KiB of UTF-8, one line,
  control characters refused: it is the one free text `Done` takes, and it reaches only the task's reader, never
  another agent's prompt.
- `Done` takes `outcome` and `why`, still only from the task's own socket (`NotItsOwn` otherwise); BLOCKED without a
  `why` is refused. A later start clears it, as today.
- `TaskInventory` answers `Task.ended`: the declaration, else FAILED with `agentEnded.error` as `why` when the run
  ended unfinished, else absent. `activity` stays as it is: DONE for a declared DONE as today; a declared BLOCKED
  leaves activity to what the task does.
- `15-task.varlink`: `Done`'s parameters and `Task.ended`, said in words; doc/commands.md; the changelog entry.
- Red first: `TaskDoneTest` (old record reads DONE; BLOCKED with its reason; an overlong or multi-line `why` refused),
  a daemon test over a task socket (`Done` with BLOCKED answers `ended` in `List`; over the person's socket
  `NotItsOwn`), and an inventory test deriving FAILED from an unfinished run.

## Acceptance

- `List` and `Watch` carry the answer of an ended unattended run; an agent declaring no last message answers ""; a
  long answer keeps its last line.
- A task that declares BLOCKED with a reason answers `ended` with that outcome and reason; one that declares nothing
  and whose run ended unfinished answers FAILED with the run's error; a declaration from before answers DONE.
- No call but the task's own can declare its end, and no `why` beyond its bound is stored.
