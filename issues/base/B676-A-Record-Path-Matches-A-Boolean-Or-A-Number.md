# B676 — A Record Path Matches A Boolean Or A Number

```json
{
  "$schema": "issue-1.json",
  "id": "B676",
  "title": "A Record Path Matches A Boolean Or A Number",
  "state": "Backlog",
  "size": "XS",
  "priority": "Low",
  "rank": 54,
  "blockedBy": [],
  "covers": "An agent description's `.record` match (`last_message.record`, `session_id.record`) can name a boolean or number field: `is_error: false` matches the record's false, as a string field matches today."
}
```

**Why this size.** one comparison in `RecordPath.matches`, its tests, and a line in the agent description's docs.

Filed 2026-11-12 from a note of the agent repositories' lane, straight into the backlog as the bug rule says.

**What was seen.** An agent description whose `last_message.record` named `is_error: false` never matched a record
holding the JSON boolean false; the description had to match a string field (`terminal_reason: completed`) instead.

**Found, read in the code.** `RecordPath.matches` (agents/api) compares each wanted value, read from YAML as a
string, with `String.equals` against the record's value, which is a `Boolean` or a `Number` for such a field.

**What must be true.** A wanted value matches a record's boolean or number when it is written as that value: `true`
or `false` for a boolean, the number's JSON form for a number; a string field still matches only the same string, and
`"false"` in quotes matches only a string.

## Plan

- `RecordPath.matches`: compare a `Boolean` with `Boolean.toString`, a whole `Number` with its integer form, other
  numbers with their JSON form; keep a string field's comparison as it is.
- The description reader keeps the YAML scalar's own type, so a quoted `"false"` stays a string.
- Red first: `is_error: false` against `{"is_error": false}` matches; `"false"` quoted does not; a number likewise.

## Acceptance

- A description matching `is_error: false` finds the record that holds false; a test fails while it does not.
