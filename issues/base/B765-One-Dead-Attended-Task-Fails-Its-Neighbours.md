# B765 — One Dead Attended Task Fails Its Neighbours

```json
{
  "$schema": "issue-1.json",
  "id": "B765",
  "title": "One Dead Attended Task Fails Its Neighbours",
  "state": "Implementing",
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

## To be checked

- Who sent SIGTERM to porelo-waits_attended. The job log has no podman events. The parallel scenarios that stop or
  clear run on their own accounts or later (the locked-out one starts at 10:20:55Z). Find it from a re-run that
  fails again, with `podman events` of the account kept as an artifact.
- In the same run, "busy"'s own status said `activity waiting` ("its commit waits at the gate"), not `working`. Check
  whether that scenario can fail on its own once the row is right.

## Acceptance

- The unit test seen red, then green; agent-waiting.feature green on a leg.
