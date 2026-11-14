# B761 — A Run's Time Limit Outlasts A Daemon Restart

```json
{
  "$schema": "issue-1.json",
  "id": "B761",
  "title": "A Run's Time Limit Outlasts A Daemon Restart",
  "state": "Implementing",
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

## Acceptance

- `RunLimitWatchTest` seen red, then green; `TaskRunCommandTest` still green for a limit the wait ends.
- On the ubuntu VM in `core`: a stub run with `minutes` 1 started through porelod, porelod restarted during it; the
  task is stopped within two minutes of its start and `porelo task list` says the limit ended it.

## To be checked

- Whether the agent's process in the container survives a porelod restart at all (the `podman exec` client dies
  with the daemon's cgroup); measured on the VM before the pass is built. If it does not, the run ends anyway and the
  pass only writes the limit's end and stops the task.
