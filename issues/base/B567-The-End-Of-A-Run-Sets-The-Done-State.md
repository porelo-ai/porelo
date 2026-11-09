# [B567](README.md#b567) — The End Of A Run Sets The Done State

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** M - a watch over the recorded work tasks, one rule read from the answer's last line, a write through the
adapter, an event and the end recorded for the window; no agent calls porelo.
**Blocked by:** [B565](README.md#b565), [B566](README.md#b566).

**What must be true.** When a task started for a story's work ends, porelod judges the end once. Its agent's run
finished, with no error, nothing unpushed, and the last line of its answer `Outcome: done`: porelod sets the work's
`done` state on the task's items that are still in the working state it set, through the adapter, writes an event,
and the work shows as done. Anything else (an error, unpushed work, `Outcome: blocked - <why>`, no `Outcome` line, a
task stopped before its run ended): the states stay, and the work shows as waiting for the operator, with why and
the agent's answer. Either way the window lists it under *Needs you* from what porelod answers; the window decides
nothing.

## Plan

Read in `TaskInventory` (`Activity.ENDED` only while the container is up and its agent declared an end),
`AgentEnding`, `PoreloDaemon.Watches` (`UpstreamWatch`, `MessageWatch`) and `EventLog`.

- `apps/app-planning`: `WorkEnds`, a pure judge from `AgentEnded` (with `answer`) or a stopped task to an end
  `DONE | BLOCKED | FAILED` with `why`; `PlanningWatch`, beside the daemon's other watches, reads the inventory every
  few seconds for the tasks in `PlanningWork`, judges each end once, writes it into the record, sets `done` by one
  `SetIssueStates` (or `SetStoryState`) with the adapter's current revision, skipping items moved meanwhile, and
  signals `PlanningChanges` ([B563](README.md#b563)).
- `EventLog`: kind `planning-done` and `planning-blocked`, the task, its items and why; the kinds listed in
  `55-events.varlink`.
- `60-planning.varlink`: `PlanWorkTask.ended: ?WorkEnded(outcome, why, answer, at)`, answered by `GetStoryWork` and
  carried on `WatchStories`, so *Needs you* has the done one to look at and the blocked one to answer.
- Red first: `WorkEndsTest` - finished, 0 unpushed and `Outcome: done` judges DONE; the same with 2 unpushed judges
  BLOCKED "2 changes not pushed"; `Outcome: blocked - needs a token` judges BLOCKED with that why; an error judges
  FAILED.

## Acceptance

- With `StubPlanning` and a stub agent run, a done run moves its issues to the done state once, a blocked one moves
  nothing; each writes one event; porelod restarted meanwhile judges no end twice.

## To be checked

- How an attended (agent mode) work ends. Its session writes no end porelod reads today, so its end is the task
  stopped, and without an answer it is judged BLOCKED "ended without an Outcome line"; whether the agent's session
  record can give its last message, read in the agents' definitions.
