# B706 — An Undeclared Address Asked About In The List

```json
{
  "$schema": "issue-1.json",
  "id": "B706",
  "title": "An Undeclared Address Asked About In The List",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 60,
  "blockedBy": [],
  "covers": "A task of a guarded project that reaches an address its project did not declare is listed waiting for that clearance question within seconds, and the watch names the question above its table.",
  "openQuestions": 0
}
```

**Why this size.** The cause is not read yet; one reproduction at the command line, then a fix in the clearance's
way to the task's row, which the activity test already models.

Filed 2026-11-13 from Frontend's run of an interface story on the test VM, with Core's packages from 40b17b120 in the
frontend account. A stub task of a guarded project reached `example.org`, which its project did not declare. For 45 s,
`porelo` listed the task with `waitingFor` "" and activity `unknown`, and the `task list --watch` showed no question
line above its table. The steps before it (typing into the task's terminal, the watch's rows) passed.

**What must be true.** The clearance question the hub holds for that address is in the task's row (`waitingFor`, activity
`waiting`) and in the watch, as `TaskActivityTest`'s clearance cases say.

## Plan

- Reproduce at the command line on the VM in a test account: a guarded project, a stub task, `curl example.org`
  inside it, then `task status --json` and the clearance journal. Read first whether the watcher was up: a start
  only warns when the clearance watcher does not come up ([B697](README.md#b697)), and with no watcher the hub may
  answer at once and leave nothing waiting.
- Fix where it breaks, red first in the test that matches the cause.

## Acceptance

- The reproduction lists the task waiting for the address within 10 s, and the watch names it; a test fails without
  the fix.
