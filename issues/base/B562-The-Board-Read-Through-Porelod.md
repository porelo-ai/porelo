# [B562](README.md#b562) — The Board Read Through Porelod

**Status:** Implementing, since 2026-11-09. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** L - a contract part with five reads, the adapter run by porelod with a token minted per call for a GitHub
App, and a story's state computed; four modules.
**Blocked by:** [B560](README.md#b560), [B561](README.md#b561).

**What must be true.** The window reads a project's board from porelod alone, never from the adapter: the one
workflow of stories and issues with `planning.work` checked against it, the stories with each one's state, a story, its issues and an issue, each
with its `link`. A story's state is the same for every adapter: with issues, the first state in the workflow's order
among its open issues' states, and when all are closed the first among all of theirs (so Finished, or Rejected when
all are Rejected); without issues, the state the adapter keeps for it. This computed state replaces the rule kept
by hand so far, that a story waits in Review only with every issue in Review, Finished or Rejected and that its
Finished sets those still in Review to Finished: the first follows from the computation, the second is a move of the
story ([B564](README.md#b564)). A project whose adapter has no workflow is refused by name, never shown empty.

## Plan

Read in `daemon` (`PoreloDaemon.register`, `ProjectMethods`, `EcosystemsCommand`'s use of the discovery), `wire`'s
`TasksContract` and `parts`, and `InstalledBuildReader`'s life in `TaskWatchBuildsCommand`.

- New module `apps/app-planning` (depends on `porelo-planning-api`, `app-model`, `app-vault`):
  - `PlanningAdapters`: one running `InstalledPlanning` per project, started at its first use from
    `PoreloPaths.planningDirectories()`, started again after it ended, closed with porelod; each call given the
    project's `planning.settings` and a token by `planning.credential`: for `token` the vault entry's value, for
    `github-app` a token minted for that call. An adapter whose `describe` lacks that credential kind is refused.
- New `apps/app-vault` class `GitHubAppToken`: reads the app's private key from the vault entry, signs a JWT of ten
  minutes for the app id (RS256, the signature `RsaKey.sign` makes for `rsa-sha2-256`), and posts it to
  `/app/installations/<installation>/access_tokens` for a one-hour installation token, which is used once and never
  stored. porelo has no such path today: `HttpForgeDirectory` (organization's git tree) only recognises an
  installation token handed to it, and the GitHub forge client porelo links uses one and mints none.
  - `StoryStates`: the rule above, from the workflow's order and the issues' states; no state name in the code.
  - `WorkCheck`: each `planning.work` key, `from` and `done` a state of the adapter's workflow, the key `WORKING`,
    `done` not `WORKING`, and every `WORKING` state a key of `planning.work`, since it is entered only by starting
    its work; each fault in words.
- `wire`: new part `60-planning.varlink`, added to `parts` after `55-events`: `GetPlanningWorkflow(project) ->
  (states, phases, work, faults)`, `ListStories(project, states: ?[]string) -> (stories, revision)`,
  `GetStory(project, story)`, `ListStoryIssues(project, story)`, `GetIssue(project, issue)`; errors
  `PlanningNotSet(project)`, `PlanningAdapterRefused(adapter, detail)`, `WorkflowMissing(project, detail)` from the
  adapter's own, `NoSuchStory`, `NoSuchIssue`.
- `daemon`: new `PlanningMethods.register`, called from `PoreloDaemon.register`, so a task's MCP socket may name
  them as any other method. A store's change is the adapter's and comes back in its revision.
- `PlanIssue` adds the local work fields below to adapter reads, from the same PlanningWork snapshot as
  GetStoryWork. Define their wire shape here; [B565](README.md#b565) supplies Start's saved choices and
  [B567](README.md#b567) supplies round results and interventions. Reads never infer an end from adapter state.
- Red first: `StoryStatesTest` - issues in Implementing and Review read Implementing; Finished and Rejected read
  Finished; all Rejected read Rejected; no issues reads the story's own. `GitHubAppTokenTest` against a local HTTP
  stand-in: the JWT it posts verifies with the key's public half and names the app id. Then `PoreloDaemonTest` holds the new part
  against the registered methods. `PlanningReadsTest` first fails on missing PlanIssue work fields: the fake
  local record's rounds/limit, stopped flags and optional finding location must match on all issue views.

## The window's wire

All fields and methods below belong to Tasks1's `60-planning`, not Planning1. They describe the newest bounded
reading design; state names come from the checked workflow/configuration.

1. `StartStoryWork.comment: ?string`, `readingRounds: ?int`,
   `issueReadingRounds: ?[](issue: string, rounds: int)` carry the operator's text and maximum readings: issue
   override > story limit > `planning.reading.rounds` (default 2). ProposeStoryWork returns the prefilled limits
   and comment; [B565](README.md#b565) persists and delivers them.
2. `PlanIssue.rounds: int`, `roundsMost: int`, `atWork: bool`, `needsYou: bool`, `readingStopped: bool` are
   present on ListStoryIssues/GetIssue and every nested PlanIssue in ListStories/GetStory/WatchStories.
   `rounds` is consumed readings (0 before any); `roundsMost` is the effective per-issue maximum, or the project
   default without a cycle. Exhausted blocking Reading answers its unchanged state, `readingStopped: true`,
   `atWork: false`, `needsYou: true`; PlanWork's reason is `ROUND_LIMIT`. Other stops do not set readingStopped.
3. `PlanIssue.findings: []PlanFinding`; `PlanFinding(number: int, severity: (BLOCKING, NOTE), text: string,
   file: ?string, line: ?int)`. `blocking|note` in the reader's answer map to BLOCKING/NOTE. WorkEnds parses the
   `Findings:` block of AgentEnded.answer, using the exact format in [B567](README.md#b567), and PlanningWork's
   per-issue reading history keeps raw answer plus parsed findings durably; the wire list is the latest reading,
   empty before one. GetStoryWork and WatchStories use the same results, including notes carried into Review.
4. New `ResolveIssueReading(project: string, story: string, issue: string, round: int,
   choice: (REVIEW_ANYWAY, ONE_MORE_ROUND, REWORK), comment: string, revision: string)`
   `-> (issue: PlanIssue, work: PlanWork, revision: string)` carries all three choices for ROUND_LIMIT.
   REVIEW_ANYWAY enters the configured waiting done state (Review), starts nobody and preserves rounds/roundsMost
   and findings. ONE_MORE_ROUND raises roundsMost to `max(roundsMost, rounds + 1)` and starts a fresh reading of
   the recorded commit, incrementing rounds once. REWORK requires nonblank operator text, grants the same next
   reading allowance and resumes the same main task into configured blocked (Reworking) with that text/findings;
   rounds stays unchanged until its successful chained Reading increments it. No choice resets the cycle.
   `round` must match the displayed PlanIssue.rounds, preventing a delayed retry from granting another round.
   [B567](README.md#b567) owns checks/recovery; ordinary SaveMoves and StartStoryWork gain no implicit retry.
5. `PlanStory.priority: ?string` carries the adapter's optional priority label on ListStories, GetStory and
   WatchStories, absent when the adapter supplies none.
6. `SaveMoves(project: string, moves: [](story: ?string, issue: ?string, to: string), revision: string)`
   `-> (revision: string, moved: []string)` saves every pending story and issue move together against the board's
   revision, all or none. Each move names exactly one of story or issue; a story move changes its least advanced
   issues, or its own state without issues. `moved` carries issue ids. Refusals are `StaleRevision(revision)`,
   `StateNotAllowed(item, from, to, reason)`, `StoryHasIssues(story)`, `MoveNotAllowed(item, from, to, reason)`,
   `EnteredOnlyByWork(item, to)` and `WorkNotAllowed(from, to, reason)`. It answers `NotYetBuilt(method)` and starts
   nothing until [B564](README.md#b564); through MCP it is `JUDGEMENT`, offered only from the person's list.

## Acceptance

- With `StubPlanning` installed, each read answers the stub's data with the computed state; a project without
  `planning` answers `PlanningNotSet`, one whose adapter has no workflow `WorkflowMissing`; a `github-app` credential
  reaches the adapter as a minted token, never the private key; a `planning.work` naming a state the workflow lacks is a fault in
  `GetPlanningWorkflow`, not a refusal of the reads.
- Contract/read tests hold every field in the window's wire, including optional finding locations, a stopped
  Reading and a no-cycle issue. List/detail/nested reads and WatchStories agree with GetStoryWork after restart;
  reads on a machine without the work record report local defaults, never another machine's inferred work.

## Questions from the window

1. **Which machine answers, and how the window finds it - settled.** Any machine whose porelod has the project's
   file with `planning` and the adapter installed answers the board's reads alike, since the adapter reads the one
   store. A story's work and the coordinator run on the machine where the operator starts it: its record
   ([B565](README.md#b565)) and coordinator are there, and `GetStoryWork` is empty elsewhere, so the window asks
   that machine for the work.
2. **What must be true and who builds it - answered.** `GetIssue` carries `whatMustBeTrue` (the adapter's `Issue`
   gives it, [B560](README.md#b560)); `ListStoryIssues` does not. Who builds it is the issue's `repository`, and the
   task working on it is in `GetStoryWork`.
3. **SaveMoves answers what it moved - answered:** `moved`, the issues' ids ([B564](README.md#b564)).
4. **The shell's name - answered.** A work task is named from the story, and with `per: repository` the repository
   (`st294`, `st294-porelo`), cut to fit as a start cuts names; the coordinator is `coordinator`
   ([B568](README.md#b568)). An attended work opens that task's session, or the coordinator's with several.
5. **Needs you - answered: a new path.** `Events` is the machine's security log, read on request and never streamed;
   the window learns of a work's end from `ended` on `GetStoryWork` and `WatchStories` ([B567](README.md#b567)). The
   event log gets its line too, for the record.

## To be checked

- Whether `RsaKey` can be made from the PEM private key GitHub hands out, or the key is read with the JDK's own
  `KeyFactory` for the signature.

- Which way an issue names its repository: the project's repository key, as `Start.repository` takes it, so
  [B565](README.md#b565) can start its task; agreed with porelo-adapters.

## As built (first part)

Built by Codex CLI on Agent Core's brief, on [B560](README.md#b560); reviewed by Agent Core. The issue stays
Implementing: this part is the window's wire and its reads, so the window can build against it.
- **`60-planning`** in Tasks1 declares every method and record of "The window's wire". `porelod` answers the reads
  (`GetPlanningWorkflow`, `ListStories`, `GetStory`, `ListStoryIssues`, `GetIssue`, a one-snapshot `WatchStories`,
  `GetStoryWork`) from the installed planning adapter, through the new area `app-planning`.
  `PlanStory.priority: ?string` preserves the adapter's label in every story view.
- **The board is read in one call** through each story's issues; story states are computed and filtered in `porelod`,
  never through the adapter's stored-state filter. A read whose revisions differ is retried whole twice, then refused
  with `StaleRevision` and the latest revision; no mixed board is answered.
- **The adapter's refusals reach the window by name:** `StateNotAllowed(item, from, to, reason)`, `StoryHasIssues`,
  `PlanningCredentialRefused`, `RateLimited`, `NoSuchStore` and `Unreachable` (each with `retryAfter`),
  `InvalidSettings`, and `PlanningAdapterError` keeping an unknown error's name and fields. `OTHER` states and kinds are
  shown and never start work.
- **Not yet built,** each answering `NotYetBuilt` and starting nothing: `RefreshStories` and a streaming `WatchStories`
  ([B563](README.md#b563)), `SaveMoves(project, moves: [](story: ?string, issue: ?string, to: string), revision)`
  `-> (revision, moved: []string)` for one all-or-none save ([B564](README.md#b564)), `ProposeStoryWork`,
  `StartStoryWork` and `StopStoryWork` ([B565](README.md#b565)), `ResolveIssueReading` ([B567](README.md#b567)).
- **Through MCP** an agent may read the board and the proposal; it never starts or stops work, and `SaveMoves` and
  `ResolveIssueReading` are a person's judgement, offered only from the person's list.
- **Tests.** `wire`, `planning/api`, `planning/stub`, `apps/app` (3661) and `daemon` (337) ran whole outside a sandbox,
  green. Left for the second part: a workflow whose closed state comes before an open one in board order, which no
  test holds yet.
