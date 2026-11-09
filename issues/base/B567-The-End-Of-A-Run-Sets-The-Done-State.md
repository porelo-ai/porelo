# [B567](README.md#b567) — The End Of A Run Sets The Done State

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** L - durable per-issue feedback, classified findings, same-task continuation and restart reconciliation span task, planning and wire code.
**Blocked by:** [B565](README.md#b565), [B566](README.md#b566).

**What must be true.** porelod judges the end of each recorded participant once. A successful build enters
Reading through then; only blocking reader findings enter Reworking, while notes accompany the issue into Review.
Reworking resumes the main agent's same task and workspace with the findings as its new order; success enters
Reading again on the new commit. Each issue has its own maximum, from `planning.reading.rounds` (default 2) or the
story/issue choice at Start. Blocking findings on the last allowed reading leave the issue in Reading, nobody at
work, under *Needs you* with the last findings. Success on that reading still reaches Review. The operator may
choose Review anyway, another reading round, or Rework with their own words. Stop and a porelod restart retain the
count, limit and findings. Infrastructure failures and missing outcomes need the operator; they never count as
blocking findings. No agent calls porelo to signal its result or start the next agent.

## Plan

Read in `TaskInventory` (`Activity.ENDED` requires a running container), `AgentEnding` (quiet log, cached per
size, finished/error/unpushed), `TaskControl.stop`/`resume`, `TaskLaunch.startExisting`/`runAgentInExisting`/
`runAgentSteps`, `TaskSession.toContinue`, `TaskMethods.Start`, `PoreloDaemon.Watches`, `BackgroundPass` and
`EventLog`. PlanningWork/WorkStart are new in [B565](README.md#b565); answers are [B566](README.md#b566).

- `WorkEnds`, a pure judge: a clean end requires finished, no error and 0 unpushed. A successful last line is
  `Outcome: done`; distinguish `Outcome: blocked - <why>` from an error, missing result or manual Stop. Reader
  instructions require exactly one `Findings:` block just before the final Outcome, with one finding per line:
  `<number>. <blocking|note> | <file-or-> | <line-or-> | <text>`, e.g.
  `1. blocking | src/Main.java | 42 | Missing check` or `2. note | - | - | Clarify the diagnostic`.
  WorkEnds parses that block from `AgentEnded.answer` ([B566](README.md#b566)), the declared reader last message
  extracted by AgentEnding/Waiting.LastMessage, never from tool output or arbitrary task-log lines. Split each
  finding at the first three ` | ` delimiters; the remaining nonempty text may contain pipes. Numbers are consecutive
  positive integers starting at 1; `-` omits a location field, a supplied line is positive and requires a file.
  An empty block means no findings. Map lowercase `blocking|note` to wire severity BLOCKING/NOTE and preserve text.
  Only a clean reading result with blocking findings and a matching blocked Outcome follows
  configured blocked; notes-only plus done reaches Review with notes. Missing/malformed classifications,
  inconsistent Outcome or answerCut true need the operator without automatic advancement. A dropped reader's
  brief is performed by main in the recorded reader role ([B565](README.md#b565)); never branch on state names.
- `PlanningWatch` runs in its own `BackgroundPass`, registered/closed with `PoreloDaemon.Watches`. Reconcile
  recorded container ids against TaskInventory, capture end/answer and commit before TaskControl.stop; an ENDED
  container can still run. No successor starts until the prior state's participants finished and stopped; report
  any survivor. Main/builders/testers must all succeed before then. Evaluate per issue, not aggregate story state.
- Extend PlanningWork's durable file: by issue keep cycle id, rounds (0 before Reading, incremented once per
  Reading entry), effective readingRounds limit, reader/fallback assignment, reviewed commit, numbered findings
  and notes, main container id, run generation, transition id, source/target, launch intent and completion receipts.
  In `$XDG_STATE_HOME/porelo/planning/<project>/work.json`, each issue's reading history retains every round's
  raw answer/answerCut, parsed findings, outcome and reviewed commit before a stop or continuation can replace
  task.log. The latest reading's findings feed the issue view; they and the history survive Review and restart.
  Reworking shows the count of the reading that found its problems. Stop/restart never reset it; a new cycle has
  its own history. A multi-issue repository task carries separate counts; serialize continuations of that main
  task, combining only findings whose issues are eligible, so two readers cannot start competing runs inside it.
- On success apply then if configured, otherwise terminal done. A reading with blocking findings follows blocked
  only while rounds < readingRounds. At the limit persist ROUND_LIMIT, state unchanged, atWork false and needsYou
  true, with last findings; launch no Reworking or next reader. Terminal Review carries notes into the operator's
  test; intermediate transitions create no transient Review or Needs you. Use saved briefs/choices/comment for
  successors; every kept reader starts fresh against the exact recorded commit.
- Keep the main container stopped, never remove it while the cycle may rework. Today TaskMethods.Start calls
  TaskLaunch.startExisting, which uses TaskControl.resume to restore container/helpers; if a new prompt was given,
  it calls runAgentInExisting. That reads the existing TaskProfile's agent/model/subagent limit and runAgentSteps
  submits the request's new prompt in RunRequest, with TaskSession.toContinue's session id when supported. Resume
  alone runs no new order. WorkStart reuses this path at entry to Reworking, with state order, findings and saved
  comment; check the recorded container id first, and refuse a missing/replaced task instead of falling through
  to creation. Keep agent/model, workspace and branch; never seed a new main workspace from the reader's branch.
  Continuing the conversation depends on declared resume/session support; the same container is retained even
  where the agent must start a fresh conversation. Preserve the attended order delivery path for AGENT mode.
- Before a continuation truncates task.log, persist the preceding answer/findings and completion receipt. Give each
  run a generation so equal log sizes cannot reuse AgentEnding's cached end from the preceding run. Recover a
  pending resume without running the same new prompt twice; ambiguous run starts stop for the operator.
- Persist transition intent before adapter SetIssueStates (or issue-less SetStoryState), with current revision.
  Skip items moved meanwhile; stale writes re-read, never overwrite another move. Serialize by project. Restart
  reconciles source/target, ends and launch receipts; ambiguous external moves halt for the operator. Crashes after
  adapter write or successor creation neither count a reading twice nor launch/resume twice. Store changes alone
  authorize no chain.
- `60-planning.varlink`: PlanIssue gains `rounds: int`, `roundsMost: int` (the effective saved readingRounds),
  `atWork: bool`, `needsYou: bool`, `readingStopped: bool`, `findings: []PlanFinding`, on ListStoryIssues/GetIssue
  and all nested ListStories/GetStory/WatchStories views. Values come from local PlanningWork, not Planning1:
  without a cycle, rounds 0, roundsMost project default, flags false and findings empty. ROUND_LIMIT answers
  Reading unchanged, readingStopped true, atWork false, needsYou true and last findings; other stops leave
  readingStopped false. PlanWork's per-issue results carry the same fields and typed reason ROUND_LIMIT;
  PlanFinding is `(number: int, severity: (BLOCKING, NOTE), text: string, file: ?string, line: ?int)`.
  PlanWorkTask.ended carries outcome/why/answer/at. GetStoryWork/WatchStories carry these durable results;
  no client computes whether a reading is stopped or parses the reader's answer.
- New `ResolveIssueReading(project: string, story: string, issue: string, round: int,
  choice: (REVIEW_ANYWAY, ONE_MORE_ROUND, REWORK), comment: string, revision: string)`
  `-> (issue: PlanIssue, work: PlanWork, revision: string)` handles the three ROUND_LIMIT choices:
  REVIEW_ANYWAY uses checked PlanningMoves to enter the reading work's configured waiting done state (Review),
  keeps findings/notes and both counts, clears ROUND_LIMIT/readingStopped, leaves needsYou for Review and launches nobody.
  ONE_MORE_ROUND keeps the cycle and recorded commit, raises roundsMost to `max(roundsMost, rounds + 1)`, clears
  the stop and starts one fresh reader in the recorded Reading state; only actual recorded Reading entry
  increments rounds once. This is an explicit retry, not a self-entry/from configuration change.
  REWORK requires nonblank comment, uses the configured blocked target (Reworking), checks its from and grants
  the same one-reading allowance. Resume the saved main task with state order, retained findings and operator
  text as the new order, notifying the coordinator too; keep rounds unchanged until the successful chained
  Reading increments it. Retain the intervention text with the cycle's saved comments for subsequent orders.
  Ordinary MoveIssues can still move to waiting Review and preserves counts/findings; it cannot carry a comment
  or start Reading/Reworking. StartStoryWork does not double as a round-limit retry.
- ResolveIssueReading is registered in PlanningMethods and handled by PlanningWatch/WorkStart's shared transition
  service under the project lock. Require story membership, the owned current cycle, Reading stopped for
  ROUND_LIMIT, `round` equal to current PlanIssue.rounds and no active/pending issue participant; faults in
  workflow/configuration or a stale adapter revision refuse before any change. Answer `ReadingNotStopped(issue)`
  when no such stop remains, `ReadingRoundChanged(issue, rounds)` on a round mismatch, and reuse
  the existing typed stale/move/start refusals. Record choice/comment and target allowance in transition intent;
  retries/crashes cannot add a second allowance, count or launch. Apply the same recovery and receipt checks as
  automatic transitions. Preserve the stop/findings on precheck failure, and report a launch failure without
  resetting consumed rounds. Signal the new issue/work values on PlanningChanges after every intervention.
- Signal PlanningChanges ([B563](README.md#b563)); journal planning-done/blocked/round-limit in EventLog and
  describe kinds in `55-events.varlink`, deduplicated by transition id on recovery.
- Red first: `PlanningWatchTest` builds → Reading(1) with numbered blocking/note findings → same-main Reworking
  with findings prompt → Reading(2) still blocking. Assert Reading, rounds 2, needsYou, last findings and no next
  launch; notes-only on either reading instead reaches Review with notes. A second issue keeps its own count/limit.
  Restart around adapter write, reader launch and main resume: no duplicate transition, count or prompt. Assert
  original container/workspace marker, new prompt and declared continuation id; equal-length logs cannot reuse an
  old end. WorkEndsTest covers the exact Findings block, absent/partial locations and pipes in text, rejecting
  error/unpushed/missing/malformed/truncated answers as automatic transitions. Contract tests round-trip every
  PlanFinding field and all PlanIssue flags. ResolveIssueReading tests cover all three choices from rounds 2/most 2:
  Review keeps 2/2; extra Reading enters 3/3 once; Rework stays 2/3 until its chained Reading enters 3/3.
  Assert operator text reaches main/coordinator, findings persist, and stale/double requests or restart add no
  allowance/prompt twice, including a delayed request for round 2 after round 3 stops without an adapter revision
  change; malformed findings cannot appear as an empty successful reading.

## Acceptance

- Only blocking findings rework; notes reach Review. Default 2 and story/issue overrides bound the loop; exhausted
  readings stay in Reading with Needs you and last findings. ResolveIssueReading carries each explicit choice;
  Review consumes no round, One more round consumes one, Rework consumes none until its chained reading.
  Neither retry resets the cycle; both grant exactly one further reading beyond the consumed limit.
- Reworking uses the same main container/workspace and delivers the findings as a new order; a missing main is
  reported, never recreated silently. Success returns to a fresh retained reader on the new commit.
- An early teammate end, moved issue, manual Stop, duplicate end or restart cannot advance twice or reset rounds.
  Board reads and WatchStories show the same per-issue count, limit, findings and Needs you reason after restart.
- The acceptance suite with stub runs proves exact-commit reading and same-task reworking; unit tests use fake
  tasks and the stub planning adapter, without podman.

## To be checked

- How AGENT mode supplies a trustworthy last message/end: its driven session has no unattended end today. Read the
  agents' declared session/last-message fields; without an attributable Outcome keep the state and need the operator.
  Confirm retry-safe EventLog receipts and AgentEnding cache invalidation on a same-task new run.
