# B151 — The Providers A Project Allows

**Status:** Analyzed.
**Size:** M - a project file key across `core` and its docs, one refusal in `StartCheck` that the CLI and the daemon
share, and a new outcome in the contract.

**What must be true.** A project names the providers its tasks may use, and a start with another is refused.

## Why

An organisation's rules can permit only some providers; today any provider the vault holds can be chosen.

**Guideline points it answers:** Stadt Zürich KI-Richtlinie Art. 6. From a review of security guidelines, 2026-10-09.

## The shape

`providers:` in the project file; refused at start with the allowed ones named.

## Plan

**Today.** `Project` (`core/src/main/java/org/fuin/sokar/core/project/Project.java`) has no provider list.
`StartCheck` (`apps/app-task`) settles agent, provider and credential before anything is made and answers outcomes
such as "the provider asked for is not declared here"; `TaskLaunch` and the daemon's start both use it.

**Steps:**

1. Red first: a `StartCheck` test with a project allowing only `github-copilot` - a start with `openrouter` is refused
   naming `github-copilot`; one with `github-copilot` passes.
2. `providers: [...]` in `project.yml`: `ProjectReader`, `ProjectSchema`, `Project`; `doc/project-file.md` and
   `DocumentedProjectFileTest`. Absent means any provider, as today.
3. `StartCheck` gets an outcome for a provider the project does not allow, checked also for the agent's default
   provider; the outcome reaches the interface through the start's contract (`15-task.varlink`).
4. A followed project's list comes from its signed configuration, so editing a checkout does not widen it.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- sokar-project [PJ16](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj16) sets a provider list per machine: whether the two are one check at two levels, and which is
  built first; a start is refused when either refuses (Agent Core's).
