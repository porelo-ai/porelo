# [B576](README.md#b576) — A Task's Status Names Its Model And Its Own Name

**Status:** Review. Filed 2026-11-09 under ST290, from the fourth move.
**Size:** M - the start's model source, the status JSON, broker usage and the durable settings record behind the start protocol.

**What must be true.**
- A start that names no model runs on the project's default and says `modelFrom: project`; a model sent equal to the
  project's default by a client that did not ask the person is not "from start". (The window sends none then.)
- `porelo task status <task> --json` names `model`, `modelLabel` and `modelFrom`, and the task by the name the list
  uses, with its container name beside it.
- Usage names the answering model and token counts visible to the broker. The broker's canonical JSONL retains
  them as `model_answered` and flat token fields, which the usage sums and task status consume without guessing.
  A rejected request retains unknown usage; its requested model is not an answering model.
- The start protocol's line that the task keeps the settings it held before is said only for a task that held
  settings recorded by an earlier start, never on its first start.

## Plan

Read in the code: `TaskInventory` already carries `modelFrom` (`start`, `repository`, ...) from the task's profile;
`TaskStatusCommand.json` writes `name` as asked plus state, project, repository, parameters and usage, no model;
`TaskUsage` reads broker records, not agent records. `TaskLaunch.settingsMovedOn`
compares with `ownSettingsHash`, which is captured before the fetch in the same launch, not an earlier task's record.

- `TaskStatusCommand.json`: add the profile's model, label and source, `task` (the short name) and `container`.
- Start: the model's source is `project` when the parameter is absent; the daemon's `Start` text says the window
  leaves it out to take the default.
- `TaskProfile`: persist the repository-settings hash in `task.json`, which `TaskState` saves and restores.
- `settingsMovedOn`: compare the stopped task's saved hash with its recorded repository's current settings,
  only when an earlier start recorded a nonempty hash. A first start makes no claim about earlier settings.
- Red first: status JSON without model; a notice without an earlier task record; the missing durable hash;
  ordinary profile edits losing that hash. Run the existing start test that pins project-model provenance.
- Usage: trace the generating request and provider answer through `VaultProxy`, `UsageReading` and
  `UsageObservation` to the canonical JSONL. `UsageSums` groups by `model_answered`; it does not match against
  the project's configured model, and `TaskUsage` only locates the record.
- Unit regression first: replay the failing provider shape through the real `UsageObservation` writer and read
  that same file with `UsageSums`. Assert the answering model and its exact token totals; use a different requested
  model to prevent a fallback from masking lost answer metadata. See it fail on behavior before fixing the broker.
- `UsageReading`: recognize an event opening incrementally inside the bounded observer even when the response
  is labeled JSON. Reuse `VaultProxy`'s framing predicates, including partial prefixes; keep relay framing and
  credential checks unchanged. Test successful Messages events, an error event, and a rejected JSON message.

## As built

- Status JSON adds `model`, `modelLabel`, `modelFrom`, `task` (the inventory's short name) and `container` while
  preserving `name`. Its model comes from the saved task profile, independent of the project's current defaults.
- Omitted-model provenance already comes from `ModelChoice`; the existing start regression pins `project`.
  The daemon's `Start` contract tells clients to omit the model when the person has not chosen one.
- `TaskProfile.settingsHash` keeps the repository overrides actually applied before the creation fetch, in the
  durable task record. Caption and clearance edits preserve it. Older profiles read without a hash.
- A stopped task's start compares that recorded hash with the settings for its recorded repository. Only a
  changed, previously recorded hash justifies the "settings it held before" notice. The notice says a new task
  takes the changed settings; repeated starts of this task keep its own. Creation emits no such notice.
  A comparison that cannot be made is reported without preventing a task from resuming its saved state.
- Usage consumes broker observations. The trace confirms that the existing parsers look for Chat Completions
  `model`, Responses `response.model`, Messages `message_start.message.model` and Gemini `modelVersion`.
- A synthetic Messages event stream labeled JSON reproduced one record with no answering model or token counts;
  a similarly labeled error event was recorded as complete. The bounded observer now recognizes the actual event
  opening across chunks and keeps the answering model, counts and error verdict in its canonical JSONL.
- JSON error answers legitimately retain an unknown answering model and missing counts: no generation was
  reported. Neither the requested model nor a task profile is substituted for an answer that never named one.
  Counts and grouping still come only from the broker record, with no match against current project settings.
- Local validation evidence and sandbox limits are recorded in the worktree's untracked build report.
