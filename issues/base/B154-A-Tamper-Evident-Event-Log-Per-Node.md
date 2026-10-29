# B154 — A Tamper-Evident Event Log Per Node

**Status:** Implementing; part 1 built, part 2 (step 5, the copy sent off the machine) after its *To be checked*.
**Size:** L - a new record written from many commands, its anchor in the user's journal, an optional copy sent off the
machine, and a log that `porelo clear` leaves.

**What must be true.** Every security-relevant event of a node - approve, reject, clearance, hand-in, runtime extensions, and every change of permissions (a clearance at run time, `--credential`, a change of `project.yml`) - is written to a log that shows when it was changed afterwards.

## Why

Today these are in separate journals that can be edited without a trace; regulation asks for event logs.

**Guideline points it answers:** Swisscom 3.3.2 (EU AI Act Art. 12); Lumenalta step 2. From a review of security guidelines, 2026-10-09.

## The shape

One append-only, hash-chained log per account, anchored in the user's systemd journal, with an optional copy sent off
the machine; read by a command and the daemon. It outlives `porelo clear` of the account.

## Plan

What is there today, all owned by the account and editable without a trace:

- per task, under `$XDG_RUNTIME_DIR` and gone at a reboot: `gate.log`, `vault.log`, `relay.log`, `clearance.log`,
  `workspace.log`, `events.jsonl` (`TaskPaths.containerState`);
- per account, under `$XDG_STATE_HOME/porelo`: `failures.log`, `stops.log` (`PoreloPaths`), the clearance journals
  (`TaskPaths.clearanceJournal`), the hand-in record (`HandIns`);
- the message record (`apps/app-messaging/.../MessageRecord.java`), the only hash chain today: each line carries the
  hash of the one before, `porelo talk verify` checks it. [B121](README.md#b121) removes it once this log exists.
- An approve or reject is recorded nowhere but in the gate's own refs and the check signal (`GateCheckCommand`).

Design: an account's own file cannot be made tamper-proof against the account. So the log is a hash chain in
`$XDG_STATE_HOME/porelo/events.jsonl`, and each line's hash also goes to the user's systemd journal, whose files the
account can read but not rewrite. A changed or removed line is then a line whose hash the journal does not hold. Where
the account-wide settings file (`config.yml`, which [B153](README.md#b153) adds; whichever is built first brings its
reader) names a destination, each line is also sent off the machine, so the record does not hang on this machine
alone; without one, nothing leaves. The log is the account's history, not
a project's, so `porelo clear` of the account leaves it and says so.

Steps:

1. Red first, in `apps/app-model`: an `EventLog` appended three events, one line edited; `verify` names that line.
   Then: a line removed together with every later one is named too, against the journal's hashes.
2. `EventLog` (`app-model`): `append(kind, task, who, detail)` under a file lock, `previous` and `hash` as
   `MessageRecord` writes them (moved here, not copied), the hash sent to the journal with `PORELO_EVENT_HASH` as a
   field through the native journal socket; `verify()` reads both.
3. The writers, each one line: `GateApproveCommand` and `GateRejectCommand` (and the daemon's same calls),
   `ClearanceWiring`/`RunningClearance` for a clearance at run time, `HandIns` for give and take back, the start's
   `--credential` in `TaskLaunch`, a reconcile that applies a new `project.yml` (`Reconcile`), a runtime extension
   where it is granted, `task stop --all` beside `stops.log`.
4. `porelo events [--since] [--verify]` (app) and the daemon's `Events()` and `VerifyEvents()` in a new
   `55-events.varlink`; `InterfaceDescriptionTest` holds them.
5. The copy off the machine: `event_log: {copy: <destination>}` in `config.yml`, absent by default; each appended
   line is sent there as well, and a send that fails is named by `doctor`, never blocks the event. `doc/settings.md`
   gives the key.
6. `porelo clear` of the account (`Clearing`, `apps/app-project`) leaves `events.jsonl`, and its listing says that the
   event log stays. Red: a clearing test that finds the log, with its chain intact, after an account is cleared.
7. [B121](README.md#b121) then drops the chain from `MessageRecord` and `talk verify`.
8. `doc/security.md` gets a section: what is logged, where, what the journal anchor and the copy off the machine do
   and do not prove, and that a clearing of the account keeps the log.

**Part 1, built, where it departs from the steps above:**

- `EventLog` and `HashChain` sit in `app-base` beside `PoreloContext`, so every module that writes an event reaches
  them; `MessageRecord` hashes with `HashChain` too, its material unchanged, so an existing record still verifies.
- The anchor is `logger --journald`, a line of fields on standard input, read back with `journalctl --user`; each
  entry carries `PORELO_EVENT_LOG`, the log's path, so a test's log or a second state directory never counts as this
  one's lost lines. `verify` names the first broken line, each line the journal lacks after the first it holds, and
  each hash the journal holds that the log lost; lines before the journal's first are counted apart, vouched for by
  the chain alone.
- The writers: `gate approve`, `porelo approve` and the daemon's `Approve`; `gate reject` and the daemon's `Reject`;
  each decision the clearance watcher records, and a change of a running task's clearance mode; `HandIns` for give
  and take back; the credentials a start gives beyond the agent's own (where `GrantRecord` records them); a
  reconcile that applies a new commit; `task stop --all`. "A runtime extension" is a credential granted beyond the
  agent's own: no other extension of a running task exists today.
- `porelo events [--since] [--verify]` and the daemon's `Events` and `VerifyEvents` in `55-events.varlink`.
- `porelo clear` leaves the log, and says so.
- A reading by a second agent changed: append and journal send under one lock, `verify` under it too; only lines ahead
  of the first the journal holds and older than its oldest entry of this log count as before it, so a line appended
  later with an old date is named, and a log the journal holds nothing of does not hold; a line without a hash and a
  kind is listed as `unreadable`, its other fields empty; a reconcile records once its commit is followed or written,
  and a follow refused for its key records what the pinned key applied before; a start records a credential only once
  the task runs and only one the broker gave a token for; `task stop --all` records only when something was stopped.
  Not changed: entries another process of the account adds to the journal under the log's identifier can only make
  `verify` fail, never hold.

**What a private access file changes, nothing
reworked yet:** a project may name a private access file ([B310](README.md#b310)); an applied commit of it
is a `project-applied` line too, naming the access repository. It is built with that issue.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- A line edited, or removed with every later one, is named by `porelo events --verify` against the journal.
- With a destination set, each event reaches it; without one, nothing leaves the machine.
- After `porelo clear` of the account, `events.jsonl` is still there and verifies.
- The documentation page that describes the area says it.

## To be checked

- It replaces what [B121](README.md#b121) removes from the message record; near [B26](README.md#b26), whose machine-wide history may be the same file
  (Agent Core's).
- How the copy is sent off the machine - syslog over TLS, or an https endpoint - and whether a send that failed is
  retried from the log's position (Agent Core's).
- Weigh an S3 Object Lock bucket written with a key that cannot delete, beside syslog over TLS and
  an HTTPS endpoint for the copy sent off the machine.
