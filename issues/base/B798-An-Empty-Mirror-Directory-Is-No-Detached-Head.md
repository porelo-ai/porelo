# B798 — An Empty Mirror Directory Is No Detached HEAD

```json
{
  "$schema": "issue-1.json",
  "id": "B798",
  "title": "An Empty Mirror Directory Is No Detached HEAD",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 140,
  "blockedBy": [],
  "covers": "A mirror directory that holds no repository is taken as no mirror and made anew, not as a detached HEAD whose fix is at the upstream; no task leaves an empty directory where the project's own mirror belongs"
}
```

**Why this size.** S: `MirrorFile.defaultBranch` (`apps/app-model/.../MirrorFile.java`, around line 86) reads any
directory as a mirror. An empty one fails `git symbolic-ref` and is said as `Detached`. Also find what left the empty
directory and stop it. A unit test for each.

**What must be true.** A person never has to remove a directory on the machine by hand to start a task, and is never
sent to change a forge's default branch that is already right.

## Found

On 2026-11-17, in a live test on a rented host. A discussion did not start: "the mirror of porelo has a detached HEAD
instead of a default branch … set the default branch of git@github.com:porelo-ai/porelo-project.git to one that
exists". GitHub's default branch was `main`. On the host, `mirrors/porelo.git`, the project's own mirror, was an empty
directory, and the host had no mirror of that repository. After `rmdir` the discussion started. The next discussion
failed the same way: the empty directory was back.

## Plan

- Today: `defaultBranch` answers `NoMirror` only when the directory is absent. An empty directory gives `Detached`,
  and `refusal` (around line 154) points at the upstream.
- Change, the reading: a directory that is no git repository (empty, or without `HEAD`) is taken as no mirror. It is
  removed and made anew from the upstream, as an absent one is. A mirror whose `HEAD` is really detached keeps its
  words, and they name the mirror's path on the machine first.
- Change, the cause: find what makes the empty `mirrors/<project>.git`. The likely place is a task's mount of the
  project's own mirror, which the container runtime makes empty when it is missing. Make the mirror before the task
  starts, or mount nothing that is not there.
- Red first: a test with an empty directory at the mirror's place. Today it is refused as a detached HEAD; after the
  change it is made anew. A second test: a start with no own mirror leaves no empty directory behind.

## Acceptance

- An empty mirror directory is made anew, and the start goes on.
- No task leaves an empty directory at a mirror's place.
- A real detached HEAD is said with the mirror's path, not only the upstream.
- The tests above fail without the change.
