# B668 — A Task Proposes An Idea

```json
{
  "$schema": "issue-1.json",
  "id": "B668",
  "title": "A Task Proposes An Idea",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 21,
  "blockedBy": [],
  "covers": "A task proposes an idea for itself over its own task socket, where the project file allows it, with Tasks1.ProposeIdea(title, whatMustBeTrue); the idea is kept with the task and offered on its List and Watch row.",
  "openQuestions": 0
}
```

Filed 2026-11-12 under ST313, part 1 of [PJ108](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj108). Analyzed by Agent Core.

**What must be true.** A task's agent can propose an idea, a new issue it found while working, without leaving its
task. It proposes it for itself only, over its own task socket, and only where the project file allows it. A client
that watches the task (the window) sees each idea once, with an id, and hands it on (Frontend's part 5).

**Size.** M: one method in Tasks1 on the task socket, a bound in the project file, a record beside the task's `done`
record, the row field, and the docs.

## Plan

Read in the code on 2026-11-12. `Tasks1.Done` is the model: the daemon takes the task from the socket it was called on
and refuses another task's name with `NotItsOwn` (`TaskMethods.done`). The declaration is kept in the task's record
directory (`TaskDone`, `paths.tasks().taskRecord`), which outlives the runtime directory.
1. **Red first.** In a daemon test:
   - an idea proposed over a task's own socket is kept and shown on its row;
   - one naming another task is refused with `NotItsOwn`;
   - one from a project whose file allows none is refused with `NotAllowed`;
   - a title over one line or too long, or an empty `whatMustBeTrue`, is refused with `InvalidParameter`.
2. **The allowance.** `limits.ideas` in `project.yml`: the most ideas one task may propose, default 0 (none). It is read
   at each call, like the mail rules. A call past the bound is refused with `NotAllowed`, naming the bound.
3. **The method.** `Tasks1.ProposeIdea(title: string, whatMustBeTrue: string) -> (id: string)`, only on a task's own
   socket. `title` is one line of at most 120 characters. `whatMustBeTrue` is at most 1000 characters, one or two
   sentences, and has no control characters but line breaks.
4. **The record.** `ideas.json` in the task's record directory, under a file lock like `done`. Each idea has an `id`
   (the task's own counter, `idea-1`, `idea-2`, ...), `title`, `whatMustBeTrue` and `at`.
5. **The row.** List's and Watch's row carries `ideas: [{id, title, whatMustBeTrue, at}]`, `[]` when none. The window
   hands each id on once. The ideas stay until the task is removed.
6. **Docs.** `doc/project-file.md` (`limits.ideas`), the Tasks1 reference, and what a task's agent is told about the
   method (`doc/running.md`, "Porelo in this task").

## Acceptance

- In a project with `limits.ideas: 2`, a task proposes two ideas; List shows both with their ids, and a third is
  refused. Seen red first.
- A task cannot propose an idea for another task, and a project without `limits.ideas` allows none.
