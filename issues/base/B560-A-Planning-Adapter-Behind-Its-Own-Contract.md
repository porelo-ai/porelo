# [B560](README.md#b560) — A Planning Adapter Behind Its Own Contract

**Status:** Review, since 2026-11-09. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
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
  `Workflow`, `PlanState`, `PlanPhase`, `StorySummary`, `Story`, `IssueSummary`, `Issue`, `SubAgent`, `StateChange`, `Change`;
  `PlanningRefused` for the contract's errors; `PlanningServer` and `PlanningMain` (`describe`, `serve <socket> <directory>`,
  copied from `BuildMain`/`BuildServer`); `InstalledPlanning`, porelo's client, which starts the executable, waits
  for its `ready` line, checks `Describe` (name equal to the file's, version not below `LOWEST`) and asks, as
  `InstalledBuildReader` does; `InstalledPlannings`, the discovery copied from `InstalledEcosystems` (first directory
  wins, a failed description named in its refusal).
- Settled: porelod starts an adapter as a build reader is started, with porelod's environment, and `serve` takes a
  second argument, the adapter's own directory per project, `$XDG_STATE_HOME/porelo/planning/<project>`, made by
  porelod, where stories-git keeps its clone.
- `PoreloPaths.planningDirectories()`: `$XDG_DATA_HOME/porelo/planning`, then `/usr/libexec/porelo/planning`.
- `planning/stub`: `StubPlanning`, an in-memory adapter with a counter for its revision, for porelod's tests.
- Add `SubAgent(name: string, kind: (BUILDER, TESTER, READER), agent: string, model: string, brief: string)` and
  `Issue.subAgents: ?[]SubAgent` on GetIssue's full Issue, with the matching Java record/list, PlanningServer
  serialization and InstalledPlanning decoding. `brief` is the complete Markdown text, never a host path.
  Missing lists decode to `[]` for compatibility; an issue without proposals answers `[]`. No new method or summary
  field. StubPlanning and PlanningContractTest round-trip all three kinds, a multi-line brief, an empty list and a
  missing list. Reading rounds and the operator's comment stay in Tasks1.
- Harden the published boundary: moves identify their item and reason, a story with issues has its own refusal;
  store/credential errors retain typed fields in Build2's shape. `StorySummary.issues` supplies one board snapshot,
  with the stored-state filter documented separately from porelod's computation. Nullable proposals and newer
  enum kinds remain readable. Capture a watch baseline atomically with subscription and keep it separate from
  pending changes; callers compare it with their read. Match the sibling modules' lifecycle and native fixture.
- Red regressions: ambiguous batch/story refusals, issue snapshots, future/null fields, descriptions, a write
  before the first subscription take, blocked subscription close, staged-store conflict and native module profile.
  Whole-module tests include wire errors, every method/refusal over a socket, cancellation and process restart.
- `doc/planning-adapters.md`, in `mkdocs.yml` beside *Writing an ecosystem adapter*; READMEs for the three modules.
- Hand-over: once it stands, the module is installed in the local repository that sibling repositories build against and its coordinates are given to
  porelo-adapters, which starts nothing before.
- Red first: `PlanningContractTest` serves `StubPlanning` through `PlanningServer` and asks it through
  `InstalledPlanning`; `SetStates` with one unknown issue among two answers `NoSuchIssue` and leaves the other
  unchanged, and one with an old revision answers `StaleRevision`. A mixed story/issue batch with one refused
  move leaves both resources and the revision unchanged.

## Contract with the adapter

What porelo-adapters plans against, held against porelo's code:

- **Confirmed, one field added:** an executable with `describe`, printing `protocolVersion`, `name`, `version`,
  `capabilities` (`write`, `watch`, `issues`) and `credentials`, the kinds it takes (`github-app`, `token`); and `serve <socket> <directory>`, which prints `ready <socket>` once it listens and answers
  `org.fuin.porelo.Planning1`. `PlanningMain.run(adapter, args)` does both, as `BuildMain` does for a build reader.
  `name` takes the place of Build2's `forge`; `Describe` over varlink answers the same five fields. porelod refuses an adapter whose `credentials` lacks the kind
  `planning.credential` names ([B561](README.md#b561)).
- **Confirmed:** every call carries the adapter's `settings` (project.yml's `planning.settings`, passed through
  unread) and the `token`, as each Build2 call carries its token; the adapter keeps no secret. For `github-app` the
  token is a one-hour installation token porelod mints for that use from the app's private key in the vault
  ([B562](README.md#b562)); for `token` it is the vault entry's value. The adapter sees a token either way.
- **Confirmed:** `GetWorkflow`, `ListStories(states: ?[]string)`, `GetStory`, `ListStoryIssues`, `GetIssue`,
  `SetStates(changes, revision)` (stories and issues together, all or none; a story with issues answers
  `StoryHasIssues(story)`) and `WatchChanges`, a stream (`more`, as Tasks1's `Watch`). Errors `StaleRevision`,
  `NoSuchStory`, `NoSuchIssue`, `StateNotAllowed(item, from, to, reason)`, `NotSupported(capability)`.
- **Changed (board design, after the close reading):** `StorySummary` and `Story` carry `priority: ?string`,
  the story file's priority in the adapter's own words, absent/null when none; older replies remain readable.
  `SetStates(changes: []StateChange, revision) -> (revision)` replaces and removes both `SetStoryState` and
  `SetIssueStates` before the contract's first release, with no aliases. `StateChange(story: ?string, issue: ?string,
  to: string)` names exactly one nonblank identifier. One batch may mix issue-less stories and issues: validate
  every move before writing, then publish one new revision; any refusal changes nothing. Requires `write`, and
  `issues` only when an issue is present. Refusals stay the same, including `StoryHasIssues(story)`.
- **Changed (one workflow):** `GetWorkflow` answers one list of states for stories and issues alike; stories-git
  reads it from one `stories/workflow.yml`. A project without its workflow answers `WorkflowMissing(detail)`, a new
  error, from every method, never an empty workflow.
- **Changed (added):** `Refresh() -> (revision)`, the fetch at once that the design names ("at once when the window
  asks") and no method list carried; a change it finds is also sent on `WatchChanges`. Without the `watch`
  capability it answers `NotSupported`.
- **Changed (added):** an `Issue` from `GetIssue` carries `whatMustBeTrue` beside the summary's fields (id,
  repository by the project's key, title, state, size, blockedBy, link), which the window shows; who builds it is
  its repository.
- **Changed (added):** `SubAgent(name: string, kind: (BUILDER, TESTER, READER), agent: string, model: string,
  brief: string)` and `Issue.subAgents: ?[]SubAgent` carry the proposed sub-agents on GetIssue's full Issue only.
  `brief` is the complete Markdown text, never a host path. No proposals answers `[]`; a missing list decodes to
  `[]` for compatibility. No new method or summary field; reading rounds and the operator's comment stay in Tasks1.
- **Changed (board snapshot):** `StorySummary.issues: ?[]IssueSummary` contains every issue summary at the
  listing's revision, including `[]` for none; an adapter with `issues` supplies it. Absent/null is unavailable,
  not issue-less. `ListStories(states)` filters stored states only. For [B562](README.md#b562), porelod omits the
  filter, computes and filters locally, and compares `GetWorkflow`'s revision with the listing's, retrying on a
  mismatch. Without the snapshot it refuses `NotSupported("board-snapshot")`; no N per-story reads are needed.
- **Changed (store errors):** `CredentialRefused`, `RateLimited`, `NoSuchStore` and `Unreachable` each carry
  `detail: string, retryAfter: int` (seconds, 0 if unknown), as Build2 does; `InvalidSettings(detail)` and
  `Failed(message)` explain other failures without credentials. Unknown errors retain their name and parameters.
- **Changed (moves):** `StateNotAllowed(item, from, to, reason)` names the batch item for
  [B564](README.md#b564)'s `MoveNotAllowed`; `StoryHasIssues(story)` is distinct and invents no from-state.
  Duplicates within a resource kind carry reason `Duplicate story` or `Duplicate issue`. StaleRevision carries the current store revision; an empty batch
  still checks it and never advances it. Writes cannot overwrite unfetched concurrent store changes.
- **Clarified (compatibility and watches):** optional `subAgents` accepts absent/null as `[]`; future enum kinds
  read as Java `OTHER` (also a declared wire value), never permission to start work. Unknown nullable fields are accepted. WatchChanges keeps
  its signature: the initial subscription revision must be compared with the preceding read and a mismatch
  invalidates the whole board. Initial revision capture and subscription are atomic, with later writes kept
  pending even before the first take; a closed watch returns null. Structured refusal fields stay intact.
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

## As built

Built by Codex CLI on Agent Core's brief; reviewed by Agent Core.
- **`porelo-planning-api`** (Apache-2.0, published to Central as the seventh artifact; AGENTS.md's list of them says so) holds `Planning1`: every method and named refusal of the contract above, the records, `PlanningServer` (`serve <socket> <directory>`), `PlanningMain` (`describe`) and `InstalledPlannings` (discovery, the account's adapter before the package's).
- **Proposed sub-agents** came after the plan: `Issue.subAgents` on GetIssue's full issue, each `SubAgent` with `name`, `kind` (BUILDER, TESTER, READER), `agent`, `model` and `brief`, the brief's whole Markdown text.
- **`planning/stub`** (LGPL-3.0-or-later) is an in-memory board with one revision counter, atomic batches, staged refreshes and independent watchers. The contract test drives it over a real socket.
- `PoreloPaths` names the planning adapters' locations; `doc/planning-adapters.md` is the adapter author's guide.
- **After a close reading by another model** the contract changed before its first release:
  - a refusal names its item and a reason: `StateNotAllowed(item, from, to, reason)`, and `StoryHasIssues(story)` for a story whose state its issues decide;
  - a store's failures are named as Build2's are: `CredentialRefused`, `RateLimited`, `NoSuchStore`, `Unreachable` (with `retryAfter`), `InvalidSettings`, `Failed`; a caller keeps an unknown error's name and parameters;
  - `StorySummary` may carry its issues, so porelod reads the board in one call; the `states` filter of `ListStories` is the stored state;
  - `subAgents` may be missing, and a newer kind or state reads as `OTHER`, which never authorizes work;
  - `WatchChanges`' first revision is compared with the preceding read, and the stub drops no write between them;
  - `StaleRevision` carries the current revision; the server binds before it initializes, and a closed watch wakes its reader without a made-up change.
  `wire`'s `VarlinkException` keeps a null parameter.
- **After the close reading, from the board's design (ST294, the window's board):** added optional story priority and
  replaced the two separate state methods with one atomic `SetStates` batch for stories and issues before release.
- **Tests.** `wire` and `apps/app` (3648) ran whole outside a sandbox before the two additions, `planning/api` (23) and `planning/stub` (65) after them, green.
