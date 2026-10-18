# B204 — Start On A Running Task Goes In

**Status:** Implementing. Built with its tests seen red; green on the VM on `070fc2a9`: the whole check, and
acceptance 233, 5 skipped; to Review once pushed and published.
**Size:** S - one branch in `TaskLaunch` that hands over to the code `task attach` runs, a check of which options were
given, and tests for the three cases; no new state and no change to `attach`.

**What must be true.** `porelo task start` on a task that is running behaves like `task attach`, unless an option was
given that would only take effect when a task is created or brought up.

## Why

After a reboot a task's container comes back running, but the agent's session in it is gone. `task start` on it
then says the task "is already running" and points to `porelo task attach`. Only `attach` starts the agent again and
continues its conversation. A person who typed `start` has said they want to work in the task. Sending them to a second
command adds a step and nothing else.

Decided by the operator, 2026-10-18, as the open question left by continuing a conversation after a restart.

## The shape

- **At a terminal, with no option that only takes effect at creation or bring-up:** `start` goes in through the same
  code as `attach` (`TaskAttachCommand`, which resumes through `TaskResumeCommand.resume`). An agent whose session is
  gone is started again and continues its conversation.
- **With such an option:** `start` refuses and names each option that no longer takes effect, for example
  `porelo: <task> is already running; --prompt takes effect only after 'porelo task stop'`. Among them: `-r`,
  `--prompt`/`-P`, `--provider`, `--credential`, `--rm`, `--attach`, `--detach`, and options of their kind.
- **Without a terminal:** `start` says the task is running and ends with its own exit code, as today (65, `RUNNING`),
  so a script never hangs in a session.
- `task attach` stays as it is: the verb that never creates anything.

The running case is today's `summary.get().running()` branch in `TaskLaunch`, after the project and repository checks.
Whether a person is at a terminal is `Offer.atTerminal()`, as other offers of `start` already ask.

## Acceptance

- [ ] At a terminal, `task start <task>` on a running task with no session left starts the agent again and continues
  its conversation, the same as `task attach <task>`. Seen to fail: today's "already running" message.
- [ ] `task start <task> --prompt x` on a running task refuses with a non-zero exit code and names `--prompt`. Each
  option of creation or bring-up is named when given, all of them when several are given, and nothing is started.
- [ ] Without a terminal, `task start <task>` on a running task prints that it is running and exits 65, as today.
- [ ] `task attach` behaves as before; its tests are unchanged.
- [ ] The CLI reference and the help text of `start` say what `start` does on a running task.

## Built, 2026-10-18

- `TaskLaunch.startExisting(out, err, goingIn)` answers a running task with code 0 and `RUNNING` and says nothing of it
  when the caller goes in; the daemon's call is unchanged and still gets 65.
- `TaskRunCommand` goes in when a person is at a terminal and `--no-input` was not given, through
  `TaskAttachCommand.goInto`, the code `attach` itself now calls. Given any of `--agent`, `--provider`,
  `--credential`, `--credential-type`, `--token-hours`, `--shell`, `--attach`, `--rm`, `--prompt`, `--model`,
  `--max-turns`, `--minutes`, `--upstream`, `--no-gate`, `--clearance`, `--raw`, `--detach` or `--restarted`, it
  refuses with 64 and names each.
- `-r` naming the task's own repository is not counted: a different one is refused before, as today.
- `--dry-run` never reaches this: the launch says what it would do and asks the runtime nothing.
- Red first: `TaskRunCommandTest.aStartAtATerminalGoesIntoARunningTaskAsAttachDoes` and
  `aStartOnARunningTaskNamesEachOptionThatWouldNoLongerTakeEffect` failed with going in turned off, and
  `aScriptsStartOnARunningTaskSaysItRunsAndEndsAsBefore` passed there, as it should.

## To be checked

- Whether the daemon's Start over the socket should go in too: it has no terminal, so it keeps 65 and `RUNNING`.
