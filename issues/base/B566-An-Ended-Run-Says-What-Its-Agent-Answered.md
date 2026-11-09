# [B566](README.md#b566) — An Ended Run Says What Its Agent Answered

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** S - one field more in `AgentEnded`, read with what the agent API already declares; no plugin changes.
**Blocked by:** -.

**What must be true.** A task whose agent's run ended carries `AgentEnded.answer`: the agent's last message, as the
record its definition declares as the last message gives it, else "". A long answer keeps its end, where a work
order asks for the last line, and says that it was cut.

## Plan

Read in `AgentEnding` (`app-model`), `RunResult` (`app-task`), `AgentWaiting.lastMessage`, `Waiting.LastMessage`,
`TaskInventory` and `15-task.varlink`.

- `AgentEnding` already reads the log's last 2 MiB; from the same lines it takes the record the agent declares as
  its last message (`Waiting.LastMessage.of`, as `AgentWaiting` and `RunResult` do), once per log size. Not
  `RunResult` itself: it lives in `app-task` with `RunFollower`, falls back to the run's last lines, which carry no
  agent's word, and keeps the first 16 KiB, cutting the end where `Outcome:` stands. The answer keeps the last
  16 KiB, cut at a code point.
- `AgentEnding.Ended` gains `answer` and `answerCut`; `asMap` carries them.
- `15-task.varlink`: `AgentEnded.answer: string` and `answerCut: bool`, said in words; the changelog entry.
- Red first: `AgentEndingTest` - a stub run whose declared last message is 20 KiB ending in `Outcome: done` reads
  `answer` ending in that line and `answerCut` true.

## Acceptance

- `List` and `Watch` carry the answer of an ended unattended run; an agent declaring no last message answers ""; a
  long answer keeps its last line.
