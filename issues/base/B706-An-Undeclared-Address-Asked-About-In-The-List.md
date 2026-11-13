# B706 — An Undeclared Address Asked About In The List

```json
{
  "$schema": "issue-1.json",
  "id": "B706",
  "title": "An Undeclared Address Asked About In The List",
  "state": "Review",
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

## Built (2026-11-13)

- Cause, reproduced at the command line with `porelo shield watch --events` on a session bus without a notification
  service (`dbus-run-session`): `DesktopPrompt.ask` threw at once, the `waiting` file was removed in the same
  moment, nothing was decided or recorded, and the question's retries were settled for the hub, so they were never
  put again. A `--deny-all` control on the same events decided at once. With no session bus at all, the watcher did
  not start. A test account has a session bus but no notification service, which is why the VM run saw `waitingFor`
  "" and no question line.
- `ClearanceHub.holdFor` keeps a question the prompt could not put open for a client's `Verdict` for the prompt's
  timeout. `onAsking` tells the start and the end of the whole question, the prompt and that hold together, and the
  shield watch writes and removes `waiting` there. A client's verdict is taken whole under the lock the hold waits on,
  so the stand-in deny is never read as its answer.
- `ClearanceService` keeps the questions put and not yet decided and tells them to a subscriber that comes later, so
  the daemon's `Prompts`, subscribed only when a list asks, still carries a question put before it.
- `porelo shield watch` without a session bus starts anyway, with every question left to a client. It says once that
  the desktop cannot ask and how long each question waits.
- The command line again, with this build: `waiting` holds `93.184.215.14:80` for the whole hold; a `Subscribe` 4 s
  after the question gets it with its key and deadline; a client's deny ends it, `waiting` goes, and the journal
  records `source: client`.
- Red first: `ClearanceHubTest.aQuestionTheDesktopCannotShowWaitsForAClientAndSaysItIsAsking` and
  `aQuestionNobodyCanShowRunsOutAfterItsHold` threw the prompt's exception; `ClearanceServiceTest
  .aSubscriberThatComesLaterIsStillToldTheQuestionsStillOpen` got no event.
- `doc/how-it-works.md` and `doc/getting-started.md` no longer say `prompt` needs a desktop.
- No VM run. The acceptance's 10 s on the VM stays open for the next interface story run.
