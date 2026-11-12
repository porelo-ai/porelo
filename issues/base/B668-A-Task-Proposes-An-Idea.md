# B668 — A Task Proposes An Idea

```json
{
  "$schema": "issue-1.json",
  "id": "B668",
  "title": "A Task Proposes An Idea",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
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

## Built (2026-11-12)

By a Claude Code sub-agent of Agent Core, uncommitted in `core-b668`.
- **The allowance.** `limits.ideas` in `project.yml` (`core`: both `Limits` records, `FileBinder`, `FileYaml`,
  `Sections`, `ProjectFileCheck`, `ProjectSchema`, `ProjectReader`): default 0, at most 100 (`Limits.MOST_IDEAS`),
  read at each call. The cap of 100 is new here: it bounds the record and every row Watch sends.
- **The method.** `Tasks1.ProposeIdea(task: string, title: string, whatMustBeTrue: string) -> (id: string)`,
  `TaskMethods.proposeIdea`, modelled on `Done`: it carries `task`, which must be the socket's own (`NotItsOwn`
  otherwise, and on the person's socket), since the Plan's refusal of another task's name needs a name to refuse. A
  task's endpoint carries it only where `mcp.methods` names it (`McpTaskSockets.WRITING`). `NotAllowed` (already in
  Tasks1) for 0, past the bound, or an unreadable file; `InvalidParameter` for an unknown parameter, a title that is
  blank, over 120 characters or holds a control character or line separator, or a `whatMustBeTrue` that is blank,
  over 1000 characters or holds a control character but `\n` and `\r`.
- **The record.** `TaskIdeas` (`app-base`), `ideas.json` in the task's record directory under `ideas.lock`, written
  staged and moved, owner-only; ids `idea-1`, `idea-2`, ... An unreadable record shows no ideas and refuses a new one,
  so nothing is numbered twice. A later start keeps them; removal deletes the directory.
- **The row.** `TaskInventory.row` adds `ideas: []TaskIdea`, `[]` when none; Watch compares the whole row, so a new
  idea is a change. A task socket without `Logs` or `Tail` receives `ideas: []` (`McpTaskSockets.WHAT_IT_WROTE`), so
  one agent's text does not reach another's prompt.
- **Docs.** `doc/project-file.md` (`limits.ideas`, the endpoint's methods), the Tasks1 contract's comments, and
  `doc/running.md` under "What a task's agent is told", beside `Done`. The guide in "Porelo in this task" is the same
  text for every task, so it names no method only some tasks have.
- **Tests seen red, then green:** `ProjectIdeasTest` and `FileRoundTripTest` (core); `TaskIdeasTest` (apps/app);
  `TaskIdeaMethodsTest` (daemon), which holds the acceptance with `limits.ideas: 2`. Written with the code and only
  seen green: the `limits.ideas: -1` case of `ProjectFileFaultsTest`, and the pin in `InterfaceDescriptionTest` of the
  method, `TaskIdea` and the row field. No feature: the kit has no step for a task's own socket.

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
