# B713 — Four Small Readings Of Marks And Providers

```json
{
  "$schema": "issue-1.json",
  "id": "B713",
  "title": "Four Small Readings Of Marks And Providers",
  "state": "Backlog",
  "size": "S",
  "priority": "Low",
  "rank": 67,
  "blockedBy": [],
  "covers": "The user documentation names only options that exist, `gate pending` shows what arrived, an agent definition with a provider key it does not know is refused, and the agents README's example says its dialect.",
  "openQuestions": 0
}
```

**Why this size.** Four small changes, three of them in text.

Filed 2026-11-13 from the reviewer readings of the room marks of a message's sender and of a provider that knows its
models, both in Review and not yet on main, where nothing here stops a merge:

- `doc/commands.md` says `certified` is `false` "in `task list --json`". `task list` has no `--json`. Name the output
  that carries it (`task status --json`, or the daemon's List).
- `porelo gate pending` lists a held arrival without its arrival mark (accepted, unsigned, and so on), which the room
  and the daemon's reply show. Print it.
- The agent definition reader ignores a provider key it does not know, so a misspelt key silently does nothing.
  Refuse it with the key's name and the keys it knows, as the project file does.
- The example definition in `agents/README.md` names no dialect, which a reader copies and then fails on. Add it.

**What must be true.** Each point above holds, with a test where it is code.

## Acceptance

- A test that `gate pending` prints an arrival's mark, and one that an unknown provider key is refused.
- check-citations and the documentation tests hold.
