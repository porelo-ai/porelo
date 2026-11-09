# [B564](README.md#b564) — States Moved On The Board

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** M - one batch write in the contract, checked against the workflow and `planning.work`, all or none through the
adapter.
**Blocked by:** [B562](README.md#b562).

**What must be true.** The board's Save moves states through porelod, and only into a state that starts no work:
`SaveMoves(project, moves: [](story: ?string, issue: ?string, to: string), revision) -> (revision, moved: []string)`
saves all pending story and issue moves together against the board's revision, all or none. Each move names exactly
one of story or issue. A story move changes every issue in the story's state and leaves issues further on where
they are, or sets the state of a story without issues; an issue move changes that issue alone.
A working state (`WORKING` in the workflow, a key of `planning.work`) is entered
only by starting its work ([B565](README.md#b565)), never by a change of state alone: a move into one is refused with
`EnteredOnlyByWork(item, to)`, its own error so the window can offer the work's dialog instead. A move the adapter
refuses or one against an old revision is refused too. No prompt, no signature.

## Plan

Read in `PlanningMethods` and `StoryStates` ([B562](README.md#b562)).

- `apps/app-planning`: `PlanningMoves` turns a story move into issue moves by `StoryStates`, checks each target, and
  calls the adapter's `SetStates(changes: []StateChange, revision)` once for all story and issue changes.
- `60-planning.varlink`: `SaveMoves(project, moves: [](story: ?string, issue: ?string, to: string), revision)`
  `-> (revision, moved: []string)`; errors
  `EnteredOnlyByWork(item, to)`, `MoveNotAllowed(item, from, to, reason)` for a state the workflow lacks,
  `StaleRevision(revision)`, the adapter's `StateNotAllowed` passed on. Nothing of a move with one refused item is
  written.
- A successful move signals `PlanningChanges` ([B563](README.md#b563)).
- Red first: `PlanningMovesTest` - a story in Backlog with issues in Backlog and Review, moved to Rejected, moves the
  Backlog issue alone; a move into In analysis is refused with `EnteredOnlyByWork`.

## Acceptance

- Moves reach the stub all or none; a working-state target, a stale revision and an adapter's refusal are each
  refused by name and change nothing.
