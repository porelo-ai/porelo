# B245 — An Attach Asks For The Model Of The Start

**Status:** Idea.

**What must be true.** An agent started in a task by `porelo task attach`, after `task run --detach --model <name>`,
is asked for that model, as it is when the start attaches at once; today it runs on its own default model.

## Why

Read in today's code: a start that attaches builds the agent's command with `attendedCommand(model)`
(`TaskRunCommand`), but `TaskProfile` records no model, and `TaskSession.attachedAgent`, which a later `task attach`
uses, builds `sandboxedCommand()` and a resume flag only. Measured from porelo-codex: a detached start with
`--model` and then `task attach` showed the agent on its default model, whose requests the test's model allow-list
refused.

## Acceptance

- After `task run --detach --model <name>` and `task attach`, the agent's command line carries the agent's model flag
  with that name, and a resumed session keeps it. Seen to fail first on today's code.
