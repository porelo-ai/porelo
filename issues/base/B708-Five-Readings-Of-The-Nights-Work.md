# B708 — Five Readings Of The Night's Work

```json
{
  "$schema": "issue-1.json",
  "id": "B708",
  "title": "Five Readings Of The Night's Work",
  "state": "Review",
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

- **[B674](README.md#b674).** `TaskControl.retireDeleting` catches only `IOException`. An `UncheckedIOException` or other runtime
  failure of the guide's, the repositories' or the result's delete still skips the retire, which is the bug [B674](README.md#b674)
  closed for the mailbox. `TaskReservationsTest`'s undeletable directory is read-only, so as root the delete succeeds
  and the tests fail; it wants `assumeFalse` for root. And a comment, at the order of the mailbox delete before the
  retire, so a later reorder does not reopen the race.
- **[B506](README.md#b506).** Two `TaskActivityTest` cases assert the activity only as not `waiting`, or as neither at rest nor working;
  any other wrong activity passes. Pin it to `unknown`. The stub test reads the question out of the rendered
  script with a pattern that breaks on reformatting ([B693](README.md#b693) lets it run the script instead).
- **[B316](README.md#b316).** A name nothing asks for prints `used for  nothing yet - …` on standard output, the prefix a reader takes
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

## Built

- **A removal retires its name whatever fails before it.** `TaskControl.retireDeleting` catches
  `IOException | RuntimeException` and keeps the first failure, with the later ones suppressed. The name is retired
  after every delete has been tried. An IO failure then throws as before ("Removed the task but could not delete its
  mailbox"); any other is rethrown as itself. A comment says why the retire comes last: a retired name is free, and a
  delete after it could take a new task's files.
- **An undeletable mailbox holds as root too.** The tests' `undeletable()` and the new `unwalkable()` helper skip
  themselves when run as root.
- **The agent's own question pins its activity.** The two `TaskActivityTest` cases now assert `UNKNOWN`, no longer
  only what it is not.
- **`vault put` says a name nothing asks for under its own word.** It reads `unused    until something asks for it: …`,
  no longer beginning "used for". `credentials-commands.feature` follows it.
- **The misfit check for asked names.** An entry stored with `--type ssh-key` under a name a project asks for, as a
  build reader's or as a `credentials:` entry, is told on standard error that its use sends it as a token and cannot
  use an ssh key. Both send the entry as a token, which is why `credentials:` entries are checked too.
- **Left to [B693](README.md#b693):** `StubAgentTest` still reads the stub's question out of its script. Running the
  script needs [B693](README.md#b693)'s change to the stub's definition.
- **Tests:**
  - New: `TaskReservationsTest.aRemovalWhoseGuideCannotBeWalkedStillRetiresTheNameAndSaysSo`, two ssh-key cases in
    `VaultPutCommandTest`, and three for cases the readings found untested (a project's own builds over the host's,
    the default project not read, an unreadable project file hiding only itself).
  - They failed on the build box before the change (`buildbox-porelo-a26884b9eef7-20261113T173928Z.log`), or under
    a mutant where the code already held (`buildbox-porelo-1cdfdc0335cd-20261113T174207Z.log`).
  - They pass after it (`buildbox-porelo-ee45e9898883-20261113T174419Z.log`): 602 tests, 0 skipped. The feature file's
    scenario is not run here.
