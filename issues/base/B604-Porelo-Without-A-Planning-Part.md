# B604 — Porelo Without A Planning Part

```json
{
  "$schema": "issue-1.json",
  "id": "B604",
  "title": "Porelo Without A Planning Part",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "Porelo without a planning part: `60-planning`, `PlanningMethods`, `apps/app-planning`, `planning/api` and the stub, `wire/planning`, their docs, tests and changelog entries removed."
}
```

**Why this size.** mechanical removal across wire, two app modules, the planning modules, the daemon and the docs; no new
behaviour, every remaining test green.

Backlog by the operator 2026-11-10; built after ST51. Filed 2026-11-10 as the operator decided ST294's porelo issues: ST294 is closed, its planning
moves into the separate app porelo-planner (`porelo-project` ST311), and porelo knows no stories or issues any more.

**What must be true.** Porelo has no planning part. A task gets everything it needs from the window when it starts,
through the base task functions; the end of its work is answered by [B566](README.md#b566). Removed, with
the code on main of the planning adapter contract, the board read through porelod and its change stream (all rejected with ST294):

- the contract part `wire/src/main/resources/varlink/org.fuin.porelo.Tasks1/60-planning.varlink` and
  `wire/src/main/java/org/fuin/porelo/wire/planning/`;
- `daemon/.../PlanningMethods.java`, its registration in `PoreloDaemon` and `McpTaskSockets` (the `PlanningChanges`
  parameters and their closing), the planning reads in `McpMethods`' list;
- the module `apps/app-planning` (`PlanningReads`, `PlanningChanges`, `PlanningReadSnapshot`, `StoryStates`,
  `PlanningChangeSource`) and its entries in `apps/pom.xml`, `apps/app/pom.xml`, `daemon/pom.xml`, `bom/pom.xml`;
- the modules `planning/api` and `planning/stub` (`Planning1`, `PlanningMain`, `PlanningServer`,
  `InstalledPlannings`, the stub adapter), `planning/pom.xml` and its entry in the root `pom.xml`;
- `PoreloPaths.planningDirectories()` and the packaged adapter directory `/usr/libexec/porelo/planning`, if anything
  packages it;
- `doc/planning-adapters.md` and its `mkdocs.yml` entry, the planning passages in `doc/running.md`,
  `doc/project-file.md`, `README.md`, `apps/README.md`, `agents/README.md` and `AGENTS.md` where they describe this
  part;
- their tests (`PlanningChangesTest`, `PlanningModulesTest`, `PlanningReadsTest`, `StoryStatesTest`,
  `PlanningMethodsTest`, `PlanningStreamsTest`, the planning parts of `InterfaceDescriptionTest`,
  `McpTaskSocketsTest` and the others that name it) and the changelog entries
  `changelog/unreleased/planning-adapter-contract.yml`, `planning-board-contract.yml`, `planning-board-updates.yml`.

What stays: a project's own repository "where project.yml, the planning and the issues live" is a sentence about the
project's files, not this part, and stays.

## Plan

To be written before it is started. Read first: `git grep -il planning -- ':!issues'` on main, and the planner's needs
in ST311. The adapter contract may be wanted by porelo-planner; it is removed from porelo, not reshaped here.
`core-st294w1` (wave 1, never pushed) is kept until the planner's agent has read what it needs; nothing of it lands.

## Acceptance

- `git grep -il 'planning'` on porelo finds no planning part: no `60-planning`, no `Planning1`, no
  `apps/app-planning`, no `planning/` module; only the project-file sentence above and released changelogs.
- The whole unit suite, `InterfaceDescriptionTest` included, is green; the packages build and install without a
  planning directory.
