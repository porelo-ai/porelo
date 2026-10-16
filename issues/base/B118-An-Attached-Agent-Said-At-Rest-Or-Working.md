# B118 — An Attached Agent Said At Rest Or Working

**Status:** Analyzed.
**Size:** M - a contract change (a new `Activity` value), `app-model`, the stub agent's declaration and a scenario.

**What must be true.** `porelo task status` and `porelo task list` say whether the agent of an attached session is
`at rest` or `working`, read from its screen against the `session.at_rest` its definition declares, as `waiting`
is read today. A task whose agent declares no `at_rest` keeps saying `unknown`.

## Why

A task's activity is observed from outside where it can be: working and idle by the age of `task.log`. An attached
session writes to its terminal, not to that log, so for it the activity is always `unknown`. That was measured with
an omp task: `unknown` before its prompt, during a 15-second tool call and after its answer alike. The agents already
declare what their screen shows at rest, measured for every shipped agent, and the wake reads it to decide when a
message may be announced. Status and list read the same declaration, so this needs nothing new from an agent and
fits the decision that waiting for a person is never inferred from silence: `at_rest` is the agent's own
declaration, not silence.

It also closes, for attached tasks, the gap `porelo-frontend`'s `doc/Contract-Gaps.md` names: "a quiet task is shown
as a guess".

Rest is taken by [B120](README.md#b120)'s rule, held on two looks a short while apart, so status and the wake never disagree about
an instant between a prompt and the agent's working line.

## Plan

Read in the code at `30c8169b`:

- **Today `activity` has no value for rest.** `TaskInventory.Activity` and the varlink type `Activity`
  (`daemon/src/main/resources/varlink/org.fuin.porelo.Tasks1/15-task.varlink`) hold `DEAD`, `WAITING`, `WORKING`,
  `IDLE`, `ENDED` and `UNKNOWN`. `TaskInventory.activityOf` answers `UNKNOWN` for every task without `task.log`,
  which is every attached one.
- **`AT_REST` becomes a value of its own**, after `IDLE` in both. `IDLE` is inferred from a log that stayed quiet;
  rest is the agent's own declaration read from its screen, and a client should be able to tell the two apart. Adding
  a value is compatible for a client that renders an unknown one; `porelo-frontend` shows it as at rest.
- **The screen is already read for a listing.** `AgentWaiting` reads each attached task's screen from `ScreenFile`
  (a file the task writes, no `podman exec`), eight at once in `readScreens`, and keeps each read for `SCREEN` (3 s).
  So status and list need no new call into a container: the same read is judged against `at_rest` too.
- **Rest by the wake's rule.** `AgentWake.rests` (`apps/app-messaging/.../AgentWake.java`) is at rest when
  `at_rest` matches and `waiting` does not. That test moves into `app-model` (a small `Rest` beside `AgentWaiting`)
  and both use it. `AgentWaiting` keeps the reading before the last: a task is `AT_REST` only when this read and the
  one before, at least `AgentWake.LOOK` apart, both rest. A one-off `task status` with no earlier read looks a second
  time after `LOOK`.
- **Order in `activityOf`:** `DEAD`, then `WAITING` (`waitingFor`), then for an attached task whose agent declares
  `at_rest`: `AT_REST` or `WORKING`; a screen that shows a question to a person, or none at all, stays `UNKNOWN`.
  Everything else as today.

Steps: `AgentWaiting`'s `installed` supplier hands the whole `AgentDefinition` (today only its `Waiting`); `Derived`
gains whether rest held; `activityOf` takes it; the varlink type and `TaskStatusCommand`/`TaskListCommand` render
`at rest` with a space; `doc/commands.md`'s activity table; the stub agent (`agents/stub/.../stub.yaml`) declares an
`at_rest`, which it does not today; a scenario attaching the stub, letting it answer, and reading `task list`.

Red first: `TaskActivityTest` - an attached task whose stand-in screen matches `at_rest` on two reads is `UNKNOWN`
today.

## Acceptance

- An attached task whose agent declares `at_rest` is listed `at rest` while its screen matches and `working` while
  it does not, by `task status`, `task list` and the daemon's `Task` on `Watch`. Seen to fail: an attached stub
  agent that answers and rests is still said `unknown`.
- A question to a person still reads as `waiting`, and a stopped container as `dead`, whatever the screen shows.
  Seen to fail: a task waiting for a clearance answer said `at rest`.
- An agent without `at_rest`, and an unattended task, read as they do today. Seen to fail: either changes its
  activity.

## To be checked

- How `porelo-frontend` draws `AT_REST` beside `IDLE` and `UNKNOWN` (Agent Frontend's).
