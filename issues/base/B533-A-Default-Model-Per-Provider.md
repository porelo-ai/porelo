# B533 — A Default Model Per Provider

```json
{
  "$schema": "issue-1.json",
  "id": "B533",
  "title": "A Default Model Per Provider",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 46,
  "blockedBy": [],
  "covers": "The project file names a default model per provider, a start takes it unless another is chosen, and the task row says which model and from where."
}
```

**Why this size.** a new section of the project file, read at start, recorded and shown; a contract field and a reply.

Filed 2026-11-08 from the third move's finding 11 (the operator's decision: the default model is
set per provider in project.yml, each provider its own).

**What must be true.** A task runs on the model its project file names for its provider unless the person starting it
chose another, and every client can show which model a task runs on and where that choice came from, before it starts
and while it runs. A provider the file names no model for runs on the agent's own default, and the task says so.

## Plan

- Today: nothing picks a model. `--model` (TaskRunCommand, `Start.model`) reaches the agent only through
  `HeadlessFlags.modelFlag`, `TaskProfile.model` records only what was asked for, and the project file has no
  provider section (`Sections`, `ProjectFile`, `Project`).
- **The file:** a top-level `providers:` section, `providers.<provider>.model: <id>` and an optional `label`
  (`Opus 5.5`), read by `ProjectReader` into `Project.providers` and refused where the provider is not one this
  machine knows (a typo would otherwise silently fall back). A repository's porelo.yml may name its own
  `providers.<provider>.model`, which wins for that repository.
- **The start:** where `TaskLaunch` settles the provider (around the `RunRequest` it builds), the model is the start's
  `model`, else the repository's, else the project's, else none; the choice and its source (`start`, `repository`,
  `project`, `agent`) go into `TaskProfile` (`model`, new `modelFrom`, `modelLabel`). A default reaches an agent with
  no `modelFlag` only as a refusal at start, named, never dropped.
- **The contract:** `ProviderModel(provider, model, label, from)` answered by a new `ProviderModels(project,
  repository?)`, so New work shows the default before starting; the task row gains `model`, `modelLabel` and
  `modelFrom` (`TaskInventory.Task`, 15-task.varlink).
- **The file change itself** (the anthropic default `claude-opus-5-5`) is a project.yml commit a Project Admin signs;
  Porelo writes no existing project file.
- Red first: a project naming `providers.anthropic.model` starts a task whose agent command carries the model flag
  with that id and whose row says `from: project`; a start with `--model` says `from: start`; an unknown provider in
  the section is refused at read.

## Acceptance

- Unit tests as above, each seen red first; once on a VM with the stub agent declaring a model flag: the row and the
  agent's command line name the project's model.

## To be checked

- With the window's design reading: where New work shows the model and how a change is offered; whether a batch needs
  anything beyond each repository's default.
- How the terminal header is made to name the model (the agent's own title today) - possibly the window's header alone.

## After the plan reading

The build takes these answers; each harm gets a test seen red first.

- **The MCP allow-list holds for the settled model.** An MCP start checks the model it ends up with against
  `mcp.agents.<agent>`, whatever source chose it; a repository's porelo.yml cannot bring a model outside that list.
- **Every model and label is checked before it is used:** a model id is one word (no blank, no leading `-`, no
  whitespace or control character, `ProjectFileCheck.refuseAsOption`), a label one line of printable text; the same for
  `Start.model` and `--model`. The file's binder reads them as a word and as text.
- **The settled model reaches the agent on every start:** settled right after the provider (`SelectedProvider.choose`),
  carried on `TaskLaunch.Running`, passed in the attended first start and in the unattended run; a resumed task keeps
  its recorded `TaskProfile.model`, never a newly read file. An agent with no `modelFlag` and a settled model is refused
  before anything is made (`launchSteps` and `StartCheck`), not first by `AgentDefinition.attendedCommand`'s throw.
- **Where the file is checked:** `core`'s `ProjectFileCheck` checks the shape of `providers.<provider>` keys (the
  provider name's own pattern); whether the machine knows the provider is checked at start (`TaskLaunch`, `StartCheck`)
  and by `project check`, which have the providers. `ProjectSchema` (`ProjectFileSchema`) grows with `Sections`.
- **porelo.yml** names a model under the same trust as its other settings (`RepositoryOwnSettings`: signed in an
  organization project, allowed by the operator in `default`); a porelo.yml model without a label takes no label from
  the project's. `ImageChoice` ignores the new keys.
- **The row:** `model`, `modelLabel` and `modelFrom` are strings, "" when unknown; a task recorded before reads
  `modelFrom` as `start` where it has a model, `agent` where it has none.

## Found in the build

Where the code was not as the plan read it:

- `ProjectFileCheck.refuseAsOption` does not exist; the check is `Repository.refuseAsOption`, which tests only a
  leading `-`. The one-word and one-line rules are `ModelDefault.isWord` and `ModelDefault.isLine`, used by the
  reader, `--model` and `Start.model` alike.
- The old `--model` refusal for an agent without a `modelFlag` came after the workspace and the gate were made, not
  "before anything exists"; it is replaced by the refusal right after the provider is settled, also in the dry run.
- `Start.model`'s contract said a model was "ignored otherwise" for an agent without a flag; the start refused it.
  The contract now says it is refused (`MODEL_REFUSED`).
- A stopped task continued with a prompt ran on its agent's default: `runAgentInExisting` passed the new request's
  model, null on a continuation. It now runs on `TaskProfile.model`.
- `ImageChoice` read an organization's `porelo.yml` as image-only (`Sections.REPOSITORY`), so a signed file with any
  other setting - `egress` before, `providers` now - stopped the image while `RepositoryOwnSettings.ofOrganization`
  took it. It now reads it beside settings when the project came from an organization.
- Left open: `McpTaskSockets` judges `mcp.agents` on the model the call names, before any file is read, so an MCP
  start naming no model for an agent with a list is still `AgentNotAllowed` even when the files settle an allowed one.
  `TaskLaunch` checks the settled model for every start with a parent, so no file can bring a model past the list.

From the code reading after the build:

- In `default`, a repository's model counts for `ProviderModels` and `CanStart` only once this machine allowed what
  its files ask (`ModelChoice.withRepository`), as at the start.
- A task brought back after a reboot reads its recorded model from its durable record, so another `--model` is
  refused there too; a `--model` for a task no agent runs in is refused; a dry run settles the model for the provider
  the agent was found signed in to.
- A repository's label set without its model is refused at read; `providers.<provider>: ~` removes the entry whole,
  and `model: ~` under a kept label is refused as a missing model.
