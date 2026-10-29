# B290 — A Merge Task Started When The Central Forge Moved

**Status:** Idea.

**What must be true.** When the gathered push
from the host's forge to the central one is
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

Porelo reads it itself: the daemon asks the forge about its own tasks, as it follows their builds today, so it
works with any forge it reaches, on this machine or not, and needs no way back to it (the operator's decision).
It sees the gathered push refused, since the push runs on its host, and reads the forge's `central/main`. **Porelo decides** and acts only if its own rules hold. A report from the forge, where the forge can reach
Porelo, only makes it quicker and changes nothing of this.

## To be checked

- Whether the gathered push hands the refusal to the daemon, or the daemon reads it from the push's own record.
