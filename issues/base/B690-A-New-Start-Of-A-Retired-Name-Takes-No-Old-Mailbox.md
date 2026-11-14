# B690 — A New Start Of A Retired Name Takes No Old Mailbox

```json
{
  "$schema": "issue-1.json",
  "id": "B690",
  "title": "A New Start Of A Retired Name Takes No Old Mailbox",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 44,
  "blockedBy": [],
  "covers": "A start of a task name whose removal could not delete its mailbox begins with an empty mailbox, never the removed task's conversation and refused originals.",
  "openQuestions": 0
}
```

**Why this size.** one check where a start makes its mailbox, its refusal or clean-up, and tests.

Filed 2026-11-13 from building the removal that retires its name whatever its deletes do, straight into the backlog as the bug rule says.

**What was seen.** Read in the code, not run. A removal whose mailbox delete fails still
retires the name, so the same owner can start that name again. `TaskLaunch` makes the mailbox with `Mailbox.create()`,
which keeps what is already there: the new task would find the removed task's conversation and the refused originals
under `filter/rejected`. While such a removal left the name blocked, this could not happen.

**What must be true.** A new task starts with an empty mailbox. What a removed task of the same name left behind is
deleted before the start, or the start is refused, naming the leftover and how to clear it (`porelo prune`).

## Plan

- Where the start makes the mailbox: a mailbox that exists for a name whose claim was retired is deleted first; one
  that cannot be deleted refuses the start with 70, naming the path's kind (never the path itself) and `porelo prune`.
- Red first: a retired name with a leftover mailbox holding a message and a refused original; a start of that name
  sees neither.

## Acceptance

- A start of a retired name never sees the removed task's mail; a test fails while it does.

## Built

`TaskReservations` removes a retired name's leftover mailbox while it still holds the name lock,
before the new reservation is published, so no new task inherits the old task's mail and no other
start can claim the name in between. If the mailbox cannot be removed, the start is refused with
`MailboxNotDeleted`: `TaskLaunch` exits 70, and the message names `porelo prune`. The retired claim
stays.

Tests (apps/app): `TaskReservationsTest.reservingARetiredNameDeletesItsLeftoverMailbox`,
`aRetiredMailboxThatCannotBeDeletedRefusesTheStartWithRecoveryGuidance`,
`anotherOwnerCannotDeleteARetiredTasksLeftoverMailbox`, and
`TaskRunCommandTest.aNewStartOfARetiredNameSeesNoOldMailOrRefusedOriginals`. Red on the base with
only the tests changed: 2 failures and 1 error in 455 tests. Green on the change: 455 tests, 0
failures.
