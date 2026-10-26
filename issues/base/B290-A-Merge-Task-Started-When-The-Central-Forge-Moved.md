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

A step of the forge's own workflow is only the trigger: it tells Porelo, through a narrow endpoint of the
broker whose token can do nothing else, that the gathered push of a repository was refused. **Porelo decides** (the operator's decision): it reads the refusal and the forge's `central/main`
itself and starts the merge task only if its own rules hold. The forge decides nothing, since a workflow is code in the
repository the agents push and a task could change it; a changed workflow can at most send one report too many,
which Porelo checks against what it reads.
It is the same endpoint as [B288](README.md#b288)'s.

## To be checked

- Whether the gathered push hands the refusal to the daemon, or the daemon reads it from the push's own record.
