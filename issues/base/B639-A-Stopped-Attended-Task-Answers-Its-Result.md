# B639 — A Stopped Attended Task Answers Its Result

```json
{
  "$schema": "issue-1.json",
  "id": "B639",
  "title": "A Stopped Attended Task Answers Its Result",
  "state": "Implementing",
  "size": "XS",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "An attended task's stop takes the file it hands back from its box, so Tasks1 Result and List answer it handed once the person left the session, though a listing at its start had found it missing."
}
```

**Why this size.** One take in `TaskControl`'s stop of a running task, one test.

Filed 2026-11-12 under ST314 from porelo-frontend F492 run 24, step 3 (porelo d71ecea04). pi wrote a valid
`idea.json` into the task's result box during an attended discussion, and the task stood stopped and kept after the
person left, as [B634](README.md#b634) means. Yet Tasks1 Result and the List row answered it `missing`. The results
directory held a `taken.json` written at the task's start and nothing later.

How it is today, read in the code: a prompt run takes the file at its run's end (`TaskResult.take`). A listing takes
it once (`takeOnce`) for a task not running, and a task listed in the second before its container ran is "not
running", so `missing` is kept then. `takeOnce` never looks again, and an attended session has no run's end that
would.

**What must be true.** When a running task is stopped, its file is taken again unless one was handed back already, so
Result and List answer what the agent wrote. A file once handed stays as handed.
