# B155 — The Logs Checked And Documented

**Status:** Analyzed.
**Size:** M - one documentation page over every log, a test per process that holds a secret, file rights checked,
and `task.log` cut after a period.

**What must be true.** What broker, gate and journals write is documented field by field: no prompt contents, tokens or personal data, or redacted; where each log lies and who can read it.

## Why

Logs are sensitive data themselves; nobody has checked all of Sokar's at once.

**Guideline points it answers:** OWASP AI Agent Cheat Sheet §6/§8; NCSC (logs as sensitive data). From a review of security guidelines, 2026-10-09.

## The shape

A page listing each log; a test that the broker's and the gate's logs contain no credential; file rights as
[B50](README.md#b50) sets them. `task.log`, which carries the agent's words and so prompt contents, is cut after 30 days
by default, a period the account's settings may change.

## Plan

The logs, read in the code:

- per task, in `TaskPaths.containerState(container)`: `vault.log` (the broker's output, `CredentialWiring`),
  `relay.log` (`CredentialWiring`), `gate.log` (`GateWiring`, the gate's token in its environment), `clearance.log`
  (`ClearanceWiring`, `RunningClearance`), `workspace.log` (`WorkspaceSetup`), `task.log` (an unattended run's
  output, `TaskLaunch`, `TaskRunCommand` - the agent's own words, so prompt contents), `dnsmasq.log`, `events.jsonl`;
- per account, in `$XDG_STATE_HOME/sokar`: `failures.log`, `stops.log` (`SokarPaths`), the clearance journals, the
  hand-in record (`HandIns`), the message record (`MessageRecord`), the build records;
- the daemon's and the user services' output in the user journal.

Steps:

1. Red first, in `apps/app`: the broker (`TokenBroker` behind `vault serve`) serves a request with a known token and a
   known phantom token, and the gate (`GateServeCommand`) takes a push with its `SOKAR_GATE_TOKEN`; the test reads
   what each wrote to its output and finds neither secret, nor an `Authorization` header's value. Whatever it finds
   is fixed where it is written, by leaving it out, not by masking after the fact.
2. The same test asserts each log file is `rw-------` and its directory `rwx------`; where a writer does not set it,
   it is set where the file is made (`TaskHelpers` already does it for some). Where [B50](README.md#b50) widens or narrows a right,
   the test follows [B50](README.md#b50).
3. `task.log` holds what the agent said, by design, so it is kept for a period, not as long as the task:
   `task_log: {days: 30}` in the account-wide settings file (`config.yml`, which [B153](README.md#b153) adds; whichever
   is built first brings its reader). A `task.log` last written longer ago than that is removed, even while its task
   lives; `task logs` then says it was cut after the period, not that nothing wrote one. Red: a `TaskInventory` test
   with a `task.log` 31 days old finds it gone and the reason said; one 29 days old is kept. The page says what it
   holds and how long it is kept.
4. A page `doc/logs.md` (in `mkdocs.yml`): one row per log - who writes it, where it lies, how long it lives, who
   can read it, its fields, and what is never in it (tokens, passphrases, a credential's value). A test reads the
   page and the code's list of log names (`TaskState`'s list of state files) and fails on a log the page misses.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- A `task.log` older than 30 days, with no setting, is cut and `task logs` says why; with `task_log: {days: 60}` it
  is kept.
- The documentation page that describes the area says it.

## To be checked

- When the cut is made with nothing running in the background: at the task's next listing, start or read, or in a
  pass the daemon already makes (Agent Core's).
