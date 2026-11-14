# B765 — One Dead Attended Task Fails Its Neighbours

```json
{
  "$schema": "issue-1.json",
  "id": "B765",
  "title": "One Dead Attended Task Fails Its Neighbours",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "A failed scenario of agent-waiting.feature leaves no task behind, and `WaitingSteps` reads the `task list` row of its own project, so one failure stays one red",
  "openQuestions": 0
}
```

**Severity:** high - one flaky scenario turns into three reds on a leg, and they hide which one failed first. A red
main blocks every push until somebody re-runs the job; nothing leaks.

**Why this size.** S: one step class in the acceptance kit, an `@After` and the feature's last steps; no product code.
Estimate: 38 minutes (Core's median for S).

**What must be true.** A scenario of `agent-waiting.feature` that fails leaves no task of its own on the account, and
no scenario reads the row of another project's task.

Seen on Build 43583612326, Leg fedora, on main 93ee09f90 (green one commit before):

- "an attached agent that puts a question to the person is shown waiting" (account accept2): porelo-waits_attended
  died at 10:20:41Z with `Exited (143)`, about 20 seconds after its start.
- Its last step, `porelo task remove porelo-waits_attended --force`, was skipped after the failed one, so the dead
  task stayed.
- "...that answered rests at its prompt and is said to" and "...at work is said to be working, never at rest" then
  failed on that dead row (`attended  waits  ...  Exited (143)  dead`). `WaitingSteps.listed` takes the first row
  whose name is `attended`, whatever its project.

## Plan

- `WaitingSteps.listed(task)` becomes `listed(project, task)`: it matches the row by task name and project column, as
  `porelo task list` prints them.
- The feature's last step `porelo task remove porelo-<project>_attended --force` moves into an `@After` of the kit, as
  `LiveTaskSteps` already does for the tasks it starts. The `@After` removes every attended task the scenario started
  through `I run "porelo task start attended ..."` and also runs after a failed step.
- Red first: a unit test of `listed` over a `task list` text with a dead row of another project before the scenario's
  own row. It is red today, since the dead row is taken.

## Built

- `WaitingSteps.row(list, task, project)` takes the `task list` row whose first two columns are the task's name and
  its project. A dead task of the same name in another project is no longer read.
- `TerminalSteps` keeps the container of each `porelo task start NAME ... --project PROJECT` the scenario typed. Its
  `@After` removes them when the scenario failed, best effort and logged, as `LiveTaskSteps` does for the tasks it
  starts.
- The feature's own last removal steps stay. A hook on every scenario would change the end of 23 typed starts in ten
  features that this fix's targeted run does not cover. Run only after a failure, it touches no passing scenario.
- Red first: `WaitingStepsTest` - a dead `waits` row above the `rests` row, in the layout the leg printed, was taken
  (red), then green. `TerminalStepsTest` names the container for three start forms and none for four other commands.
- Green: the unit tests of `acceptance/kit` and `acceptance/suite` (`-Pwhole`), 0 skipped there. The feature itself
  runs on the legs of this commit's Build.

- The reading (agy, gemini-4-argon-high, 10:45:55-10:55:06Z), each finding checked in the code, fixed after the
  push in one commit on top:
  - Red, fixed: the removal and `closeTheTerminal` had the same order. A start still running in the terminal holds
    its task's reservation, and a removal beside it is refused. The removal now runs at order 9000, after the
    terminal is closed; Cucumber runs the higher order first.
  - Red, fixed: the container was read before `World.expand`. A `${NAME}` in the task or project was missed. It is
    read from the expanded command now.
  - Yellow, fixed: the pattern missed `; ...` right after the project, `cd ... && porelo task start`, `-p` and
    `--project=`. It took an option for the task when none is named, and named project porelo's container wrongly.
    It now matches one command of a line, needs a task name first, and names the container through
    `ReadySteps.container`. Six cases were red, then green.
  - Yellow, fixed: `row` took `(task, project)` where every other helper takes `(project, task)`.

## To be checked

- Who sent SIGTERM to porelo-waits_attended. The job log has no podman events. The parallel scenarios that stop or
  clear run on their own accounts or later (the locked-out one starts at 10:20:55Z). Find it from a re-run that
  fails again, with `podman events` of the account kept as an artifact.
- In the same run, "busy"'s own status said `activity waiting` ("its commit waits at the gate"), not `working`. Check
  whether that scenario can fail on its own once the row is right.

## Acceptance

- The unit test seen red, then green; agent-waiting.feature green on a leg.
