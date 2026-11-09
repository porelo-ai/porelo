# [B568](README.md#b568) — A Coordinator While Agents Work

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** M - a task porelod starts, tells and stops on its own, keyed on the work record, with two fields more in
the contract.
**Blocked by:** [B565](README.md#b565), [B567](README.md#b567).

**What must be true.** A project has one coordinator task, which runs whenever any agent works on its stories. The
first work task of any working state starts it, if it is not running, with `planning.coordinator.order` filled in;
each later story or issue is told to it by a message. When the last recorded work task has ended or was stopped,
porelod stops it. `ProposeStoryWork` answers `coordinator: START` (none runs) or `TELL` (it runs);
`GetStoryWork` names it.

## Plan

Read in `WorkStart` and `PlanningWatch` ([B565](README.md#b565), [B567](README.md#b567)), `TaskControl.stop`,
`Tell`'s path and `Start`'s defaults for agent and model.

- `apps/app-planning`: `Coordinator` - task name `coordinator` in the project's own repository, started
  `UNATTENDED` with the filled order through the same start as the work, with `Start`'s defaults for agent and model;
  a running one is brought into the record, not started twice; told by `Tell` with each new story and its tasks;
  stopped (not removed) by `PlanningWatch` when no work task runs. Never judged as work by `WorkEnds`.
- `PlanningWork` keeps the coordinator apart from the work tasks.
- `60-planning.varlink`: `coordinator: (START, TELL)` and `coordinatorTask: string` on `ProposeStoryWork`,
  `coordinator: ?string` on `PlanWork`.
- An attended work with several repositories opens the coordinator's session to the operator; with one, that task's.
- Red first: `CoordinatorTest` with a fake launcher - the first `StartStoryWork` starts the coordinator, the second
  tells it, the last work's end stops it.

## Acceptance

- One coordinator per project however many stories run; it is told each new one; it stops after the last work and
  starts again with the next.
