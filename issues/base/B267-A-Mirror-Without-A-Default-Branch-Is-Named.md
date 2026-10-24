# B267 — A Mirror Without A Default Branch Is Named

**Status:** Review, published in Build 40088562915 of 2026-10-24.
**Size:** S - `MirrorFile`, `ImageChoice` and the gate's refresh, with their tests; no contract changes.

**What must be true.** When a repository's mirror names no default branch, a task start on it says so, naming the
repository and how to fix it, rather than going on as if the repository had no `porelo.yml`.

## Why

Seen on the shared VM on 2026-10-23: a project followed from a bare clone whose `HEAD` named `master`, while its only
branch was `main`, started its task with the project's own image. The repository's `porelo.yml` was never read, and
nothing said so. A refusal that was expected did not come, and the image was not the one the repository asks for.

Read in the code: `ImageChoice` asks `MirrorFile.defaultBranch` for the mirror's branch, and reads `porelo.yml` only
when there is one (`apps/app-model/.../ImageChoice.java:172-188`). With no branch it adds nothing, so the repository's
image quietly gives way to the project's.

Read in the gate: the mirror is made by `git clone --bare` (`GitGate.initialize`), which copies the upstream's `HEAD`
once. `GitGate.refresh` fetches `refs/heads/*` and never looks at `HEAD` again, so a mirror made while the upstream's
`HEAD` named a branch that did not exist keeps naming it after the upstream is fixed.

## Decided

- **The start is refused**, since a wrong image is worse than none: exit 64 with "nothing was created", as every other
  `porelo.yml` refusal.
- **The refresh takes the upstream's `HEAD`**, so fixing the upstream is the whole fix and nobody edits a mirror.
- **`project follow` warns** when the mirror it made names no default branch, and still follows: the project may be
  fixed upstream before its first task.

## Plan

1. **`MirrorFile.defaultBranch`** answers a sealed `DefaultBranch`: `Found(ref)`, `NoMirror` (not made yet, as
   today's empty answer means), `Missing(ref, branches)` (`HEAD` names a branch with no commit; the mirror's branches
   listed, at most five), `Detached` and `TaskBranch`. `MirrorFile.read` keeps its behavior for `NoMirror`.
2. **`ImageChoice.preview`** turns every answer but `Found` and `NoMirror` into its `mirrorRefusal`, so the start
   refuses only after the fetch (`ImageChoice.of`, `TaskLaunch` after `openWorkspace`), as it does for a broken
   `porelo.yml` today:
   `the mirror of matrix names refs/heads/master as its default branch, which does not exist (its branches: main), so
   its porelo.yml cannot be read; set the default branch of <upstream> to one that exists - the next start takes it`.
3. **`GitGate.refresh`**, after its fetch, asks `git ls-remote --symref <upstream> HEAD` through the same lease and,
   when the upstream names a branch the mirror now has, points the mirror's `HEAD` at it with `git symbolic-ref`. An
   answer that does not parse, or names a branch the mirror lacks, changes nothing.
4. **`ProjectFollowCommand`** prints one warning line with the same words when a repository's mirror answers
   `Missing`.
5. **Tests, red first:**
   - `ImageChoiceTest`: a bare mirror whose `HEAD` names `master` while only `main` exists makes `ImageChoice.of`
     refuse, naming `master`, `main` and the upstream; today it returns the project's image.
   - `GitGateTest`: an upstream whose `HEAD` moved from `master` to `main` moves the mirror's `HEAD` on the next
     refresh.
6. A changelog line under *Fixed*.

- **Build estimate=90**, like porelo [B261](README.md#b261) (S, a confirmed answer through two modules), with the
  gate's test repositories.

## Acceptance

- A start on a repository whose mirror names a missing default branch is refused, naming the branch, the branches
  there are, and the fix; nothing is created.
- After the upstream's default branch is fixed, the next start reads `porelo.yml` with no hand edit of the mirror.
- `project follow` on such a repository warns with the same words.
