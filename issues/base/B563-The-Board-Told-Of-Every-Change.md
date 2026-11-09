# [B563](README.md#b563) — The Board Told Of Every Change

**Status:** Analyzed. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** S - one stream more, fed by the adapter's stream and by porelod's own writes; no new reading.
**Blocked by:** [B562](README.md#b562).

**What must be true.** `WatchStories(project)` answers the whole board first, the stories with their computed states
and the revision, then again on every change: one the adapter reports from elsewhere, one porelod wrote itself (a
move, a work started, a done state), and a work that started or ended. It never starts work. Called without `more` it
answers once, as `Watch` does. The window can ask the adapter to look at once.

## Plan

Read in `ProjectMethods`' `WatchProjects` (its `replies.streaming()` and the check that the client is still there)
and `TaskMethods`' `Watch`.

- `apps/app-planning`: `PlanningChanges`, one subscriber per project on the adapter's `WatchChanges`, opened with
  the first watcher and closed with the last; porelod's own writes and work records signal it too. An adapter without
  the `watch` capability is read again every few minutes.
- `60-planning.varlink`: `WatchStories(project)`, the same shape as `ListStories`, and `RefreshStories(project)`,
  which asks the adapter's `Refresh` and returns its revision.
- `PlanningMethods`: both, each stream ending when its client is gone.
- Red first: a test client on `WatchStories` with `StubPlanning` receives a second reply after the stub changes an
  issue's state from outside.

## Acceptance

- A change from outside the machine, a move and a started work each reach a watching client within one reply; a
  closed client's stream ends; the adapter's stream is closed with the last watcher.
