# B245 — An Attach Asks For The Model Of The Start

**Status:** Backlog.
**Size:** M - `wire`, `app-task`, `app-model` and their tests, and the task profile's version; no contract another repository reads, as far as the checkouts here show.

**What must be true.** An agent started in a task by `porelo task attach`, after `task run --detach --model <name>`,
is asked for that model, as it is when the start attaches at once; today it runs on its own default model.

## Why

Read in today's code: a start that attaches builds the agent's command with `attendedCommand(model)`
(`TaskRunCommand`), but `TaskProfile` records no model, and `TaskSession.attachedAgent`, which a later `task attach`
uses, builds `sandboxedCommand()` and a resume flag only. Measured from porelo-codex: a detached start with
`--model` and then `task attach` showed the agent on its default model, whose requests the test's model allow-list
refused.

## Plan

Read in `TaskRunCommand`, `TaskProfile`, `TaskSession.attachedAgent`, `TaskLaunch.writeProfile` and
`AgentDefinition.attendedCommand`.

1. `TaskProfile` (in `porelo-wire`) gains a nullable `model`, its earlier constructor kept as an overload, carried
   through `withLabel` and `withClearance`, written and read back (missing reads as none), its `VERSION` 5.
   `TaskProfile` is built only in this repository, and no checkout here reads it.
2. `TaskLaunch.writeProfile` records `request.model()`; `TaskSession.attachedAgent` builds
   `definition.attendedCommand(profile.model())` before the resume flag.
3. **Red first:** `TaskSessionTest.anAgentStartedAgainOnAttachIsAskedForTheModelItsTaskWasStartedWith`, through a
   small seam taking the definition, the model and the session id; then
   `TaskLabelTest.aRelabeledProfileKeepsTheModelItWasStartedWith`.

**Decided:**
- `task run --model Y` reaching a task started with model X is refused, naming X, as a start's other values are refused on a resume.

## Acceptance

- After `task run --detach --model <name>` and `task attach`, the agent's command line carries its model flag with
  that name, a continued session too. Seen to fail first on today's code.
- A profile an older Porelo wrote attaches on the agent's default model.

## To be checked

- That a detached start leaves no tmux session, so an attach starts the agent rather than joining it.
