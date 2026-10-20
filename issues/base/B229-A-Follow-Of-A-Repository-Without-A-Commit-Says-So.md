# B229 — A Follow Of A Repository Without A Commit Says So

**Status:** Analyzed.
**Size:** S - one question after a failed fetch and one outcome in `Reconcile`, with its test; no contract.

**What must be true.** `porelo project follow` of a repository whose `HEAD` has no commit - none made yet, or `HEAD`
naming a branch that does not exist - says that, names the branch `HEAD` points to, and names the next step, instead
of passing on git's `couldn't find remote ref HEAD`.

## Why

`Reconcile` fetches the source's `HEAD` (`git fetch <source> HEAD`), and git answers both cases with the same line,
which names neither the cause nor what to do; reproduced with a fresh repository and with `HEAD` on `master` while
the commits are on `main`.

## Plan

Read in the code on 2026-10-20 (`apps/app-project`).

1. **Asked, not parsed**: git's line is translated (here it came in German), so it is never read. When the fetch
   in `Reconcile.run` (`:396`) fails, a second question goes to the same source with the same credential and
   environment: `git ls-remote --symref <source> HEAD`. Measured on 2026-10-20: for an empty repository and for
   `HEAD` naming a branch that does not exist, it exits 0 and prints nothing; for a healthy repository or a bundle it
   prints the commit.
2. **A new `Reconcile.Outcome.NO_COMMIT`**, ahead of `UNREACHABLE` (`:470`) when that answer is empty. Its detail:
   "`<source>` has no commit on HEAD"; for a path, the branch `HEAD` names (`git -C <path> symbolic-ref HEAD`) and the
   branches that do have commits (`git -C <path> for-each-ref refs/heads`); and the next step: commit there, or point
   `HEAD` at a branch with commits (`git symbolic-ref HEAD refs/heads/<branch>`), then follow again.
3. Nothing is recorded, as today.

**Red first:** `ReconcileTest`: following a repository made with `git init` and no commit answers `NO_COMMIT` and
names its branch; today it answers `UNREACHABLE` with git's line. The same for `HEAD` on `master` with commits on
`main`, naming `main`.

## Acceptance

- Both cases are named as such, with the branch and the next step, under `LANG=de_DE.UTF-8` as under `C`.
- A source that cannot be reached at all still answers `UNREACHABLE`.
