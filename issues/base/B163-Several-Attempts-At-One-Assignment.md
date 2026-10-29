# [B163](README.md#b163) — Several Attempts At One Assignment

**Status:** Analyzed.
**Blocked by:** [B136](README.md#b136), [B141](README.md#b141), [B145](README.md#b145), [B166](README.md#b166), [B176](README.md#b176), [B323](README.md#b323).
**Size:** L - grouped task launch, gate comparison/approval, task and daemon contracts, rate-limit reporting and review-store cleanup.

**What must be true.** `porelo task start --attempts N -P "..."` starts independent tasks on one assignment from the
same commit. The gate compares their diff size, risk, build evidence and SARIF findings; approving one applies all
its controls and discards the other waiting attempts safely.

## Plan

`TaskRunCommand` uses `TaskLaunch` and `WorkspaceSetup` to clone from the mirror; separate starts can see different
heads. `GitGate.Waiting` and incoming refs identify individual waiting pushes. There is no group start,
`GroupReview`, `ReviewGroup` or `gate diff`. `TaskBuilds` supplies build records; agent-ending/waiting information
supplies a last message. `Offer` supports optional removal after a decision.

1. Red first: in `apps/app`, start three stub tasks and inspect their actual workspace commits and records: all must
   match one captured source commit and group ID. Name collision or a count above the configured limit starts none.
2. Add `--attempts N` in `TaskRunCommand`; capture the mirror head once and pass it through `TaskLaunch` and
   `WorkspaceSetup`. Reserve `<name>-1` through `<name>-N` as a group, record each identity, and report partial launch
   failures without losing already created tasks. Default maximum is 5; a machine setting can raise or lower it.
3. Add group information to the task record and `15-task.varlink`. Implement `GroupReview` in `app-gate` and
   `ReviewGroup` in `35-gate.varlink` for CLI and daemon alike: per attempt, pinned commit, lines added/removed, files,
   [B323](README.md#b323)'s risk, build verdict, last agent message and SARIF counts by level with completeness.
   Missing, failed or partial checks are explicit and cannot compare as zero findings or a clean attempt.
4. Add `gate diff A B` over the two captured waiting commits. A changed incoming ref invalidates the comparison used
   for approval. Apply [B136](README.md#b136)'s commit binding, [B141](README.md#b141)'s dependency confirmations and
   [B145](README.md#b145)'s required independent approvals to the selected attempt, never to the group generically.
5. After successful approval, reject only the other attempts' captured waiting work; refuse cleanup when one moved.
   Coordinate [B166](README.md#b166)/[B176](README.md#b176)'s SARIF store, outbox and pins: archive needed evidence,
   preserve unreleased/unsent items and objects still referenced, and do not erase a newer attempt's data. Offer task
   removal through `Offer`, default no; ordinary held-work checks still apply.
6. Attempts may share a credential and rate limit. Add an optional agent-definition rule for rate-limit recognition
   (attached-screen or unattended-event form), documented in `agents/README.md` and exercised by the stub. Show it
   per attempt with the credential name in task status and group review; unsupported detection says unknown. No
   agent-specific branch is introduced outside the agent contract.
7. `InterfaceDescriptionTest` holds additive group and review fields. Update `doc/commands.md` for the count limit,
   grouping, comparison, shared rate limits and approval cleanup. The interface consumes these host answers.

## Acceptance

- Seen red first: three tasks start at different commits, a sixth exceeds the default limit after creating tasks, or
  a moved losing attempt is silently discarded. Prevent each and verify actual refs/workspaces after the action.
- A selected attempt cannot reuse another's dependency exception or approval signatures. Partial/unknown review
  evidence stays visible; counts do not establish approval authority or become a model-computed risk score.
- Approving one clears only the captured losing pushes; declined removal keeps tasks. Archived review references
  remain readable and a pending reply cannot disappear through group cleanup.
- A reached credential rate limit appears only for the attempt that observed it; unsupported detection is unknown.

## To be checked

- Choose the machine setting's location and group-name reservation/rollback mechanism; measure parallel launch and
  comparison bounds without weakening the one-source-commit invariant.
- Verify archive and outbox ownership across group cleanup, rejected refs and subsequent reuse of task names.
