# [B565](README.md#b565) — Work Started From The Board

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** L - per-repository teams, briefs, operator choices and durable launch records cross task, gate, daemon and wire code.
**Blocked by:** [B562](README.md#b562), [B563](README.md#b563).

**What must be true.** A story entering a working state gets a Start dialog filled by porelod. For each
repository it proposes the main agent and model, builder/tester sub-agents and the reader reserved for Reading,
each with agent, model, role and brief. The operator keeps, changes or drops each sub-agent, adds a comment and
sets the reading limit for the story or individual issues, prefilled from `planning.reading.rounds` (default 2).
porelod puts the comment and changed/dropped assignments in the coordinator's and main agent's orders; the main
agent does any dropped part. The reader starts later through `then:` ([B561](README.md#b561)), with a fresh
conversation reading the recorded commit as a stranger. Dropping that reader assigns its brief to the main agent
in Reading; it does not skip the state. No agent, including the coordinator, starts or stops another agent.
No readiness message, MCP call or adapter notification starts work. `GetStoryWork` returns the team and work
record; `StopStoryWork` stops its active tasks, retaining the working state with `atWork: false` for the operator.

## Plan

Read in `daemon/TaskMethods.Start`, `TaskLaunch.Request`/`settleSubagents`/`startExisting`/`runAgentInExisting`,
`ModelChoice`, `TaskControl`, `TaskWorkspace.startingFrom`, `TaskBase`, `GitGate.waitingWork`,
`MessagingMethods.Tell`, `McpTaskSockets` and `TaskProfile.parent`. PlanningMethods/WorkCheck/PlanningChanges are
planned in [B562](README.md#b562), [B563](README.md#b563); the adapter contract is [B560](README.md#b560).

- Today limits.subagents/Start's subagents is a number passed by TaskLaunch to `SetupContext`/`RunRequest` for the
  agent to enforce internally. Separately, McpTaskSockets forwards permitted Start calls, bounds agent/model/task
  choices and records TaskProfile.parent. Neither supplies a board team lifecycle. Planning participants become
  separate tasks; set internal subagents 0 and deny MCP Start/Stop and planning lifecycle writes for them and the
  coordinator, even if general project permissions allow them. Preserve unrelated task use.
- Extract TaskMethods.Start's checks/launch into a service shared with `WorkStart`: ProjectTaking,
  StartCheck.unknownHost/undeclared, model/subagent checks and certificates. app-planning gains app-task and
  app-messaging dependencies, never a dependency on daemon.
- `WorkProposal` groups eligible issues by repository (project repository for per: story), resolves main agent,
  ModelChoice defaults and state-specific model, and fills the inline order. Additional assignments come from
  adapter GetIssue's `subAgents[]`: name, kind BUILDER/TESTER/READER, proposed agent/model and complete brief text.
  Scope each stable assignment id by repository/issue/name; a reader belongs to the chained reading state.
  This requires adding that list and its record to Planning1 in [B560](README.md#b560), not guessing from prose.
- The file adapter reads each brief listed in an issue's Sub-agents section from
  `issues/<group>/<ID>.sub/<name>.md`; missing or malformed referenced briefs refuse the proposal. Each brief gives
  scope, allowed/forbidden files, acceptance and result branch `<ID>-<name>` pushed through the gate, ending with
  `Outcome: done` or `Outcome: blocked - <why>`. A reader checks What must be true, Acceptance and Plan with numbered
  findings marked `blocking` or `note`, with optional file and line, using the parseable answer format in
  [B567](README.md#b567). The briefs are removed with the issue at Finished.
  File parsing and cleanup belong to the adapter/finishing work; porelod consumes text and never opens that path.
  These are board/backend format requirements; no Shared Area rule or section is added to this repository's issues.
- `60-planning.varlink`: ProposeStoryWork answers repositories: []PlanRepositoryWork(repository, issues, main,
  subAgents), plus comment (initially empty), readingRounds and issueReadingRounds: [](issue, rounds), prefilled
  from the project default. Each sub-agent assignment carries id/issue/name/agent/model/kind/workingState/brief.
  StartStoryWork(project, story, to, mode, repositories, comment, readingRounds, issueReadingRounds, revision)
  takes KEEP/CHANGE/DROP and resulting agent/model for each sub-agent. Its exact additional request fields are
  `comment: ?string`, `readingRounds: ?int`, `issueReadingRounds: ?[](issue: string, rounds: int)`; omitted comment
  is empty and omitted limits use the defaults. ProposeStoryWork returns `comment: string`, `readingRounds: int`,
  `issueReadingRounds: [](issue: string, rounds: int)` with the effective proposal values. Per-issue rounds override story rounds,
  which override the project default; omitted choices use the proposal. Validate positive whole limits and issue
  membership, from/modes, identities, roles, model choices and issue revision/briefs before any write or launch.
  A main agent is required; dropped briefs remain assigned to it. Ordinary Start cannot enter Reading directly;
  the recorded chain enters it. ROUND_LIMIT's Review anyway/One more round/Rework use ResolveIssueReading in
  [B567](README.md#b567), reusing the saved cycle, assignments and same-main continuation service.
- `PlanningWork`: versioned, atomically replaced `$XDG_STATE_HOME/porelo/planning/<project>/work.json`, serialized
  per project. Keep cycle id, approved assignments, brief text/revision, deferred reader/drops, comment, effective
  per-issue limits, signed config identity, items, state/done/then/blocked, container ids, roles, agents/models,
  launch status and since. Several tasks may own one issue; the first teammate ending cannot complete it.
- WorkStart prechecks all choices, records intent, changes eligible items in one adapter write, then launches
  current-state agents with unique slot names. UNATTENDED sub-agents get state order, then their whole brief,
  then comment, as one prompt; main gets the issue/order plus dropped briefs and explicit responsibility, e.g.
  "Sub-agent <name> dropped: <comment>. Do its part yourself." Changes name the replacement and any dropped scope.
  Coordinator order/Tell carries the same comment and decisions ([B568](README.md#b568)). AGENT main gets its
  order through PersonNote. Use background workers: prompt-bearing Start waits for the run, so serial calls defer
  teammates. Record each container immediately; partial failure stops new participants and restores only states
  still owned by the attempt, retaining failures/survivors. Reconcile intent on restart; WorkRunning prevents
  duplicates and a same-named foreign task is never adopted.
- Reuse saved choices/briefs/comment for the chain. Pin the pushed commit: GitGate incoming work is outside normal
  branches; TaskBase/TaskWorkspace seed a branch today. Add a scoped handoff of the exact commit to a fresh reader
  workspace/conversation without upstream approval or host workspace access. Keep the stopped main container for
  Reworking, resuming it with findings as the new order ([B567](README.md#b567)); never seed a replacement workspace.
- PlanWork/PlanWorkTask answer assignments, decisions/comment, limits, container ids, role/state, since and atWork;
  per-issue limits use wire `roundsMost` (the saved effective readingRounds), alongside consumed `rounds`.
  Name failed starts, signal PlanningChanges and journal planning-started. Red first: `WorkStartTest` with
  StubPlanning/fake launcher starts main and retained builder/tester concurrently, delivers order/brief/comment
  in that order, honors changed model and gives dropped work to main/coordinator. It records the deferred reader
  and story/issue limits; a forbidden from or stale brief starts nothing. Contract tests hold the nested shapes.

## Acceptance

- Each repository's proposed/chosen team includes the adapter's briefs; comments and dropped responsibility reach
  main and coordinator, while each selected sub-agent receives its own brief and comment in UNATTENDED mode.
- Default 2, raised/lowered story limits and per-issue overrides survive restart with all choices and deferred
  readers. Assert the exact StartStoryWork request fields and PlanIssue.roundsMost, including omitted defaults,
  issue-over-story precedence and refusal of zero/fractional limits before launch. A dropped reader leaves Reading
  assigned to main. A forged store state starts nothing.
- Duplicate requests and partial failures neither duplicate tasks nor leave an unrecorded survivor. Stop leaves
  nobody at work and does not itself start the chain. Planning tasks cannot start agents through MCP or internally.
- With the stub agent on a real acceptance machine, retained participants work concurrently and a fresh retained
  reader receives the exact committed result, even when the gate has not approved it upstream.

## To be checked

- Exercise the shared start's certificate request path from planning; keep Start's typed refusal where the caller
  cannot certify. Confirm the incoming-commit handoff with `GitGate`'s advertised refs before implementation.
