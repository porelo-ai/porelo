# B174 — A Running Task's Commits And Their Diff

**Status:** Analyzed.
**Size:** M - one contract change (two methods and a type in `15-task.varlink`), the census that `WorkHeld` already
runs widened to a list, the stop's note keeping that list, a diff read in the container and cut as [B150](README.md#b150) cuts a review,
and docs; no other repository's contract changes but the interface reading it.

**What must be true.** A client can ask the daemon which commits a running task has made that no remote holds - each
commit's id, subject and time - and the diff of one of them or of all together, without the work having reached the
gate. Today the contract gives only counts (`WorkHeld.unpushedCommits`, `AgentEnded.unpushed`), so the interface
cannot say in words what a piece of work did until it is pushed.

Asked for by `sokar-frontend`'s
[F112](https://github.com/sokar-ai/sokar-frontend/blob/main/issues/README.md#f112), which is blocked by it. Accepted
2026-10-11.

## Plan

- **Which commits:** the same set `WorkHeld` counts - `git log --branches --not --remotes` in the task's workspace
  (`UnhandedWork.ask`) - read as `%H%x09%ct%x09%s` instead of counted. One census gives both, so the count and the
  list never disagree.
- **`15-task.varlink`:** `type TaskCommit (commit: string, at: string, subject: string)` and
  - `method TaskCommits(task: string) -> (readable: bool, commits: []TaskCommit, asOf: ?string)`, read as
    `WorkHeld` is: live while the task runs; for a stopped task, from the note its stop wrote, with `asOf`.
  - `method TaskDiff(task: string, commit: ?string) -> (diff: string, files: []string, cut: bool)`: one commit's
    `git show --patch`, or without `commit` all of them together, from the oldest one's parent to the branch tip.
    A running task only; a stopped one is refused as `TaskNotRunning`, since its workspace is in a stopped container.
- **The commit asked for** must be one `TaskCommits` lists, by its full id; anything else is refused as
  `UnknownCommit` before git runs, so no ref name or option reaches git from the wire.
- **The size:** the diff is cut at a file boundary below the message limit, `cut: true`, and `files` always names
  every file - as [B150](README.md#b150) plans for `Review`, with the same helper.
- **The stop's note** (`unhanded-work`, `UnhandedWork.VERSION` 2) keeps the list beside the counts; version 1 notes
  still read, with an empty list and `readable` as before.
- **The command line:** `sokar task commits <task>` prints the list, and `--diff [<commit>]` the diff; the same code.
- **Red first:** `UnhandedWorkTest` - a census with three commits gives the list and the counts; `SokarDaemonTest`
  with a stand-in podman - `TaskCommits` of a running task, of a stopped one from its note, `TaskDiff` of an unknown
  commit refused before podman is asked; `InterfaceDescriptionTest` holds both methods, the type and the errors.
- **Docs:** the contract's comments, `doc/` where `WorkHeld` is described.

## Acceptance

- In `task-session.feature` (or its own), a task makes two commits; `sokar task commits` lists both with their
  subjects, newest first; `--diff` of the first shows its change and not the second's; the task pushes, and the list
  is empty.
- A stopped task's list is read from its note and says when it was true.
- A commit id not in the list, or a string that is not an id, is refused and git is never run with it.

## To be checked

- How long the census takes in a repository with a long unpushed history, measured, and whether the list needs a
  limit (Agent Core's).
