# B163 — Several Attempts At One Assignment

**Status:** Estimated.
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
- At most 5 attempts per start by default, a machine's setting that may raise or lower it; more is refused before any
  task starts, naming the limit and where it is set.
- The attempts may share one credential and so its rate limit; Sokar does not split or queue them. A limit reached
  is said per attempt - in its row of `gate review <group>` and in `task status` - naming the credential, so a
  stalled attempt is not read as a weaker one.
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
   `N` above the machine's limit (5 unless the machine sets another) is refused before any task starts, naming the
   limit and where it is set.
3. A reached rate limit is read as `waiting` is: the agent definition gains an optional `rate_limited` rule (a screen
   text for an attached agent, a record for an unattended run, `agents/README.md` and the stub), and the task's
   status names it with the credential the task runs on. An agent without the rule cannot say it, and its row says
   the limit is not known rather than none.
4. `GateReviewCommand` with a group: one row per attempt - lines added and removed, files touched, the newest build
   verdict where the project names builds, the agent's last message, and a rate limit the attempt reached, with its
   credential - from a `GroupReview` in `app-gate` that the daemon uses too. `gate diff A B` (new `GateDiffCommand`)
   shows the diff between two attempts' waiting commits.
5. `GateApproveCommand` on an attempt: after the approve, rejects the other attempts' waiting work as `gate reject`
   does, and offers through `Offer` to remove their tasks, default no.
6. Daemon: `group` on a task in `15-task.varlink`, `ReviewGroup(group)` in `35-gate.varlink`;
   `InterfaceDescriptionTest`. The interface's view is `sokar-frontend`'s.
7. `doc/commands.md`: `--attempts` and its limit, `gate review <group>`, `gate diff`, and that the attempts share
   the credential's rate limit.

## Acceptance

- Seen to fail first, then green: `--attempts 3` starts three tasks on one commit; the gate lists them as one group;
  approving one leaves nothing of the others waiting.
- `--attempts 6` is refused before any task starts, naming the limit of 5 and where it is set.
- An attempt whose agent reached its credential's rate limit says so in its own row, naming the credential; the other
  attempts' rows do not.
- `doc/commands.md` says it.

## To be checked

- Where the machine's limit on attempts is set: a file of its own under `~/.config/sokar/` or a field of one that is
  there by then.
