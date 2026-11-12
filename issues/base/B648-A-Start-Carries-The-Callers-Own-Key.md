# B648 — A Start Carries The Caller's Own Key

```json
{
  "$schema": "issue-1.json",
  "id": "B648",
  "title": "A Start Carries The Caller's Own Key",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 87,
  "blockedBy": [],
  "covers": "Start takes an opaque key from its caller, at most 200 characters, kept with the task from its creation and given back by List and Watch, so a caller that restarts finds every task it started."
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

## To be checked

- Where the key is kept: in the task's profile at creation (`TaskLaunch`, before the container starts), so a start
  that fails still shows it on the task it left.
- Its limits: at most 200 characters, with no control characters, refused as Start's other parameters are.
- Whether `task run` gets the same as an option (`--caller-key`), or it stays an interface parameter.
