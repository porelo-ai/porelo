# B277 — A Task Says When Its Agent Last Wrote And What Waits For It

**Status:** Backlog, for sprint 2d; its fields agreed with porelo-frontend.
**Size:** S - two fields in `Tasks1`'s `Task`, set in `TaskInventory.describe`, kept out of `Watch`'s change test
where they would change it every second, their tests and the contract's text.

**What must be true.** A running task's view can say what its agent does - working, with how long ago it last
wrote; quiet; waiting for a person; ended - and for an ended unattended run how many messages wait for its next run,
from what the daemon sends, without guessing from a silent log.

## Why

From the operator's rented-host test of sprint 2c (finding 14): an unattended task's view showed the start's log up
to `running claude (up to 30 minutes)` and then nothing; a waiting agent looked like a hanging start. Today `Task`
has `activity` (`WORKING` when `task.log` changed within 60 seconds, `IDLE` after, `WAITING` for a recorded question,
`ENDED`, `DEAD`, `UNKNOWN`) and `phase`, but neither when the agent last wrote nor what waits in its inbox; and an
unattended run is never woken, so a message that arrives after it ended waits for a start the person must make.

## Plan

Read in `TaskInventory` (`activityOf`, `describe`, `Task.asMap`), `AgentEnding`, `AgentWake`, `Mailbox`,
`MessagingPaths`, `TaskMethods.watch` and `15-task.varlink`.

1. **`Task.lastWrote: string`** - the instant `task.log` was last written (its modification time, which
   `activityOf` already reads), `""` without a log. The age is the interface's to render; the daemon sends the
   instant. **`Watch` does not count it as a change** (as it does not count age today), or every line the agent
   writes would resend the whole list; `activity` crossing `WORKING`/`IDLE` still does.
2. **`Task.inboxWaiting: int`** - the messages in the task's `inbox/new` on the host
   (`~/.local/state/porelo/mail/<container>/box/inbox/new`, its `.json` files, as `AgentWake` lists them), read
   without entering the container, for **every listed task, running, ended or stopped** - the mailbox stays until
   the task is removed, which is the case "start again, N messages wait" needs; `0` when there is no mailbox. It changes `Watch` when it changes.
3. **The last line is not a field**: the interface asks `Tail(last: 1)` for it when it shows it, so a busy log does not
   travel in every `Watch` reply; the contract's text for `lastWrote` says so.
4. **"Waiting for a message"** is not something the daemon can tell from an unattended run's silence, and is not
   sent as such: the contract's text says that `IDLE` means quiet, with `inboxWaiting` and the prompt the
   interface's to read.
5. **`doc/`**: the task status page names both fields; `doc/messages.md`'s line on ended unattended runs names
   `inboxWaiting`.

**Red first:** `TaskActivityTest.anEndedRunCountsTheMessagesWaitingForItsNextRun` (two files in `inbox/new`, one in
`inbox/cur`: `inboxWaiting` 2; no field today) and `WatchChangeTest.aLogWrittenAgainDoesNotChangeTheList`
(`lastWrote` moves, nothing else: no change) beside `...aNewMessageChangesTheList`.

## Acceptance

- `lastWrote` is `task.log`'s write time, `""` without one; `inboxWaiting` counts only `inbox/new`, for an ended
  and a stopped task too.
- `Watch` sends a new list when `inboxWaiting` or `activity` change, never for `lastWrote` alone, held by a test.
- `InterfaceDescriptionTest` holds both fields; agreed with porelo-frontend before building, which renders "working,
  last wrote 2 min ago", "quiet", "ended, 2 messages wait - start again".
