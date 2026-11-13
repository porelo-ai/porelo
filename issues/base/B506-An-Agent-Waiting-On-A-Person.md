# B506 — An Agent Waiting On A Person

```json
{
  "$schema": "issue-1.json",
  "id": "B506",
  "title": "An Agent Waiting On A Person",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 17,
  "blockedBy": ["B118"],
  "covers": "A task's listing says when its agent waits on a person for its own question, where the agent declares how that is seen."
}
```

**Why this size.** read in the code on 2026-11-08, most of it is on the wire already and what is missing is
[B118](README.md#b118); first sized M for an activity in the task contract part (`15-task`), its judgment
in `TaskInventory`, and an agent definition field.

**What must be true.** A task's listing says when its agent waits on a person for its own question, not only for a
clearance, where the agent declares how that is seen; an agent that declares nothing is shown as working or quiet, as
judged from outside, and never as waiting by guess.

## Plan

Read in the code on 2026-11-08: most of this is on the wire already.

- An attached agent's own declaration is answered in `Task.screen` (`ScreenState`: `WAITING`, `NOT_WAITING`,
  `UNDECLARED`, `UNSEEN`) in `15-task.varlink`, read from the screen against the rules the agent's package declares.
- An unattended run's last message and whether it asked are in `Task.lastMessage` and `Task.asked` (`ASKED`,
  `DID_NOT_ASK`, `CANNOT_SAY`).
- What is missing is working and quiet for an attached task, observed from outside: that is
  [B118](README.md#b118). This issue adds nothing beside it unless the window needs more than `screen`, `asked` and
  its `AT_REST`.

## Acceptance

- The stub's declared waiting is answered; an agent without a declaration never is.

## Built (2026-11-13)

Nothing beside [B118](README.md#b118) was needed: the acceptance holds against its code, and only tests were added.

- **The stub's declared waiting is answered.** `StubAgentTest` takes the question the stub's script really asks
  when its workspace holds `.porelo-stub-asks` and reads it by the stub's own declaration: `WAITING`, for "whether to
  trust the folder", and never its rest. Seen to fail: with the declaration's `contains` changed, it reads
  `NOT_WAITING`.
- **Through the listing.** In `TaskActivityTest`, an attached agent declaring its question and its rest, as the stub
  does, whose screen shows that question, is listed with `screen` `WAITING` and `screenWaitingFor` saying what for in
  the row a client is sent, `task status` says "reads as waiting for you - …", and its activity is neither `at rest`
  nor `working`. Seen to fail: a declared match read as not waiting.
- **Never by guess.** An attached agent that declares no screen rules, whose screen shows a question, is listed
  `UNDECLARED` with no `screenWaitingFor`, activity `unknown`, no `waitingFor`, and `task status` says it cannot say.
  Seen to fail: activity `waiting` for an attached task with no log, and `WAITING` for an undeclared screen.
- **Not only a clearance.** A clearance question alone is activity `waiting` with its destination in `waitingFor`
  while the screen reads `NOT_WAITING`; the agent's own question alone is `screen` `WAITING` with an empty
  `waitingFor` and an activity other than `waiting`; both open at once are each said by their own field. Seen to
  fail: the screen's question carried into `waitingFor`.

The agent's own question shows in `screen`, which the contract keeps apart from `activity` as a reading, not a fact:
its `activity` stays `unknown`, and `task list`, which prints only `ACTIVITY`, does not show it. `task status` and the
daemon's `Task` on `List` and `Watch` do.
