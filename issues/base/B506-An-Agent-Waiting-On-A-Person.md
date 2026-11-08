# [B506](README.md#b506) — An Agent Waiting On A Person

**Status:** Backlog.
**Size:** M - an activity in the task contract part (`15-task`), its judgment in `TaskInventory`, and an agent
definition field.

**What must be true.** A task's listing says when its agent waits on a person for its own question, not only for a
clearance, where the agent declares how that is seen; an agent that declares nothing is shown as working or quiet, as
judged from outside, and never as waiting by guess.

## Plan

- An agent definition field naming how its waiting is seen, beside the at-rest judgment of the attached screen;
  `TaskInventory` answers a `WAITING_ON_PERSON` activity, additive under `Tasks1`.
- Red first: a unit test with the stub agent declaring it.

## Acceptance

- The stub's declared waiting is answered; an agent without a declaration never is.
