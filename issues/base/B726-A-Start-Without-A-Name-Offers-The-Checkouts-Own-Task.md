# B726 — A Start Without A Name Offers The Checkout's Own Task

```json
{
  "$schema": "issue-1.json",
  "id": "B726",
  "title": "A Start Without A Name Offers The Checkout's Own Task",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 7,
  "blockedBy": [],
  "covers": "`porelo task start` with no name, in a checkout whose repository has a running or stopped task of this person, offers to attach to the running one or start the stopped one again before it starts a new task; without a terminal it starts nothing and names them with their commands.",
  "openQuestions": 0
}
```

**Why this size.** The start's naming step learns the person's tasks of the checkout's repository; a question with three answers, a choice among several, the no-terminal refusal with its exit code, and tests. Estimate: 49 minutes (Core's median for M).

Operator finding on 2026-11-13, on snapshot.512 in the project `default`: after leaving task porelo-default_utils4j-2 with Ctrl+b d, `porelo task start` with no name in the same checkout started a second task, utils4j-2-2, instead of noticing the running one. The same holds for a stopped task.

## Plan

- Only for a start with no task name, at a checkout: find this person's tasks whose repository is the checkout's (by the repository the start would name, in the project the start would use).
- A running one comes first: "<task> is running. Attach to it instead? [Y/n]". Enter or y attaches as `porelo task attach <task>` does; n goes on to start a new task as today.
- Else a stopped one: "<task> is stopped. Start it again? [Y/n]". Enter or y starts it as `porelo task start <task>` does; n starts a new one.
- Several: name them, running first, and ask which by number, or n for a new one.
- Without a terminal (`--no-input`, or none attached): start nothing, name each task with `porelo task attach <task>` or `porelo task start <task>`, and end with 75 (try again by name); `--yes` does not answer this question.
- With an explicit task name nothing changes.

## Acceptance

- Red first: with a running task of the checkout's repository, a start with no name asks to attach, and Enter attaches; n starts a new task as today.
- With a stopped one, Enter starts that task again and no second task is made.
- With two, both are named and a number picks one.
- Without a terminal nothing starts, both commands are named, and the exit code is 75.
- A start with a name, and a checkout with no task of its repository, behave as today.
