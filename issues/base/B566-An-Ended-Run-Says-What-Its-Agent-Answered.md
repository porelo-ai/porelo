# B566 — An Ended Run Says What Its Agent Answered

```json
{
  "$schema": "issue-1.json",
  "id": "B566",
  "title": "An Ended Run Says What Its Agent Answered",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 5,
  "blockedBy": [],
  "covers": "`AgentEnded.answer`, the agent's declared last message, its end kept; `Done(outcome, why)` answered as `Task.ended`, FAILED derived."
}
```

**Why this size.** grew from S on 2026-11-10: the answer (built, unpushed, `cf5115887` on `core-st294w1`), and `Done` with an
outcome and a reason answered as `Task.ended`; the contract, the durable record, the inventory and the task socket.

Implementing since 2026-11-10; in Review since 2026-11-10, until the operator's test. Slice 2 built by Codex
under Core's supervision. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.

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

## As built

**Slice 1, the answer** (`cf5115887`, taken onto main as it was):

- `AgentEnding` reads the answer from the lines it reads for the end: the log's last 2 MiB, whole lines, given to the
  `Waiting.LastMessage` the agent's definition declares, cached with the end per log size. A last-message record
  whose line starts before those 2 MiB is not seen, and the answer is then an earlier one or "".
- An answer longer than 16 KiB keeps its last 16384 bytes, moved forward to a whole character; `answerCut` is then
  true. `Ended.answer`/`answerCut` reach `List` and `Watch`; `15-task.varlink` `AgentEnded.answer`, `answerCut`.
- Tests: `AgentEndingTest` (a 20 KiB answer keeps its last line and is cut; none declared is ""; cuts inside 3- and
  4-byte characters stay valid; one parse per log size), the daemon's `EndedAnswerMethodsTest`.

**Slice 2, the declared end:**

- `TaskDone` keeps `{outcome, why, at}` as JSON in the same private durable record, written atomically. A bare `done`
  mark from before reads as DONE with `why` "" and the file's modification time as `at`; an unreadable or oversized
  record reads as none. `why` is at most 1024 bytes of UTF-8, one line, with no control character and no line or
  paragraph separator; BLOCKED needs a non-blank `why`. Each is refused, never cut.
- `Done(task, outcome: ?(DONE, BLOCKED), why: ?string)`: still only from the task's own socket (`NotItsOwn`
  otherwise); a bad `outcome` or `why` is `InvalidParameter` naming it. A later start clears it, as before.
- `Task.ended: ?(outcome: DONE|BLOCKED|FAILED, why, at)`: the declaration, else FAILED with the run's `error` and its
  `at` when the run ended unfinished, else absent. `activity` is DONE only for a declared DONE; BLOCKED leaves it as
  observed. A task socket without Logs or Tail gets no `ended`, as it gets no `agentEnded`, since `why` is the
  agent's own text.
- `15-task.varlink` (`Done`'s comment now says the one bounded line of free text and who reads it), `doc/commands.md`,
  the reach table (`Reach`, `doc/reach.md`), changelog `declared-end-outcome.yml`.
- Tests, each seen red first: `TaskDoneTest` (old mark reads DONE; BLOCKED with its reason; overlong, multi-line,
  control-character and BLOCKED-without-reason refused; a bad timestamp), `TaskInventoryEndedTest` (FAILED derived
  from an unfinished run; absent for a finished one; a declaration wins), `TaskDoneMethodsTest` over a task socket and
  the person's, `McpTaskSocketsTest`. wire, app-base, app-egress, app-model, apps/app (4320) and daemon (378) whole:
  green.
