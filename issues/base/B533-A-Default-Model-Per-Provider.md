# [B533](README.md#b533) — A Default Model Per Provider

**Status:** Backlog. Filed 2026-11-08 from the third move's finding 11 (the operator's decision: the default model is
set per provider in project.yml, each provider its own).
**Size:** M - a new section of the project file, read at start, recorded and shown; a contract field and a reply.

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
