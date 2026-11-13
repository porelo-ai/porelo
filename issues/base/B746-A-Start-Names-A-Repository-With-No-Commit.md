# B746 — A Start Names A Repository With No Commit

```json
{
  "$schema": "issue-1.json",
  "id": "B746",
  "title": "A Start Names A Repository With No Commit",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 71,
  "blockedBy": [],
  "covers": "A start in a checkout that holds no commit is refused before anything is added or made, naming a repository with no commit and the commit to make first.",
  "openQuestions": 0
}
```

**Why this size.** S: one check in the start from a checkout, before the checkout is added to `default`, its refusal
line, and the tests in `apps/app`. Estimate: 45 minutes (Core's median for S).

**What must be true.** A person who starts a task in a fresh repository is told that it holds no commit yet and to
commit first. Nothing is left behind from the start that was refused.

Found while playing [B682](README.md#b682) on a test VM (2026-11-13). The checkout was `git init` with an `origin`
and no commit, and the start was `porelo task start empty-… --agent stub --detach --clearance deny --no-input`:

```
project        added to 'default' as 'st30-…-empty': its history comes from /tmp/…/st30-…-empty, …
…
porelo: starting branch 'main' is not in the mirror; publish it to the repository's source and fetch again, or name another with --from
porelo: nothing was created
```

The exit code was 64. The refusal names a missing branch and never says that there is no commit. The checkout is
still in `default` afterwards (`porelo project default list` shows it), and its mirror stays under
`$XDG_DATA_HOME/porelo/mirrors/default/<name>.git`, although the start says nothing was created.

## Plan

- `WorkOrigin.fromCheckout` (`apps/app-model`) adds the checkout to `default` through `DefaultProject.add` before
  anything reads its history. Before that, ask `git -C <top> rev-parse --verify --quiet HEAD^{commit}`. A checkout
  with no commit is refused there with a `ProjectException`: "<path> holds no commit yet: commit your work (`git
  commit`) and start again". Nothing is added or mirrored.
- `TaskBase` (`apps/app-model`, the "is not in the mirror" refusal at about line 141) keeps its words for a branch
  that really is missing. A mirror that holds no branch at all is named as a repository with no commit, so a
  repository of `default` added by its address and still empty is also told what it is.
- First red, in `apps/app`: `TaskBaseTest` gives an empty mirror and expects the words "no commit". A new test of the
  start from a checkout with no commit expects the refusal, no entry in `default` and no mirror directory.

## Acceptance

- A start in a checkout with no commit is refused, names a repository with no commit, and names `git commit` as the
  next step.
- After it, `porelo project default list` does not list the checkout and no mirror of it exists.
- A start from a checkout with a commit, and the refusal of a branch that is really missing, are unchanged.
