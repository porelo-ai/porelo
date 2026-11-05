# B460 — A Task Starts On The Branch Of Its Checkout

**Status:** Review.
**Size:** L - task launch, durable state, gate review and the daemon contract, with a separate window change.

**What must be true.** A task started from a checkout starts on that checkout's branch, and the start says so. It never works on another
branch without the person having seen which.

The operator's choice, 2026-11-05. Nothing is built before the test.

## Found

Today a task starts on the upstream's default branch, even from a checkout on `develop`. The operator's first task on `utils4j` worked on `main`
by mistake for that.

## What Is Asked

- A task started from a checkout starts on that checkout's branch, and the start says so, for example `starts from develop (your checkout)`.
- `--from <branch>` names another.
- With no checkout, `base_branch` in `project.yml`, else the default branch. That key does not exist yet.
- The window shows it on the start and can change it: Agent Frontend needs the branch and where it came from on the answer, and a way to name
  another.

## To Read First

What compares with the default branch today, and must follow the branch a task started from:

- the review's diff, `GateReviewCommand`'s default and `GateMethods`;
- the forward, including where approved work goes;
- `online`.

Then:

- A branch only in the checkout and not upstream says so at the start, rather than failing late.
- A detached checkout says what it does.
- **The rule stays:** `MirrorFile` reads a repository's `porelo.yml` at the default branch, as that holds reviewed commits. A task on `develop` does
  not change which file it trusts, unless a reading finds a reason, which then goes to the operator.
- The size is said again after this reading.

## Plan

- Keep the starting branch with pending work in the mirror, so removing a task does not make review or approval fall back to the default branch. A runtime copy follows the existing durable-task-state mechanism.
- Record the starting branch and its source separately from the task's forge branch. Choose explicit `--from`, then the matching caller checkout, then root `base_branch`, then the mirror's default.
- Refuse a detached checkout unless `--from` names a branch. Refuse a selected branch absent from the refreshed mirror before creating a container; do not copy unpublished checkout commits implicitly.
- The workspace fetch and checkout use that branch, and any failed setup stops a start with a selected branch. Resume preserves existing work and ignores the caller's current checkout; previews query no runtime. A real-git workspace test first demonstrates the wrong default checkout.
- Gate review in the CLI and daemon compares against the recorded starting branch. With a declared branch scheme, approval and online forwarding retain the task's separate forge branch and pattern checks. Without a scheme, approval defaults to the recorded starting branch; older tasks retain their previous default.
- `MirrorFile` continues to read `porelo.yml` at the default branch, which remains the trust boundary for reviewed image settings.
- Add optional daemon `Start.from` and answer the selected branch and source. A remote caller supplies its checkout's branch as `from`; the daemon never treats its own current directory as the caller's checkout.

## Window handover

The start dialog needs a starting-branch field, sent as `Start.from`, and must show `Start.baseBranch` and `Start.baseSource` (`FROM`, `CHECKOUT`, `PROJECT` or `DEFAULT`). When starting from a local checkout over a remote connection, read its symbolic branch locally and supply it; a detached checkout must ask for a branch. This repository does not change the window.

## Acceptance

- Unit tests demonstrate branch selection, missing/detached refusals, workspace contents and preservation on resume, durable branch state and the review base.
- On a prepared machine, extend `task/task-branch-scheme.feature` and `gate/gate-review.feature` with diverging main/develop histories: start from develop, push, review only the task's changes, and confirm guarded approval and online forwarding reach the task's forge branch.
- No acceptance scenarios or machine runs are authorized for this checkout.

## Validation

The clean focused run passes 312 tests, with none skipped, including the core project tests and the starting-branch regressions. The option-nullness guard and document tests pass too. The read-only review's checkout-failure and dry-run findings are reproduced by failing tests and fixed.

The unfiltered unit run stops in `porelo-wire`: the task namespace presents root directories as the overflow UID, so the existing socket ownership checks refuse them. The daemon socket regression cannot reach its assertion here. Run the full unit suite outside this namespace before accepting the change. No acceptance scenario or VM is run.
