# B358 — A Task Reviews A Pull Request On The Central Forge

**Status:** Accepted (operator, 2026-10-31); analysed after sprint 6m.
**Blocked by:** [B172](README.md#b172).
**Size:** - (estimated at its analysis).

**What must be true.** A reviewer task, as [B172](README.md#b172) builds it, also takes as its target any existing pull
request on the central forge, named by its URL, that no task made and no project maps, and it does so in a project or
without one. Everything else is the reviewer's as [B172](README.md#b172) has it: read-only, pinned to the pull
request's head, no push, SARIF comments and findings only, read and answered in IntelliJ, and each one reaching the
pull request only after the person releases it.

## Why

[B172](README.md#b172) reviews another task's work, and a pull request only where [B171](README.md#b171)'s
authenticated mapping ties it to a task of the same project. A pull request somebody else opened, or one in a
repository no project names, cannot be reviewed that way, and a task without a project reaches no forge at all. What
this adds to [B172](README.md#b172): the pull request's URL as a target, and fetching its base and head into the
task's gate read-only.

## The shape

- The person needs no write access to the pull request's repository and no fork, only the right to read the pull
  request and comment on it.
- The pull request is read, and the released comments are written, with the person's own forge account: they appear
  on the pull request under the person's name. The task's container holds no token.
- A new head pushed to the pull request starts no review round on its own: IntelliJ shows that the pull request moved,
  and the person starts the next round.
- Only comments go out, never a verdict: approving or requesting changes is the person's own act on the forge's page,
  so an agent never adds to what lets a pull request merge.

Taken over from the project's requirement handed over on 2026-10-31.
