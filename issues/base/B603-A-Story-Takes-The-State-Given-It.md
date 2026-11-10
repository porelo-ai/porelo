# [B603](README.md#b603) — A Story Takes The State Given It

**Status:** Backlog. Filed 2026-11-10 in `porelo-project` ST309 (A story takes the state the operator gives it), the porelo CLI's part, as the operator ordered it (2026-11-10): file only, not built now.
**Priority:** medium (the operator, 2026-11-10).
**Size:** M - the board read takes a story's own state over the derived one, a move answers a mismatch as a warning, and one command sets it with "anyway? y/N"; the window's part is porelo-frontend's, the stories index's porelo-buildtools'.
**Blocked by:** [B564](README.md#b564).

**What must be true.** A story put into a state by hand - moved on the board, or written in its head - has that
state, whatever its issues' states are. Today the board read derives every story's state from its issues
(`StoryStates.of`, the earliest open issue state) and has no own state to prefer. Where an issue's state does not
fit the story's state by that rule, porelo warns and never refuses: the command names the mismatch and asks
"… anyway? y/N", an unattended call needs `--yes`, and the daemon answers the mismatch as a typed warning a caller
confirms. A story with no state of its own keeps the derived one, as today.

## Plan

Read `StoryStates`, `PlanningReads` (`GetPlanningBoard`) and the moves [B564](README.md#b564) builds (`PlanningMoves`, `SaveMoves`),
and the planning adapter API's story record: whether an adapter can carry a story's own state, or the API needs a
field for it (porelo's planning API; porelo-adapters' stories-git then reads the head's `state`).

- The board read answers a story's own state when the adapter gives one, else `StoryStates.of` as today, and says
  which (`explicit` or `derived`).
- `SaveMoves` moving a story into a state its issues do not fit by the derived rule answers a typed
  `StoryStateMismatch` naming each issue and its state, and saves it only when the caller confirms.
- A command to set a story's state (proposed `porelo planning move STORY STATE`) that names the mismatch and asks
  "… anyway? y/N" at a terminal; `--yes` for an unattended call; nothing is saved on "no".
- Red first: an explicit story state wins over its issues' (unit); a move into a state its issues do not fit is
  answered as a warning and saved only on confirmation (unit and over the socket); a story without its own state is
  derived as today.

## Acceptance

- A story the operator puts into a state keeps it on the board read, with a warning naming the issues that do not
  fit; without its own state it is derived as before. No MCP.
