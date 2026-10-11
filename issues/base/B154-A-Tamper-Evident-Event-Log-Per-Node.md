# B154 — A Tamper-Evident Event Log Per Node

**Status:** Estimated.
**Size:** L - a new record written from many commands, its anchor in the user's journal, an optional copy sent off the
machine, and a log that `sokar clear` leaves.

**What must be true.** Every security-relevant event of a node - approve, reject, clearance, hand-in, runtime extensions, and every change of permissions (a clearance at run time, `--credential`, a change of `project.yml`) - is written to a log that shows when it was changed afterwards.

## Why

Today these are in separate journals that can be edited without a trace; regulation asks for event logs.

**Guideline points it answers:** Swisscom 3.3.2 (EU AI Act Art. 12); Lumenalta step 2. From a review of security guidelines, 2026-10-09.

## The shape

One append-only, hash-chained log per account, anchored in the user's systemd journal, with an optional copy sent off
the machine; read by a command and the daemon. It outlives `sokar clear` of the account.

## Plan

What is there today, all owned by the account and editable without a trace:

- per task, under `$XDG_RUNTIME_DIR` and gone at a reboot: `gate.log`, `vault.log`, `relay.log`, `clearance.log`,
  `workspace.log`, `events.jsonl` (`TaskPaths.containerState`);
- per account, under `$XDG_STATE_HOME/sokar`: `failures.log`, `stops.log` (`SokarPaths`), the clearance journals
  (`TaskPaths.clearanceJournal`), the hand-in record (`HandIns`);
- the message record (`apps/app-messaging/.../MessageRecord.java`), the only hash chain today: each line carries the
  hash of the one before, `sokar talk verify` checks it. [B121](README.md#b121) removes it once this log exists.
- An approve or reject is recorded nowhere but in the gate's own refs and the check signal (`GateCheckCommand`).

Design: an account's own file cannot be made tamper-proof against the account. So the log is a hash chain in
`$XDG_STATE_HOME/sokar/events.jsonl`, and each line's hash also goes to the user's systemd journal, whose files the
account can read but not rewrite. A changed or removed line is then a line whose hash the journal does not hold. Where
the account-wide settings file (`config.yml`, which [B153](README.md#b153) adds; whichever is built first brings its
reader) names a destination, each line is also sent off the machine, so the record does not hang on this machine
alone; without one, nothing leaves. The log is the account's history, not
a project's, so `sokar clear` of the account leaves it and says so.

Steps:

1. Red first, in `apps/app-model`: an `EventLog` appended three events, one line edited; `verify` names that line.
   Then: a line removed together with every later one is named too, against the journal's hashes.
2. `EventLog` (`app-model`): `append(kind, task, who, detail)` under a file lock, `previous` and `hash` as
   `MessageRecord` writes them (moved here, not copied), the hash sent to the journal with `SOKAR_EVENT_HASH` as a
   field through the native journal socket; `verify()` reads both.
3. The writers, each one line: `GateApproveCommand` and `GateRejectCommand` (and the daemon's same calls),
   `ClearanceWiring`/`RunningClearance` for a clearance at run time, `HandIns` for give and take back, the start's
   `--credential` in `TaskLaunch`, a reconcile that applies a new `project.yml` (`Reconcile`), a runtime extension
   where it is granted, `task stop --all` beside `stops.log`.
4. `sokar events [--since] [--verify]` (app) and the daemon's `Events()` and `VerifyEvents()` in a new
   `55-events.varlink`; `InterfaceDescriptionTest` holds them.
5. The copy off the machine: `event_log: {copy: <destination>}` in `config.yml`, absent by default; each appended
   line is sent there as well, and a send that fails is named by `doctor`, never blocks the event. `doc/settings.md`
   gives the key.
6. `sokar clear` of the account (`Clearing`, `apps/app-project`) leaves `events.jsonl`, and its listing says that the
   event log stays. Red: a clearing test that finds the log, with its chain intact, after an account is cleared.
7. [B121](README.md#b121) then drops the chain from `MessageRecord` and `talk verify`.
8. `doc/security.md` gets a section: what is logged, where, what the journal anchor and the copy off the machine do
   and do not prove, and that a clearing of the account keeps the log.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- A line edited, or removed with every later one, is named by `sokar events --verify` against the journal.
- With a destination set, each event reaches it; without one, nothing leaves the machine.
- After `sokar clear` of the account, `events.jsonl` is still there and verifies.
- The documentation page that describes the area says it.

## To be checked

- It replaces what [B121](README.md#b121) removes from the message record; near [B26](README.md#b26), whose machine-wide history may be the same file
  (Agent Core's).
- How the copy is sent off the machine - syslog over TLS, or an https endpoint - and whether a send that failed is
  retried from the log's position (Agent Core's).
