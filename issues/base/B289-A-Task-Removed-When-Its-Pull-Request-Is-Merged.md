# B289 — A Task Removed When Its Pull Request Is Merged

**Status:** Idea.

**What must be true.** A project can say that a task ends by itself once the person has merged its pull request on the
forge: the host sees the merge, as it reads the task's builds, and removes the task as `porelo task remove` would,
instead of the person removing it by hand.

- Only a merge of the task's own branch by a person counts; a closed pull request, a red build or a new push does not.
- What the task holds that is not merged (commits after the merged one, files in its workspace) stops the removal, and
  the person is told why.
- The person sees that the task was removed and by what (the project's setting).
- Off unless the project's file says so.

## The shape proposed

Porelo reads it itself: the daemon asks the forge about its own tasks, as it follows their builds today, so it
works with any forge it reaches, on this machine or not, and needs no way back to it (the operator's decision).
It sees that a person merged the task's pull request. **Porelo decides** and acts only if its own rules hold. A report from the forge, where the forge can reach
Porelo (porelo-host [HO28](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho28)), only makes it quicker and changes nothing of this.

## Why

The person's merge is the acceptance of a task's work; removing the task afterwards is a second step that only follows
it. The agent cannot know the work was accepted, so the host, which sees the merge, ends it. With
[B288](README.md#b288) starting the reviewer and the comments waking the agent ([B171](README.md#b171)), the person
then only reads and merges.

## To be checked

- Whether a task kept for further work on the same branch needs a way to say so.
