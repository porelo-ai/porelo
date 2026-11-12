# B633 — A Task Record Refusal Names Its Failed Check

```json
{
  "$schema": "issue-1.json",
  "id": "B633",
  "title": "A Task Record Refusal Names Its Failed Check",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 3,
  "blockedBy": [],
  "covers": "A task whose identity record is refused as unreadable is refused with the check that failed - which file, and what it held against what was expected - so a person or the window can tell a broken record from a stale one without reading porelo's code."
}
```

**Why this size.** One refusal record gains its check, about a dozen places in `TaskIdentities.read` name theirs,
and the three places that print the refusal print it.

Filed 2026-11-12 under ST314. While a task's attach was refused in porelo-frontend's F492 runs, the only words were
"its identity record cannot be read". `TaskIdentities.read` gives that one reason for about a dozen different checks.
Telling them apart took reading the code. The cause found in the end was the walk's own `XDG_RUNTIME_DIR`, which
moved the state directory a sidecar names.

**What must be true.** Every `INVALID_RECORD` refusal names its check in a person's words, such as "its sidecar names
the state directory <path>, not <path>" or "its recorded id file holds no id". The refusal's reason and its name stay
as they are, so code that branches on the reason is unchanged. The words go wherever the reason's words go today:
`porelo task` targets, `task refresh` and `talk pass`. Nothing secret is named: paths under porelo's own state, ids
and task names only.

## Plan

1. `TaskIdentities.Refused` gains `check`, a person's words for what failed ("" where the reason says it all), and
   `said()`, the reason's words followed by the check. A two-argument constructor keeps today's callers.
2. Each `INVALID_RECORD` in `TaskIdentities` names its check: a container name that is no task's, a recorded id file
   with no id, a sidecar that is no regular file or cannot be parsed, a sidecar's state directory, the two sidecars
   disagreeing on the task or short names, a short name refused, the recorded task against the runtime's label, a task
   name refused, and a container name that is neither the legacy nor the encoded form.
3. `TaskTarget`, `TaskRefresh` and `TalkPassCommand` print `refused.said()`.
4. Red first: a sidecar naming another state directory is refused with both paths named.
