# [B172](README.md#b172) — A Task That Reviews

**Status:** Analyzed.
**Blocked by:** [B166](README.md#b166), [B291](README.md#b291), [B292](README.md#b292), [B293](README.md#b293), porelo-message-sluice [SL24](https://github.com/porelo-ai/porelo-message-sluice/blob/main/issues/README.md#sl24).
**Blocks:** [B288](README.md#b288), porelo-project [PJ67](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj67).
**Size:** M - one task role across profile, launch, git server and message handling, using the shared review contract, plus daemon fields and acceptance.

**What must be true.** A reviewer task receives another task's work read-only and submits only SARIF comments and
findings. It cannot push, approve, reject, suppress or resolve findings, and its check is the same reviewer role used
for check rounds, never a person's approval.

## Plan

`TaskProfile` version 5 records mode, agent, provider and model but no role or reviewed target. `GateWiring` gives a
per-task `GitHttpServer` its one writable ref; `hidingOthers` hides other incoming refs. `MailboxGuide` has no review
submission format. No task can call the owner's daemon approval methods merely by having a role.

1. Red first: a `GitGateTest` server fixture for a reviewer advertises its authorized reviewed ref but refuses every
   `receive-pack` request, both service discovery and RPC, including the reviewer's own ref.
2. Add `developer`/`reviewer` role and exact reviewed project, repository, head/base and task identity to the next
   `TaskProfile` version; preserve older profiles as developers. Add role/target to task status/list and the task
   contract in `15-task.varlink`, with `InterfaceDescriptionTest` checking the contract.
3. `TaskRunCommand`, `StartCheck`, `TaskLaunch` and `WorkspaceSetup` accept `--role reviewer --review <name>` or a
   pinned review branch. Refuse own work or another project's target before starting, without disclosing that
   project's data. A PR target is resolved through [B171](README.md#b171)'s authenticated mapping when available.
   The clone checks out the exact target; it receives no push URL. Do not execute the reviewed repository's code.
4. `GateWiring` configures upload-only access to the pinned target and keeps all other private refs hidden. Enforce
   the role on the host and every message mutation endpoint; an agent's own read-only mode is supplementary. Apply
   [B293](README.md#b293)'s input restrictions to the reviewer, whose producer remains A. Provider access is brokered
   for reading/reviewing and is not permission to run untrusted builds or pass credentials into them.
5. Through [B166](README.md#b166) and
   porelo-message-sluice [SL24](https://github.com/porelo-ai/porelo-message-sluice/blob/main/issues/README.md#sl24), accept `to: review` data
   only for this target. The host stamps producer, role, round and exact revision; findings use explicit rule/level,
   comments `review/none`. Refuse native IDs, origin claims, resolution, suppression, baseline and verdict authority.
   A suggestions wait for human release and stay within the profile's one-replacement rule.
6. `MailboxGuide` describes SARIF review submission as untrusted data, separate from instructions. For repeated
   checks, only the daemon compares compatible complete runs and computes baselines; missing results never clear a
   finding. An empty or partial check cannot satisfy approval, including the two people required for E0/C3.
7. End the reviewer after its output has been durably accepted, without a separate accept/remove step or another
   reviewer start. Keep its review evidence after cleanup. Update `doc/commands.md` and `doc/messages.md`. The check
   task in porelo-project [PJ67](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj67) uses this same role.

## Acceptance

- In `gate-review.feature`, a stub developer pushes, a stub reviewer reads the pinned commit and submits a finding
  carrying host-stamped A/reviewer origin. Every push, other-task thread mutation and attempted resolution is refused.
- Seen red first: a reviewer can see another hidden ref, submit forged clean state or apply an unreleased fix. A
  partial or empty run leaves gate holds intact. E0/C3 checks do not count as human approvals.
- Cross-project/self-review starts fail before launch. The reviewer ends after durable delivery, preserving findings
  and without an echo loop or a second reviewer. Status and daemon show the same role and target.

## To be checked

- Determine how a pinned ref remains readable through reject/rework and reviewer cleanup; align pins and retention
  with [B166](README.md#b166) and [B176](README.md#b176).
- Verify that every task-accessible Git and message endpoint enforces the role. Test the absence of approval
  authority through these channels; do not invent a task-callable approve method for a test.
- A reviewer delivers its review the way every task does, by writing into its outbox and renaming the file there,
  which needs a shell; no agent's built-in tools rename. Its read-only mode is therefore "no edit tools" and not a
  guarantee: the guarantees are the host's (no push URL, upload-only gate access, `to: review` accepted only for the
  pinned target). Since a shell can run the reviewed code, check that a reviewer of E0 or C3 work runs as
  [B95](README.md#b95)'s container does for that level - no secrets and egress only to its provider - and say so in
  `doc/reach.md`.
