# [B568](README.md#b568) — A Coordinator While Agents Work

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** M - one oversight task's durable lifecycle and notifications reuse the work launcher; it schedules no agents and gains no start authority.
**Blocked by:** [B565](README.md#b565), [B567](README.md#b567).

**What must be true.** A project has one coordinator while any recorded planning participant works, including
builders, testers and readers. It oversees the story, task progress and findings, and remains the operator's common
session when several repositories work. porelod starts it with the first work, tells it of each later story/issue
and state transition, and stops it after the last participant. It starts and stops nothing, including through MCP;
all worker lifecycle belongs to porelod at state changes. ProposeStoryWork answers START or TELL and GetStoryWork
names it. The Start comment and changed/dropped sub-agents reach its order, including that main takes over any
dropped part. A chained Reading/Reworking transition does not briefly stop/restart the coordinator.

## Plan

Read in `MessagingMethods.Tell` (`PersonNote.tell`, `AgentWake.announce`), `TaskControl.stop`,
`TaskMethods.Start`, `McpTaskSockets.TaskSocket.method` (Start/Stop forwarding), `McpWiring`, and
`PoreloDaemon.Watches`. WorkStart/PlanningWatch are planned in [B565](README.md#b565), [B567](README.md#b567).

- New `Coordinator` in app-planning retains a small oversight role: the story and the participant/state/round
  summaries arrive through the host's Tell path, with findings and failures. Start's comment and each KEEP/CHANGE/
  DROP decision are added to coordinator.order on first start, or the new-work Tell if it is already running;
  name the dropped brief and main agent responsible for it, plus changed assignments and effective reading limits.
  Use the same saved decisions/comment as the main order, never ask the coordinator to infer dropped work.
  It can discuss the work and report concerns, but its text never authorizes a launch, stop or state change.
  PlanningWatch does the watching; no second scheduler or readiness-message parsing. The signed coordinator.order
  describes this scope.
- Start `coordinator` in the project's own repository through the shared launcher, using Start's agent/model
  defaults and in-task subagents 0. Preserve the attended multi-repository session route. Restrict its MCP endpoint
  as for other planning participants: no Start, Stop, StartStoryWork or StopStoryWork even when general project
  permissions offer them. No new project-file exception granting the coordinator lifecycle authority.
- `PlanningWork` keeps its container id apart from participants and completion results. Serialize the first-start
  check per project; recover only the recorded task, never a same-named foreign one. Tell each newly accepted story
  and each state transition once, using saved notification receipts. Coordinator answers do not feed WorkEnds.
- Hold the coordinator across a pending authorized chain. When no participant or successor launch remains,
  PlanningWatch stops it (not removes), retaining/reporting stop failures. StopStoryWork uses the same census;
  stopped or ended coordinators do not trigger worker starts. A daemon restart reconciles ids and pending Tell
  receipts without starting another coordinator merely because the adapter reports a working state.
- `60-planning.varlink`: coordinator START/TELL and coordinatorTask on ProposeStoryWork; coordinator id on
  PlanWork/GetStoryWork. No scheduling interface or coordinator-issued MCP commands are needed. For AGENT mode,
  several repositories open the coordinator's session, one repository its retained working task's.
- Red first: `CoordinatorTest` with fake launch/Tell/stop drives first work, a second story, Reading and Reworking,
  and the final end. Include a changed and dropped sub-agent with a comment on both START and TELL: assert the
  comment, reassigned brief, main responsibility and limits survive restart and arrive once. Assert one coordinator,
  notifications, no bounce between chained states and one final stop;
  `McpTaskSocketsTest` proves that its Start/Stop calls fail even with project-level permissions.

## Acceptance

- One coordinator oversees all project work across restarts, receives new work and findings, and stops after the
  last participant or pending chain. Workers continue to be scheduled by porelod without coordinator commands.
- The initial order and later Tell both carry the comment, team changes and main's dropped work; restart neither
  loses the comment nor repeats an acknowledged notification.
- A coordinator's answer, readiness message or attempted MCP call starts/stops no agent; the denied calls and
  in-task subagent limit are held in tests. An unconfirmed coordinator stop is shown as a failure.
