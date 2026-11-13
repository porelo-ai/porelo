# B692 — A Task List Showing An Agent Waiting On Its Own Question

```json
{
  "$schema": "issue-1.json",
  "id": "B692",
  "title": "A Task List Showing An Agent Waiting On Its Own Question",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
  "rank": 46,
  "blockedBy": [],
  "covers": "`porelo task list` says when an attached agent's screen reads as waiting for its own question, apart from its activity, as `task status` already does.",
  "openQuestions": 0
}
```

**Why this size.** One column or marker in `TaskListCommand`'s table from the row's `screen`, and one test.

Filed 2026-11-13 from [B506](README.md#b506): an attached agent asking its own question has `screen` `WAITING` in
the daemon's row and in `task status`, but `task list` prints only ACTIVITY, which stays `unknown`, so the list does
not show that somebody is waited for.

**What must be true.** A task whose screen reads as waiting is marked in `task list`, as a reading and not as its
activity: the contract keeps `screen` apart from `activity`. A task with no declaration shows no mark.

## Plan

- `TaskListCommand`: a marker after ACTIVITY (for example `unknown, reads as waiting`) from `screen` `WAITING`.
- Red first: `TaskActivityTest`'s attached agent with its own question on screen is listed with the mark; a task with
  an undeclared screen is not.

## Acceptance

- `task list` marks an agent waiting on its own question; a test fails while the list leaves it out.
