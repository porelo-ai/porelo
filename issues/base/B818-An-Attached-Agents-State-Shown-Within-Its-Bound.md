# B818 — An Attached Agent's State Shown Within Its Bound

```json
{ "$schema": "issue-1.json", "id": "B818", "title": "An Attached Agent's State Shown Within Its Bound",
  "state": "Implementing", "size": "S", "priority": "High", "rank": 141, "blockedBy": [],
  "covers": "The three acceptance scenarios of an attached agent's shown state (working, at rest, waiting) failed together once on the fedora leg within their 60 s and passed on a rerun with no change; the cause is found and fixed, never retried away" }
```

**Why this size.** S: one step class of the acceptance kit and, where the cause lies there, the reading of a task's
screen in `app-task`; no contract.

**What must be true.** The Build's scenarios of an attached agent's shown state pass or fail by what Porelo shows,
never by how fast a leg starts a task.

## Found

On one Build (fedora leg, first attempt), the scenarios "an attached agent at work is said to be working, never at
rest", "an attached agent that answered rests at its prompt and is said to" and "an attached agent that puts a
question to the person is shown waiting" failed together: "Porelo did not settle on showing the stub agent working
within 60s" (and at rest, and waiting). The rerun of the same commit passed both legs; no other run in seven days
failed them.

Read in the job log of that attempt (fedora leg), each scenario on its own account:

- **waits:** started at 10:20:22Z; its container ended at 10:20:41Z with `Exited (143)`, a SIGTERM, about 19 seconds
  later, while the step still read its status (until 10:21:39Z). The status said `activity dead`. Who sent the SIGTERM
  is not in the log, which holds no podman events.
- **rests:** reached work at 10:22:58Z and then read the dead `waits` row, which the kit took by task name alone. That
  is fixed in the kit already: `WaitingSteps` reads the row of its own project, and a failed scenario's typed starts
  are removed after it.
- **busy:** reached work at 10:24:35Z; its status then said `activity waiting`, "its commit waits at the gate". With
  `.porelo-stub-works` at 2 seconds the stub commits at every step and pushes at every tenth, so about 20 seconds after
  its start. A push held at a guarded gate rightly reads as waiting for a person, so the scenario raced its own stub.

Measured on the fedora VM (2 CPUs, 3 GB, snapshot 561 rpm, the stub in the account), the three scenarios alone, 7 runs,
all green; seconds per step (min-max):

| Scenario | Reaches work | Expected state read |
|---|---|---|
| waits (shown waiting, 60 s bound from the task's existence) | - | 33.8-55.1 |
| rests | 18.2-27.0 | 14.7-16.2 |
| busy | 17.9-35.7 | 9.8-12.6 |

In runs 1 to 3 busy reached work after its first push and still read working, so the race depends on when the gate's
waiting is read; it did not lose on the VM.

## Plan

- Measure first, from the Build's suite log of the failed attempt and of passing runs: when each of the three tasks
  was created, when it first answered a status with a screen, and when the expected state was first read.
- Fix the cause found:
  - where the start's cost is what ran out, the bound counts from the agent's first screen, not the task's creation,
    with its own bound for the start, each named in the failure;
  - where the status went on reading no screen or a wrong one, the screen reading is fixed in `app-task` with a unit
    test over a recorded screen, seen red first.
- No retry, and no longer bound without the measurement that shows the real cost.

## Built

- `agent-waiting.feature`: the busy scenario's stub works at a 30-second step, so its first push comes at step ten,
  about 300 seconds in, long after the scenario's bound. Its subject, working and never at rest, no longer involves the
  gate. Test data only; no product code.

## Acceptance

- The measurement is written here with its numbers; the fix names which of the two it was.
- The three scenarios pass on both legs of the Build that carries the fix, and the failure, provoked once by a start
  held back locally, names what it waited for.

## To be checked

- Who sent waits' SIGTERM 19 seconds after its start. The next failing run is read with the account's `podman
  events` kept beside the suite log; seven runs on the VM did not reproduce it.
- waits read waiting after up to 55.1 seconds of its 60, measured from the task's existence: the start's cost sits
  inside the bound. Per the plan, the bound counts from the agent's first screen, each part named in the failure. Not
  built yet.
- The repeated runs (ten per leg) of the three scenarios with this fix, on both legs.
