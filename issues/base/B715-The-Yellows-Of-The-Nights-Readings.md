# B715 — The Yellows Of The Night's Readings

```json
{
  "$schema": "issue-1.json",
  "id": "B715",
  "title": "The Yellows Of The Night's Readings",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 69,
  "blockedBy": ["P09", "B622"],
  "covers": "An agent's default provider is one it knows, the start check's refusal of an unknown provider is tested and names where the provider came from, `porelo keep` never leaves an older work record than the one it was given, and a task that wrote no result costs a listing nothing.",
  "openQuestions": 0
}
```

**Why this size.** Small changes in five places, each with its test.

Filed 2026-11-13 from the Sonnet readings of the night's work, where nothing was found to stop a merge. Two of the
changes read are not on main yet: a result taken only from a task that ran, and Agent Smith's tests under any login
and umask. Smith files the latter's points himself.

- **[P09](../providers/README.md#p09).**
  - Nothing checks that `provider.default` is among `knows`, so an agent definition can refuse its own default on
    every start. Refuse such a definition when it is read.
  - The new `StartCheck` arm for `UNKNOWN_TO_AGENT` has no test of its wire value and exit code.
  - Its refusal says `--provider` when the provider came from the project's setting. Name where it came from.
- **[B622](README.md#b622).**
  - A restore can leave an older work record than the kept one. Machine B restores an early keep, the task goes on on
    A, A keeps again, and B's restore keeps its own older record as "newer". Compare by the record's own time, not
    by which copy is on the machine.
  - The "no records were kept" message names only the usage path.
  - A linked or unreadable `*.json` gives exit 70, where the documentation says it is "not copied".
- **A result taken only from a task that ran.** A kept `MISSING` is retaken at every listing, with its lock and a
  rewrite of the record, for a task that wrote nothing. Skip the write when the new record equals the kept one. No
  test covers `Initialized`, an empty status or two retakes at once.

**What must be true.** Each point above holds, with a test that fails without it.

## Acceptance

- Each point's test holds and fails without its change.
