# B154 — A Tamper-Evident Event Log Per Node

**Status:** Analyzed.
**Size:** L - a design to decide first (where the chain's anchor lives), then a new record written from many commands.

**What must be true.** Every security-relevant event of a node - approve, reject, clearance, hand-in, runtime extensions, and every change of permissions (a clearance at run time, `--credential`, a change of `project.yml`) - is written to a log that shows when it was changed afterwards.

## Why

Today these are in separate journals that can be edited without a trace; regulation asks for event logs.

**Guideline points it answers:** Swisscom 3.3.2 (EU AI Act Art. 12); Lumenalta step 2. From a review of security guidelines, 2026-10-09.

## The shape

One append-only log per account, hash-chained or kept where the account cannot rewrite it; read by a command and the daemon.

## Plan

What is there today, all owned by the account and editable without a trace:

- per task, under `$XDG_RUNTIME_DIR` and gone at a reboot: `gate.log`, `vault.log`, `relay.log`, `clearance.log`,
  `workspace.log`, `events.jsonl` (`TaskPaths.containerState`);
- per account, under `$XDG_STATE_HOME/sokar`: `failures.log`, `stops.log` (`SokarPaths`), the clearance journals
  (`TaskPaths.clearanceJournal`), the hand-in record (`HandIns`);
- the message record (`apps/app-messaging/.../MessageRecord.java`), the only hash chain today: each line carries the
  hash of the one before, `sokar talk verify` checks it. [B121](README.md#b121) removes it once this log exists.
- An approve or reject is recorded nowhere but in the gate's own refs and the check signal (`GateCheckCommand`).

Design (to decide first, see below): an account's own file cannot be made tamper-proof against the account. So the
log is a hash chain in `$XDG_STATE_HOME/sokar/events.jsonl`, and each line's hash also goes to the user's systemd
journal, whose files the account can read but not rewrite. A changed or removed line is then a line whose hash the
journal does not hold.

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
5. [B121](README.md#b121) then drops the chain from `MessageRecord` and `talk verify`.
6. `doc/security.md` gets a section: what is logged, where, and what the journal anchor does and does not prove.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Where the chain's anchor is kept: the user's systemd journal as planned, a key in the vault that signs each line
  (the account can open the vault, so it proves less), or a copy sent off the machine (the operator's).
- Whether the log outlives `sokar clear` of the account (the operator's).
- It replaces what [B121](README.md#b121) removes from the message record; near [B26](README.md#b26), whose machine-wide history may be the same file
  (Agent Core's).
