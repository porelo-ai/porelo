# B163 — Several Attempts At One Assignment

**Status:** Analyzed.
**Size:** L - the start, the gate's review and approve, the daemon's contract and docs; a group to design first.

**What must be true.** `sokar task start --attempts N -P "…"` starts N tasks on the same assignment, from the same commit,
each in its own container; the gate shows their results side by side, one is approved and the others are discarded.

## Why

An agent given the same assignment twice comes back with different work, and the better of several is often worth more
than one carefully watched run. Sokar already has what that needs - cheap tasks kept apart, and a review before
anything reaches the upstream; what is missing is the shared start and the comparison. Comparable tools offer it only
as a hosted service, not on the person's own machine.

## The shape

- The N tasks share a name with a number each (`<name>-1` … `<name>-N`) and a group the gate knows them by.
- `sokar gate review <group>` shows, per attempt: the diff's size, the files touched, whether its build passed where the
  project names one, and the agent's own summary; `gate diff` between two attempts.
- Approving one discards the others' waiting pushes and offers to remove their tasks (as `Offer` asks), default no.
- The interface's view is `sokar-frontend`'s part.

## Plan

Read in today's code:

- `sokar task start` is `TaskRunCommand` over `TaskLaunch` (`apps/app-task/...`); the workspace is a clone the
  container makes from Sokar's mirror at its head (`WorkspaceSetup`), so two starts a minute apart may start from
  different commits.
- The gate knows waiting work per task name (`GitGate.Waiting(commit, subject)`, refs under `refs/sokar/incoming/`);
  `gate review`, `gate pending`, `gate approve`, `gate reject` act on one name. There is no `gate diff` command.
- What an attempt needs for its row exists: the builds of a push (`task status`, the build records), the agent's last
  words (`AgentEnding`, `AgentWaiting.lastMessage`), and `Offer` for the question after an approve.

Steps:

1. Red first, in `apps/app` with the stub agent: `task start --attempts 3 -P "..."` is an unknown option; afterwards
   three tasks `<name>-1` to `<name>-3` exist, each workspace at the same commit, and each `task.json` names the
   group.
2. `TaskRunCommand`: `--attempts N` resolves the mirror's head once and hands the commit to each `TaskLaunch`;
   `WorkspaceSetup` takes a commit to check out instead of the head. Names come from `<name>-<i>`, refused as one
   when any is taken. The group is the base name, written in each task's record and said by the daemon's `List`.
3. `GateReviewCommand` with a group: one row per attempt - lines added and removed, files touched, the newest build
   verdict where the project names builds, the agent's last message - from a `GroupReview` in `app-gate` that the
   daemon uses too. `gate diff A B` (new `GateDiffCommand`) shows the diff between two attempts' waiting commits.
4. `GateApproveCommand` on an attempt: after the approve, rejects the other attempts' waiting work as `gate reject`
   does, and offers through `Offer` to remove their tasks, default no.
5. Daemon: `group` on a task in `15-task.varlink`, `ReviewGroup(group)` in `35-gate.varlink`;
   `InterfaceDescriptionTest`. The interface's view is `sokar-frontend`'s.
6. `doc/commands.md`: `--attempts`, `gate review <group>`, `gate diff`.

## Acceptance

- Seen to fail first, then green: `--attempts 3` starts three tasks on one commit; the gate lists them as one group;
  approving one leaves nothing of the others waiting.
- `doc/commands.md` says it.

## Open questions

- Whether N attempts may share one credential's rate limit, and how a limit reached by one is said (the operator's).
- The highest N a machine is allowed, by default (the operator's).
