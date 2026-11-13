# B708 — Five Readings Of The Night's Work

```json
{
  "$schema": "issue-1.json",
  "id": "B708",
  "title": "Five Readings Of The Night's Work",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 62,
  "blockedBy": ["B674", "B506", "B316"],
  "covers": "A removal retires its name whatever fails before it, a test of an undeletable mailbox holds as root too, the tests of an agent's own question pin its activity, and `vault put` says a name nothing asks for apart from a use.",
  "openQuestions": 0
}
```

**Why this size.** Small changes in four places, each with its test.

Filed 2026-11-13 from the reviewer readings of [B674](README.md#b674), [B506](README.md#b506) and
[B316](README.md#b316), where nothing was found to stop a merge:

- **B674.** `TaskControl.retireDeleting` catches only `IOException`. An `UncheckedIOException` or other runtime
  failure of the guide's, the repositories' or the result's delete still skips the retire, which is the bug B674
  closed for the mailbox. `TaskReservationsTest`'s undeletable directory is read-only, so as root the delete succeeds
  and the tests fail; it wants `assumeFalse` for root. And a comment, at the order of the mailbox delete before the
  retire, so a later reorder does not reopen the race.
- **B506.** Two `TaskActivityTest` cases assert the activity only as not `waiting`, or as neither at rest nor working;
  any other wrong activity passes. Pin it to `unknown`. The stub test reads the question out of the rendered
  script with a pattern that breaks on reformatting ([B693](README.md#b693) lets it run the script instead).
- **B316.** A name nothing asks for prints `used for  nothing yet - …` on standard output, the prefix a reader takes
  for a use. Say it under another word. The misfit check (an entry's type against what asks for it) is not applied to
  names a project asks for: `vault put github-actions --type ssh-key` is called used by the build reader with no
  warning. Untested: a project's own file over its host's, the skip of `default`, an unreadable file skipped quietly.

**What must be true.** Each point above holds, with a test that fails without it.

## Plan

- `retireDeleting`: catch `IOException | RuntimeException`, keep the first as the failure; red first, a deletion that
  throws `UncheckedIOException` still leaves the name retired.
- The four test fixes, and the `vault put` line under its own word with its test.
- The misfit check for asked names, red first with `--type ssh-key` for a build reader.

## Acceptance

- Each point has its test, seen failing first.
