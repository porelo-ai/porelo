# [B505](README.md#b505) — Where Delivered Content Came From, In Review

**Status:** Backlog.
**Size:** M - a field in `Review` (`35-gate`), `GateMethods`, read from the hand-in records, and its test.

**What must be true.** The gate's review says, beside what a push changes, where the content Porelo delivered into the
task came from - each hand-in and build log with its source - so a reviewer can tell what the agent was given from
what it made.

## Plan

- `Review` gains the delivered files' origins from the records `HandIns` already keeps; additive under `Tasks1`.
- Red first: a unit test with a handed-in file whose origin the review answers.

## Acceptance

- Each delivered file is named with its origin; a task given nothing answers none.
