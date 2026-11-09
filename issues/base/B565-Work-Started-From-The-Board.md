# [B565](README.md#b565) — Work Started From The Board

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** L - four methods, a record of running work that outlives porelod, and `Start`'s checks made callable from
a second place without growing apart.
**Blocked by:** [B562](README.md#b562).

**What must be true.** Dragging a story into a working state opens a dialog porelod fills: `ProposeStoryWork(project,
story, to)` answers the allowed modes, the proposed agent and model, the work order filled in, and the tasks it would
start (`per: story` one, in the project's own repository; `per: repository` one per repository of the story's issues
that are in a `from` state, each with its issues). `StartStoryWork(project, story, to, mode, agent, model, revision)`
checks the story's state against `from` and the mode against `modes`, sets the working state through the adapter
(the only way into a working state: a move into one is refused, [B564](README.md#b564)),
starts the tasks with no name and no repository typed, and records which task works on which items towards which
done state. `GetStoryWork` answers that record; `StopStoryWork` stops the story's tasks.

## Plan

Read in `TaskMethods`' `Start` handler (its checks before `TaskLaunch`: subagents, `ProjectTaking`,
`StartCheck.unknownHost`, `StartCheck.undeclared`), `TaskLaunch`, `TaskControl`, `Tell` in `MessagingMethods`, the
`started` kind of `EventLog`, and `Start.repository`'s rule that the project's own repository is how an agent gets a
task for planning.

- `TaskMethods`: the `Start` handler's checks and launch move into one method that `Start` and the work both call.
- `apps/app-planning`:
  - `PlanningWork`, the record, one file per project under `$XDG_STATE_HOME/porelo/planning/<project>/work.json`:
    per task its items, working state, done state, mode, agent, model, since, and later its end
    ([B567](README.md#b567)).
  - `WorkProposal`: the tasks and the order, its `{story}`, `{issues}` and `{tasks}` filled from the adapter and the
    record; the model from `planning.work.<state>.model` for the agent's provider, else `Start`'s own default.
  - `WorkStart`: sets the items' working state by one `SetIssueStates` (or `SetStoryState`), then starts each task
    with `now`: `UNATTENDED` with the order as its prompt; the attended mode without a prompt, the order put in its
    inbox as `Tell` does. Task names from the story and the repository, cut to fit as a start cuts them. A task that
    fails to start sets its items back and is answered by name. An `EventLog` line `planning-started` per task.
- `StopStoryWork` stops the story's tasks and leaves the items in their working state, the record marking them
  nobody at work (`PlanWork.atWork` false), so the board shows them so; the operator moves them on the board.
- `60-planning.varlink`: the four methods, `PlanWork`/`PlanWorkTask` types; errors `WorkNotAllowed(from, to,
  reason)`, `WorkRunning(story, tasks)`, `StaleRevision`.
- Red first: `WorkStartTest` with `StubPlanning` and a fake launcher - a story in Accepted moved to In analysis
  starts one task in the project's own repository with the filled order; a story in Idea is refused by `from`.

## Acceptance

- Proposal and start agree on the tasks; the record survives porelod's restart; `from`, `modes` and a second start of
  a working story are refused by name; a failed start leaves the items as they were.

## To be checked

- Whether a start on a host that needs its certificate from the person's computer can run from this call, which
  streams nothing to certify with; if not, it is refused there with `Start`'s words.
