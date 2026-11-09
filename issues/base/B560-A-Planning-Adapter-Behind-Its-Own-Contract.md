# [B560](README.md#b560) — A Planning Adapter Behind Its Own Contract

**Status:** Analyzed. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** M - a new published module with one varlink contract another repository builds against, its stub, its
discovery and one page of documentation; nothing in porelod yet.
**Blocked by:** -.

**What must be true.** A planning adapter is an executable of any license, installed under its name, that keeps a
project's stories and issues wherever it likes. porelo knows it only through `org.fuin.porelo.Planning1`, published
in `porelo-planning-api` beside `porelo-wire`: the one workflow of stories and issues alike (states in board order,
each `WAITING`, `WORKING` or `CLOSED`, and the phases), the stories and their issues read by resource, an issue's state and an issue-less story's
state set, and a stream of changes. An adapter says which credential kinds it takes, `github-app` or `token`, and
gets with each call a token porelod hands it. porelo finds installed adapters the way it finds ecosystem adapters, and an
adapter built against the module needs no `main` of its own.

## Plan

Read in `ecosystems/api` and `builds/api` (`BuildMain`, `BuildServer`, `InstalledBuildReader`, `Build2.varlink`),
`InstalledEcosystems` and `PoreloPaths.ecosystemDirectories`.

- New module `planning/` with `api` and `stub`, beside `ecosystems/`, added to the root `pom.xml` and to `bom`.
  `planning/api` is Apache-2.0 like `ecosystems/api` (`LICENSE`, `<licenses>`), so [B559](README.md#b559)'s check
  takes it in; it depends on `porelo-wire` alone.
- `planning/api`, all new: `org.fuin.porelo.Planning1.varlink` as a resource; `PlanningProtocol` (`INTERFACE`,
  `VERSION` 1, `LOWEST` 1, method names); `PlanningAdapter`, the Java interface an adapter implements; records
  `Workflow`, `PlanState`, `PlanPhase`, `StorySummary`, `Story`, `IssueSummary`, `Issue`, `StateChange`, `Change`;
  `PlanningRefused` for the contract's errors; `PlanningServer` and `PlanningMain` (`describe`, `serve <socket>`,
  copied from `BuildMain`/`BuildServer`); `InstalledPlanning`, porelo's client, which starts the executable, waits
  for its `ready` line, checks `Describe` (name equal to the file's, version not below `LOWEST`) and asks, as
  `InstalledBuildReader` does; `InstalledPlannings`, the discovery copied from `InstalledEcosystems` (first directory
  wins, a failed description named in its refusal).
- Settled: porelod starts an adapter as a build reader is started, with porelod's environment, and `serve` takes a
  second argument, the adapter's own directory per project, `$XDG_STATE_HOME/porelo/planning/<project>`, made by
  porelod, where stories-git keeps its clone.
- `PoreloPaths.planningDirectories()`: `$XDG_DATA_HOME/porelo/planning`, then `/usr/libexec/porelo/planning`.
- `planning/stub`: `StubPlanning`, an in-memory adapter with a counter for its revision, for porelod's tests.
- `doc/planning-adapters.md`, in `mkdocs.yml` beside *Writing an ecosystem adapter*; READMEs for the three modules.
- Hand-over: once it stands, the module is installed in the local repository that sibling repositories build against and its coordinates are given to
  porelo-adapters, which starts nothing before.
- Red first: `PlanningContractTest` serves `StubPlanning` through `PlanningServer` and asks it through
  `InstalledPlanning`; `SetIssueStates` with one unknown issue among two answers `NoSuchIssue` and leaves the other
  unchanged, and one with an old revision answers `StaleRevision`.

## Contract with the adapter

What porelo-adapters plans against, held against porelo's code:

- **Confirmed, one field added:** an executable with `describe`, printing `protocolVersion`, `name`, `version`,
  `capabilities` (`write`, `watch`, `issues`) and `credentials`, the kinds it takes (`github-app`, `token`); and `serve <socket>`, which prints `ready <socket>` once it listens and answers
  `org.fuin.porelo.Planning1`. `PlanningMain.run(adapter, args)` does both, as `BuildMain` does for a build reader.
  `name` takes the place of Build2's `forge`; `Describe` over varlink answers the same five fields. porelod refuses an adapter whose `credentials` lacks the kind
  `planning.credential` names ([B561](README.md#b561)).
- **Confirmed:** every call carries the adapter's `settings` (project.yml's `planning.settings`, passed through
  unread) and the `token`, as each Build2 call carries its token; the adapter keeps no secret. For `github-app` the
  token is a one-hour installation token porelod mints for that use from the app's private key in the vault
  ([B562](README.md#b562)); for `token` it is the vault entry's value. The adapter sees a token either way.
- **Confirmed:** `GetWorkflow`, `ListStories(states: ?[]string)`, `GetStory`, `ListStoryIssues`, `GetIssue`,
  `SetStoryState` (a story without issues only; one with issues answers `StateNotAllowed`), `SetIssueStates(changes,
  revision)`, all or none, and `WatchChanges`, a stream (`more`, as Tasks1's `Watch`). Errors `StaleRevision`,
  `NoSuchStory`, `NoSuchIssue`, `StateNotAllowed(from, to)`, `NotSupported(capability)`.
- **Changed (one workflow):** `GetWorkflow` answers one list of states for stories and issues alike; stories-git
  reads it from one `stories/workflow.yml`. A project without its workflow answers `WorkflowMissing(detail)`, a new
  error, from every method, never an empty workflow.
- **Changed (added):** `Refresh() -> (revision)`, the fetch at once that the design names ("at once when the window
  asks") and no method list carried; a change it finds is also sent on `WatchChanges`. Without the `watch`
  capability it answers `NotSupported`.
- **Changed (added):** an `Issue` from `GetIssue` carries `whatMustBeTrue` beside the summary's fields (id,
  repository by the project's key, title, state, size, blockedBy, link), which the window shows; who builds it is
  its repository.
- **Confirmed:** `revision` is an opaque string the adapter makes; porelo only hands it back.
- **Changed (settled):** the adapter runs with porelod's environment, as a build reader does, and is started as
  `serve <socket> <directory>`, the second argument its own directory per project, where it keeps what it holds on
  the machine (stories-git's clone).

## Acceptance

- The stub, served and asked through the published client, answers every method; all-or-none and the stale revision
  are seen to fail first.
- Discovery lists the account's adapter before a packaged one of the same name and names one whose `describe` fails.
- An adapter's `main` is `PlanningMain.run(new MyAdapter(), args)`, as the document shows.

## To be checked

- The method and field names, read once more with porelo-adapters' agent before the first release of the module.
