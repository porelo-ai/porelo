# B481 — On A Host An Approval Opens A Pull Request

**Status:** Review. Accepted on the operator's word (2026-11-06).
**Size:** M - a branch target for approve on a host, a pull-request call to the host's forge, the task token's use;
their tests. Estimate 120 minutes.

**What must be true.** On a host, where `main` of each carried repository takes work only by pull request, a person's
approval at the gate lands as a pull request they can approve: approve forwards the reviewed commit to `porelo/<task>`
on the host's forge and opens the pull request `porelo/<task>` → `main` as the task's own forge account. The person
approves and merges it in the forge, as someone other than its author. The host's part (the account and the token it
hands the gate) is filed in porelo-host.

## Found

- Approve forwards to the starting branch, `main` by default (`ApprovalBranch.of`, `GitGate.approve`). On a host's
  forge a push to `main` is refused: "Not allowed to push to protected branch main".
- `--branch porelo/<task>` lands the commit, but the pull request opened by the person is theirs, and the forge's rule
  (one approval from owners or developers) cannot be met by its author: a project with one member cannot merge.
- On a host each person's tasks push as their own forge account, a developer of the project, and each task start is
  handed a token of it for that task alone, kept outside the container (porelo-host's documentation).
- Nothing in porelo opens a pull request on a forge.
- Approve never forwards over a branch whose head the new commit does not grow from (`GitGate.refuseEarlierWork`):
  a task that amended its work after an approval was offered `porelo/<task>-2`, and a second pull request, on the
  rehearsal (2026-11-06).

## Plan

- On a host (the repository's `upstream` is the host's forge), approve defaults to `porelo/<task>` instead of the
  starting branch; `--branch` still names another.
- After the push, open the pull request through the forge's API with the task's token, titled with the push's subject
  and naming the task; an open one for the same branch is reused, not doubled.
- A task's own branch is the task's: when its head is the commit this gate forwarded there last for the same task,
  approve replaces it with a lease on that commit, and the open pull request follows. Any other head still refuses
  and offers the next free branch.
- The answer names the pull request's address; `Approve` over varlink answers it too.
- The token never reaches a command line, a log or an answer.
- **Red tests:** against a stub forge, approve on a host pushes to `porelo/<task>` and opens one pull request with the
  task token; a second approve of the same task reuses it; an amended push after an approval replaces the task's branch and
  keeps the one pull request; a head the gate did not forward still refuses; a refused call says why and leaves the
  push waiting.

## Open questions

- Whether the scopes the task token holds let it open a pull request, measured by porelo-host before this starts.

## Done

- On the host's own forge, `gate approve` without `--branch` and without a `branch` block forwards to `porelo/<task>`
  (`ApprovalBranch`), and then opens or reuses the task's pull request to the repository's default branch with the
  task's forge token (`HostPullRequests`): no proxy, no redirect, bounded answers, the token in no message. The
  command prints `pull request <url>`; the daemon's Approve answers it as `pullRequest`.
- The gate records each forward by task and destination under `refs/porelo/forwarded/`, hidden from every task's Git
  endpoint. On the host's forge an amended approval replaces the task's branch only while its head is that recorded
  commit, with `--force-with-lease` on it; any other head still refuses with the next free branch. Off a host an
  amended approval always refuses, since the branch there may be one others build on.
- A refused pull request call says the push happened and why the forge refused; the approval stays pending for a
  retry, which reuses the push.
- Tests: `GateHostApprovalTest` (a local forge: open, reuse, amend, a foreign head, a refusal without the token,
  an explicit branch, a pull request to another base) and `GateForwardedTest` (amendment, lease race, ownership by
  task, branch and upstream, hidden records, off a host). With reuse broken two host tests fail; with the host
  condition removed the off-host test fails.

## Still open

- Approval uses the task's forge token as it is now. Removing a task retires its forge identity and forgets its
  token even while its work still waits at the gate, so a host approval has to come before the removal. Keeping that
  identity usable after removal, and a rescued ref's approval, need a decision on the token's lifetime of their own.
