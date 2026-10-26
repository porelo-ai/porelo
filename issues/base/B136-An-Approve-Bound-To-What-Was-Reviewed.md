# [B136](README.md#b136) — An Approve Bound To What Was Reviewed

**Status:** Analyzed.
**Blocks:** [B141](README.md#b141), [B145](README.md#b145), [B95](README.md#b95), [B293](README.md#b293), [B163](README.md#b163), [B166](README.md#b166).
**Size:** M - gate commands, daemon commit binding, enrollment hints and acceptance, providing the commit key for later review controls.

**What must be true.** Gate approval forwards exactly the commit the person reviewed in the CLI, interface or IDE
plugin. A concurrent push cannot forward unseen work or reuse confirmations made for another commit.

## Plan

`GitGate.reviewedCommit` checks a full 40- or 64-digit lowercase object ID when supplied, but null/blank selects the
current incoming ref. `GateApproveCommand` and daemon `Approve` permit that omission; `GateReviewCommand` reads the
ref independently for its patch and log. `CheckoutApproval` supplies a reviewed commit. `ProjectEnrollCommand` prints
an approval command without one. Commit-specific SARIF and co-signatures are consumers of this foundation.

1. Red first: in `apps/app`, a moved pending ref followed by plain noninteractive `gate approve` currently forwards;
   require refusal with exit 70. In `PoreloDaemonTest`, omitted/blank `commit` must answer `CommitNotNamed`.
2. Add `GitGate.waiting(name)` for all gate modes, returning full commit and subject. Keep `waitingWork`'s existing
   start-check behavior. Make `rankedReview` and `log` accept the captured commit so one review cannot mix two pushes.
3. `GateReviewCommand` reads once, prints patch/log for that commit and ends with the full ID and a usable approve
   command containing `--commit`, project, repository and any selected destination options.
4. Without `--commit`, `GateApproveCommand` at a terminal shows what waits and asks through `Offer`, default no. A
   yes calls either signed or ordinary approval with that exact commit. No terminal or `--no-input` refuses with
   exit 70 and the bound command; stopped tasks receive the same treatment. A push during the question is
   `MovedSinceReview`, never a fresh implicit selection.
5. Keep `Approve.commit` optional on the wire for compatibility but reject absence with
   `CommitNotNamed(name, waiting)` in `GateMethods` and `35-gate.varlink`. Preserve `MovedSinceReview` and full-ID
   validation. Add a captured commit to the `Review` reply so clients bind their displayed evidence directly.
6. `ProjectEnrollCommand` prints the exact enrollment commit with `--signed --commit`. Correct approval hints in
   `GateCheckoutCommand`, `GateCheckCommand`, `WorkspaceSetup`, and the command, project-file, gate, security and
   getting-started documentation to name the bound form.
7. The commit is the key for [B141](README.md#b141)'s dependency confirmations, [B145](README.md#b145)'s independent
   signatures and [B166](README.md#b166)/[B172](README.md#b172)'s review-run revisions. A different commit invalidates
   those acts. Approval stays a distinct authenticated operation: SARIF verdicts, suppressions, baselines and empty
   findings grant no authority. Consumer checks are built in their own issues.

## Acceptance

- Seen red first: a push between review and approval, including during a terminal prompt, forwards no unseen commit.
  A script without `--commit` is refused; a terminal no is refused; the matching full ID forwards and a moved ID fails.
- Patch, log, returned commit and printed approval command all name the captured revision, even during a push.
- Signed approval and enrollment follow the same binding. No SARIF input can supply approval authorization.
- The acceptance scenario in `gate-review.feature` covers a running and stopped task and confirms upstream refs.

## To be checked

- Verify interface/IDE handling of `CommitNotNamed` and the additive `Review.commit` field against their clients;
  local client assumptions are not evidence that every installed client sends a commit.
