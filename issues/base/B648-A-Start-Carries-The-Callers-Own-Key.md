# B648 — A Start Carries The Caller's Own Key

```json
{
  "$schema": "issue-1.json",
  "id": "B648",
  "title": "A Start Carries The Caller's Own Key",
  "state": "Backlog",
  "size": "M",
  "priority": "Highest",
  "rank": 3,
  "blockedBy": [],
  "covers": "Start takes an opaque key from its caller, at most 200 characters, kept with the task from its creation and given back by List and Watch, so a caller that restarts finds every task it started.",
  "openQuestions": 0
}
```

Filed 2026-11-12 from porelo-frontend's findings of F492 runs 24–26. The window hands a planner's offer to a task
with `Start`. Start answers the container alone. If the window dies between Start's answer and keeping the pair, a
window that restarts cannot tell which of the listed tasks the offer belongs to. The existing `Label` does not close
the gap. It is a caption a person reads and edits, and it is set by a second call after Start, which leaves the same
window open.

**What must be true.** A program that starts a task can give it a key of its own at the start, in the same call, and
read that key back from the task's listing after any restart of its own, of the daemon or of the machine. A person
never sees the key as a caption, and a person's label never overwrites it.

**Size.** M: one field through the start, the profile (a wire contract), Tasks1 Start, List and Watch, and the CLI;
the code is small.

## Plan

Read in the code on 2026-11-12. A task's profile (`org.fuin.porelo.wire.TaskProfile`) is written once the container
exists (`TaskLaunch.java:4171`). The state directory is made earlier (`:3418`), where the starting branch is already
written. `Label` changes the profile afterwards (`TaskControl.label`). List and Watch read the profile into
`TaskInventory`'s row.
1. **Red first.** A `TaskInventoryTest` row of a task started with a key answers `callerKey`. A `TaskRunCommandTest`
   start that fails after its state directory was made still lists the task with its key.
2. **The start.** Tasks1 Start takes `callerKey` (at most 200 characters, no control characters, else
   `InvalidParameter`), and `task run --caller-key` gives the same, as `Label` serves both surfaces. The key is written
   beside the starting branch at `:3420`, before anything else can fail, and the profile carries it from `:4171`.
3. **Kept apart from the label.** `Label` never changes it, and nothing a person types changes it. A resume keeps it.
4. **The row.** List's and Watch's row carries `callerKey` ("" when none).
5. **Docs.** The Tasks1 reference names it as the caller's own, opaque to porelo.

## Acceptance

- Start with a key, then List (also after a daemon restart and after a failed start): the row carries the key. A
  `Label` call leaves it unchanged. An over-long key is refused. Each seen red first.

## To be checked

- Whether a failed start's task is listed at all today when only its state directory exists; if not, the failed start
  shows its key where it shows its failure.

## Decided by the operator (2026-11-12)

- Accepted, priority Highest, with Estimated and Backlog given in advance for its analysis (ST314, run 26).
