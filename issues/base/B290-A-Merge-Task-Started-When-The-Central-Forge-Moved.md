# B290 — A Merge Task Started When The Central Forge Moved

**Status:** Idea.

**What must be true.** When the gathered push
(`porelo-buildtools` [BT06](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt06)) is
refused because the central forge's `main` holds commits the local forge lacks, Porelo starts a task by itself that
merges the branch `central/main` into the local work and pushes it as any task does. Its work is built, reviewed and
merged by the person like any other; the person then starts the gathered push again.

- One merge task per refused repository and per `central/main` commit, never a second while one runs.
- The task has the project's agent and provider, as a task the person starts would.
- The person sees that it was started and why (the refusal, naming the commits).
- Off unless the project's file says so.

## Why

A refused push is a regular case, since the person merges on the central forge too (Dependabot's pull requests among
them). Starting the task that merges the central changes only waits on the refusal, which the host already sees.

## The shape proposed

A forge event is only the trigger: the forge's own webhook, routed by porelo-host [HO28](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho28) to the daemon of the
account the task belongs to, tells Porelo that the gathered push of a repository was refused (the push reports it itself, as it runs on the host). **Porelo decides** (the operator's decision): it reads the refusal and the forge's `central/main`
itself and starts the merge task only if its own rules hold. The forge decides nothing; a report too many changes nothing,
since Porelo checks it against what it reads.

## To be checked

- Whether the gathered push hands the refusal to the daemon, or the daemon reads it from the push's own record.
