# [B586](README.md#b586) — A Task Name The Room Cannot Hold

**Status:** Backlog. Filed 2026-11-10 under ST290, from the reading of [B582](README.md#b582).
**Size:** S - the bracket's room in `TransportLifecycle.display`, and `capitalized` for an empty word.

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
