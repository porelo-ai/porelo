# [B576](README.md#b576) — A Task's Status Names Its Model And Its Own Name

**Status:** Backlog. Filed 2026-11-09 under ST290, from the fourth move.
**Size:** M - the start's model source, the status JSON, the usage reading and one line of the start protocol.

**What must be true.**
- A start that names no model runs on the project's default and says `modelFrom: project`; a model sent equal to the
  project's default by a client that did not ask the person is not "from start". (The window sends none then.)
- `porelo task status <task> --json` names `model`, `modelLabel` and `modelFrom`, and the task by the name the list
  uses, with its container name beside it.
- The usage reading names the model the agent used and its token counts, never `unknown` while the agent wrote them.
- The start protocol's line that the task keeps the settings it held before is said only for a task that held
  settings before, never on its first start.

## Plan

Read in the code: `TaskInventory` already carries `modelFrom` (`start`, `repository`, ...) from the task's profile;
`TaskStatusCommand.json` writes `name` as asked plus state, project, repository, parameters and usage, no model;
`TaskUsage` reads the agent's records; `TaskLaunch.settingsMovedOn` compares with `ownSettingsHash`, which a first
start has not set.

- `TaskStatusCommand.json`: add the profile's model, label and source, `task` (the short name) and `container`.
- Start: the model's source is `project` when the parameter is absent; the daemon's `Start` text says the window
  leaves it out to take the default.
- `TaskUsage`: read the model and counts from the record the agent wrote; a test with the stub agent's record.
- `settingsMovedOn`: only when an earlier start recorded a hash.
- Red first for each: status JSON without model; a first start printing the "held before" line; usage `unknown`.
