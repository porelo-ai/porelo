# B288 — A Reviewer Started When The Build Is Green

**Status:** Idea.

**What must be true.** A project can say that a task's pushed work is reviewed by an agent as soon as the forge's build
of it is green: Porelo then starts a reviewer task ([B172](README.md#b172)) on that pull request by itself, with the
agent and the provider the project names, instead of the person starting it.

- The reviewer is started once per green build of a new commit, never for a red one, and never for a commit that already
  has a review running or done.
- The person sees that it was started and by what (the project's setting), and can stop it like any task.
- A reviewer task ends when its comments are delivered; it starts no second reviewer and pushes nothing.
- Off unless the project's file says so.

## Why

Today the person starts the reviewer by hand after watching the build. The review is wanted after every green build, so
starting it is a step that only waits on the build's verdict, which the host already reads
([B253](README.md#b253)). The developer agent is woken by the comments by itself
([B171](README.md#b171)), so with this the loop from push to reviewed work needs the person only for the merge.

## The shape proposed

A step of the forge's own workflow is only the trigger: it tells Porelo, through a narrow endpoint of the
broker whose token can do nothing else, that the build of a commit has finished. **Porelo decides** (the operator's decision): it reads the verdict with its build reader ([B253](README.md#b253))
itself and starts the reviewer only if its own rules hold. The forge decides nothing, since a workflow is code in the
repository the agents push and a task could change it; a changed workflow can at most send one report too many,
which Porelo checks against what it reads.

## To be checked

- Where the setting lives in the project file, and whether it names one reviewer agent or a list to choose from.
- What bounds the reviews a project can start in an hour, so a pushing agent cannot start reviewers without end.
