# B761 — A Run's Time Limit Outlasts A Daemon Restart

```json
{
  "$schema": "issue-1.json",
  "id": "B761",
  "title": "A Run's Time Limit Outlasts A Daemon Restart",
  "state": "Review",
  "priority": "High",
  "size": "M",
  "rank": 8,
  "blockedBy": [],
  "covers": "An unattended run's `minutes` is kept as a deadline in its task's state, and porelod, on its start and while it runs, stops a run whose deadline passed and that no process is waiting on, as the run itself would have",
  "openQuestions": 0
}
```

**Severity:** high - an unattended agent started through porelod runs on without its limit once porelod restarts
during the run, spending the provider's money and holding its task until somebody notices; nothing leaks.

**Why this size.** M: a record change in `RunLimit`, a background pass in the daemon, the launch writing and closing
the record, and tests over a fake clock and runner. Estimate: 63 minutes (Core's median for M).

**What must be true.** A run started with `minutes` is stopped when they pass, also when porelod restarted (or the
process that started the run died) in between; List and Watch then say `agentEnded.limit: true`, as for a run its own
wait ended.

## Plan

- Today the limit is only the wait in the starting process: `TaskLaunch` (the run step, around
  `RunLimit.started(...)`) calls `runner.runAgent(..., Duration.ofMinutes(request.minutes()))`, and only its
  `RunTimedOut` leads to `endAtLimit`. Tasks1 Start runs the same launch inside porelod, so a daemon restart ends the
  wait, and nothing else knows the run had a limit: `run-limit.json` holds only `minutes`.
- `RunLimit.started(state, minutes, now)` writes `until` (start + minutes, ISO 8601) beside `minutes`;
  `RunLimit.closed(state)` marks the run's end (written where `records.runEnded` is, whatever the exit), and
  `RunLimit.due(state, now)` answers a deadline that passed on a run not closed. An old record without `until` is no
  deadline.
- A `RunLimitWatch` in `app-task`, started by `PoreloDaemon` as a `BackgroundPass` (platform thread, as every
  long-lived pass), reads each running task's state once a minute and once at its start: for a due run whose agent
  is still running and whose starting process no longer holds the task's run (no live waiter recorded by pid and
  start time beside `until`), it calls the same `TaskLaunch.endAtLimit` the wait calls, which stops through
  `TaskControl.stopRunning` and writes `ended-by-limit.json`. A run whose waiter is alive is left to it, so a limit
  is never ended twice.
- The contract text of Start's `minutes` in `15-task.varlink` adds that the limit holds across a restart of porelod;
  the interface itself does not change.
- Red first: `RunLimitWatchTest` - a state with `until` in the past, no close, no live waiter and a running container
  in the fake runner: the pass stops the task and writes the limit's end. Red today, since nothing reads the record.
  Beside it: a live waiter, a closed run and a record without `until` stop nothing.

## Built

- Measured first, on this machine's podman: a process started by `podman exec` keeps running when the `podman exec`
  client is killed (SIGKILL). An agent therefore outlives a restart of porelod, and nothing else ends it.
- `RunLimit.started` writes `until` (start plus minutes) beside `minutes`, and the waiting process (`waiterPid`, and
  `waiterStarted` when the system says so). `RunLimit.closed` marks the run's end. `RunLimit.due` answers the minutes of a run whose `until` passed, that is not
  closed, and whose waiter is gone. A record without `until` is never due. A waiter
  is alive only when its pid runs and, where both say so, started at the recorded time, so a pid used again is not
  taken for it.
- `RunLimitWatch` (app-task), started by `PoreloDaemon` beside the room sweep, looks at its start and once a minute.
  For each running task whose run is due, it calls `TaskLaunch.endAtLimit`, the same end the wait uses, and says each
  one in porelod's log.
- After the agy reading (gemini-4-argon-high, four faults, all taken):
  - Only a stop that went through closes a run: `TaskControl` closes it as it stops a running task, whoever stops
    it, and `endAtLimit` answers whether the stop went through. The watch no longer closes first: a stop that
    failed is tried at the next pass and said once. `due` no longer skips a run ended by its limit, since the end is
    recorded before the stop that may not go through.
  - A task brought back (`TaskControl` resume) closes its last run's limit, and so does any stop of a running task:
    stopped before its deadline and brought back after it, it was ended by a run it no longer had.
  - `TaskLaunch` settles the limit in a `finally` around the run: closed however it ended, an unchecked exception
    before the exec included, but left open where the agent may still run: a wait that was interrupted, and a stop
    at the limit that did not go through.
  - The waiter is a process, and porelod outlives each run it waited on. This process keeps the runs it still waits
    on in memory, so a run it let go is due at once in the same daemon, not after its restart.
  - `closed` writes only into a record that is there: a task removed meanwhile is not given its state directory
    back, which refused every later start of its name.
  - Left: a new run that starts in the same moment the watch judges the old one due may be stopped by it. That needs
    an orphaned, overdue run and a new one started on its running container within the pass.
- Start's `minutes` in `15-task.varlink` says the limit holds across a restart of porelod. One changelog entry.
- Not told apart: an agent that ended on its own while no process waited, in a container still running. Its run is
  ended at its deadline and read as ended by its limit, since nothing recorded its own end.
- Red first: `RunLimitWatchTest.aRunPastItsDeadlineThatNothingWaitsOnIsEndedAtItsLimit` (red), then green. Beside it
  as guards: a live waiter, before the deadline, a closed run, a record without `until`, and the waiter's liveness.
- The reading's guards: a stop that fails is tried again and said once, a run this process let go is due, closing a
  removed task's run makes no state, the waiter passed on is the one recorded, `endAtLimit`'s verdict and its close,
  and a task brought back closes its last run's limit. The last two are red without the close in `TaskControl`.
- Green, targeted: `RunLimitWatchTest` 9, `TaskRunLimitTest` 9, `TaskDoneTest` 20, `TaskRunCommandTest` 310, the
  daemon tests (360 in all), and the wire, daemon and app tests named for
  the run limit, the task list, the agent's end, the daemon and the interfaces (48, 330, 177), 0 skipped.
- The VM acceptance, on ubuntu26.04 as `core` with porelo 0.5.1~snapshot.761 built from this branch: the new scenario
  "a run's time limit ends it though the daemon that waited on it was lost" (`task-unattended.feature`) is green
  (111 s). A run started through the scenario's daemon with `minutes` 1 and the stub's new prompt `stub sleeps 600`
  keeps its deadline; the daemon is killed and systemd starts it again; the agent's `sleep 600` is still running in
  the container. Within the next pass the task is `Exited`, `ended-by-limit.json` is written, and `porelo task status`
  says "its time limit of 1 minutes passed". The stub's prompt is one line in `agents/stub`'s `stub.yaml` (its 22 tests
  green), with a changelog entry.

## Acceptance

- `RunLimitWatchTest` seen red, then green; `TaskRunCommandTest` still green for a limit the wait ends.
- On the ubuntu VM in `core`: a stub run with `minutes` 1 started through porelod, porelod restarted during it; the
  task is stopped within two minutes of its start and `porelo task list` says the limit ended it.

## To be checked

- Answered: measured on this machine before the pass was built (an exec'd process survives the SIGKILL of its client),
  and on the VM in the scenario. Was: whether the agent's process in the container survives a porelod restart at all
  (the `podman exec` client dies with the daemon's cgroup); measured on the VM before the pass is built. If it does not,
  the run ends anyway and the pass only writes the limit's end and stops the task.
