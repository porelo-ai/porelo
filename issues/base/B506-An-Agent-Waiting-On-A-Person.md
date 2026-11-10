# B506 — An Agent Waiting On A Person

```json
{
  "$schema": "issue-1.json",
  "id": "B506",
  "title": "An Agent Waiting On A Person",
  "state": "Backlog",
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
