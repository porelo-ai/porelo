# B481 — On A Host An Approval Opens A Pull Request

**Status:** Accepted, on the operator's word (2026-11-06). Not started before the rehearsal's first pass is done.
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

## Plan

- On a host (the repository's `upstream` is the host's forge), approve defaults to `porelo/<task>` instead of the
  starting branch; `--branch` still names another.
- After the push, open the pull request through the forge's API with the task's token, titled with the push's subject
  and naming the task; an open one for the same branch is reused, not doubled.
- The answer names the pull request's address; `Approve` over varlink answers it too.
- The token never reaches a command line, a log or an answer.
- **Red tests:** against a stub forge, approve on a host pushes to `porelo/<task>` and opens one pull request with the
  task token; a second approve of the same task reuses it; a refused call says why and leaves the push waiting.

## Open questions

- Whether the scopes the task token holds let it open a pull request, measured by porelo-host before this starts.
