# B460 — A Task Starts On The Branch Of Its Checkout

**Status:** Backlog.
**Size:** L - a guess: the start itself is small, what compares with the default branch is not read yet.

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
