# B586 — A Task Name The Room Cannot Hold

```json
{
  "$schema": "issue-1.json",
  "id": "B586",
  "title": "A Task Name The Room Cannot Hold",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 14,
  "blockedBy": [],
  "covers": "A long task name on a clash, and one with two hyphens in a row, still get a name in the room."
}
```

**Why this size.** the bracket's room in `TransportLifecycle.display`, and `capitalized` for an empty word.

Filed 2026-11-10 under ST290, from the reading of [B582](README.md#b582).

**What must be true.** Every task enrolled in a room that shows names is given one no other task holds, within the
room's 64 characters: also a task whose own name is 54 characters or more when another task holds it, and a task whose
name has two hyphens in a row (`foo--bar`, which the task name pattern allows).

## Plan

Read in the code: `display` cuts the bracket to `64 - first - 3` characters; with a long own name that is 0 or less,
`ContainerName.shortened` gives an empty cut and `display` returns `null`, so the task is enrolled with no name and the
room shows its account. `capitalized` returns `null` on an empty word between two hyphens.

- Where the bracket has no room, shorten the name before it, so name and bracket fit.
- Keep the empty word in `capitalized` (`Agent Foo--Bar`).
- Red first: a 55-character task name on a clash, and `foo--bar`, each get a name.

## As built

- `TransportLifecycle.display` shortens the name before the bracket only when the bracket and its numeric suffix
  otherwise leave no room for a character. Each candidate fits in 64 characters and is checked against the room's
  other task names without regard to case; numbered candidates reserve their space anew.
- `capitalized` preserves empty words between hyphens, so `foo--bar` is shown as `Agent Foo--Bar`.
- Four unit tests in `TransportLifecycleDisplayTest` were seen failing on behavior before the fix: a clashing
  55-character task name, a clashing 64-character room display, consecutive hyphens, and two clashing long task names
  sharing their first 54 characters. The 64-character display uses the existing agent-name fallback for a container
  outside the project's task form: a literal 64-character task name is already refused by container-name validation,
  which remains unchanged.
- The existing `task-shown-by-its-name.yml` changelog entry includes the room-length and consecutive-hyphen fixes.
