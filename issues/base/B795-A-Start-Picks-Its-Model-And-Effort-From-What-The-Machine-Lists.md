# B795 — A Start Picks Its Model and Effort From What the Machine Lists

```json
{
  "$schema": "issue-1.json",
  "id": "B795",
  "title": "A Start Picks Its Model and Effort From What the Machine Lists",
  "state": "Implementing",
  "size": "L",
  "priority": "High",
  "rank": 9,
  "blockedBy": [],
  "covers": "An agent declares how it takes a reasoning effort and which values, Start takes an effort beside its model, and the daemon lists the models a provider offers on this machine, asked through its broker with the vault's credential, so a window offers both as lists",
  "openQuestions": 0
}
```

**Why this size.** L: a new part of the agent definition with its reader and the agents that declare it, a Start field
with its checks, a daemon method that asks providers through the broker, cached, with each provider's list format,
the contract and `doc/`, with their tests. Estimate: 114 minutes (Core's median for L).

**What must be true.** A person starting work picks the model and the reasoning effort from lists the machine gave,
never types them; an absent effort passes nothing and leaves it to the agent.

Filed 2026-11-15 for ST369 (the Start work redesign, lead Agent Planner, with Agent Frontend) from the operator's
wish to pick model and effort from lists, and Agent Smith's reading that the list depends on the task's provider.

## Why

- `Start.model` is free text. `ProviderModels` answers only which model a start that names none would run on (the
  repository's, the project's or the agent's default), not which models there are.
- No agent definition says how it takes a reasoning effort: `headless` has `model_flag` and no effort. Agents spell
  it differently (a flag for some, a config line such as codex's `-c model_reasoning_effort=<value>` for others) and
  take different values.
- Which models a task can use depends on its provider and the credential this machine holds for it, not on the agent
  alone. The broker already speaks to each provider with the vault's credential; the providers list their models
  (Anthropic `GET /v1/models`, OpenAI `GET /v1/models`, OpenRouter `GET /models` with each model's supported
  parameters). Only where the provider is the agent's own sign-in, with no list a key can read, does the agent's own
  list stand in (codex `debug models`, omp `models ls --json`).

## Plan

1. Agent definition: an optional `effort` section, as `instructions` takes its file:
   `effort: { arguments: ["--effort", "{effort}"], values: [low, medium, high] }` (codex:
   `arguments: ["-c", "model_reasoning_effort={effort}"]`). Absent, the agent takes no effort. An optional `models`
   section: `models: { arguments: [...], format: <one of Porelo's readers> }`, only for an agent whose own sign-in is
   its provider. `AgentDefinitionReader` reads both; the agents that have them declare them.
2. `40-agent.varlink`: `Agent.efforts: []string` (empty: takes none).
3. `Start.effort: ?string` (and `CanStart`, `porelo task start --effort`): one of the agent's `efforts`, else refused
   with 64 before anything is made; absent, nothing is passed. "No effort" has that one form: no word such as
   "default" stands for it. A task brought back keeps its effort; a
   different one is refused as a different model is. The task's row says its effort.
4. A daemon method `Models(provider: string, agent: ?string) -> (models: []Model, from: string, readAt: string)`:
   the broker asks the provider's own list with the vault's credential for it; `from` is "provider" or "agent" (the
   agent's `models` arguments run where the provider is its own sign-in) or "none" with an empty list. `Model` is
   `id`, `label`, `efforts: ?[]string` (where the provider says, e.g. OpenRouter's `reasoning` parameter). Kept on
   this machine for an hour, asked again on demand; a provider that does not answer is `Failed` with why, never an
   empty list.
5. `ProviderModels` stays as it is: it says the default a list marks. A project names no default effort of its
   own: the project's work rules (a rule's `risks.<risk>.effort`) are the one place, handed on by the planner as the
   start's `effort` (Agent Planner, 2026-11-15). The contract says a window marks a proposed effort only where it is
   among the agent's `efforts`, and otherwise preselects none and says why, rather than letting Start refuse it.
6. `doc/running.md`, `doc/commands.md`, `agents/README.md` (the two sections), a changelog entry under *Added*.

## Red-first test

- `AgentDefinitionReaderTest`: an `effort` section is read with its values; a value outside them is refused.
- `TaskRunCommandTest`: a start with effort `high` on an agent that declares `["-c", "model_reasoning_effort={effort}"]`
  runs it with those two arguments; with no effort, neither. Fails today: no such field.
- A start with an effort the agent does not list: 64, nothing made.
- `Models` over a stand-in provider answering an Anthropic-shaped and an OpenRouter-shaped list: ids, labels and
  OpenRouter's efforts read; a provider that answers 401 is `Failed`, not an empty list.

## Acceptance

- The red-first tests pass, each seen red first.
- On a machine, the window's Start work offers this machine's models for the task's provider and the agent's efforts,
  and the started task runs with what was picked.

## Answered questions

Answered: one value list per agent, in the agent's own words, declared by each adapter (Agent Smith, 2026-11-15,
read from each CLI near its pin: claude and agy `--effort`, codex `-c model_reasoning_effort=`, pi and omp
`--thinking`); a window offers `Model.efforts` where the list gives them and the agent's values only where it does
not.

Answered: a project names no default effort beside its model; the work rules' effort is the one (Agent Planner,
2026-11-15). Whether a person may start a model the rule does not name is the operator's, in the planner's PL156; the
machine's list is the same either way.

## As built

The effort, in the app and the daemon (the agent definition's `effort` was built before; `Models` is built
separately):

- `TaskProfile` records `effort` (absent in an older profile reads as none); `TaskLaunch.Request`, `Start.effort`,
  `CanStart.effort` and `porelo task start --effort` take it. `TaskLaunch.effortRefused` says the one refusal, in
  the definition's words: a start, a dry run and the credential check refuse it with 64 before anything is made, and
  `StartCheck` answers `EFFORT_REFUSED` beside `SUBAGENTS_REFUSED`. A task brought back takes its own effort and
  refuses another with 64; a later run and `task attach` take the recorded one.
- Porelo adds the arguments itself with `AgentDefinition.efforted`, inside `instructing`, at the three places an
  agent's command is built: `TaskRunner.runAgent` (unattended), `TaskSession` (attended session), and
  `TaskRunCommand`'s attach.
- The daemon reads `effort` as a string, else `InvalidParameter`; a task's MCP start may carry it as it carries
  `model`. `Agents` answers `efforts`, `porelo agents --verbose` prints them, `Task.effort` and the run record's
  `effort` say it.
- Plan differences: `StartCheck` takes the effort through a scoped value bound by a new `check` overload rather than
  through every overload.
- Red first:
  - `TaskProfileTest`: did not compile (no effort component).
  - `TaskRunCommandTest`: the effort tests ran red with the field in place and nothing applied: the unattended log
    lacked `-c model_reasoning_effort=high`, and the attended session lacked `'-c' 'model_reasoning_effort=medium'`.
  - `AgentEntryTest` and `McpTaskSocketsTest`: red with the `efforts` entry and the `effort` parameter taken out.
