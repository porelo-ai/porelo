# B231 — The Usage Records Kept Off The Machine

**Status:** Analyzed; blocked by [B148](README.md#b148).
**Size:** M - one command with two directions and their refusals, in one module, but a command line the host's
playbook in porelo-host runs, which is a contract between repositories.

**What must be true.** `porelo keep <dir>` copies the tasks' usage records into a directory as they stand, and
`porelo keep --restore <dir>` puts them back on a new machine before a task starts, so the history a project's
velocity is computed from outlives the machine it was written on. The copying to the project's
store and the schedule are the playbook's.

## Plan

Read in the code and in [B148](README.md#b148) on 2026-10-20.

1. **What is copied**: [B148](README.md#b148) writes one append-only record per task,
   `$XDG_STATE_HOME/porelo/usage/<container>.jsonl`, kept after the task's removal; nothing writes usage records
   today, hence the block. Only that directory is copied: no vault, no task state, no logs.
2. **`porelo keep <dir>`**: `<dir>/usage/` made `0700` if missing; each record copied up to its last complete line,
   since a line may be half written while a broker appends; written to a temporary name and moved into place with
   `ATOMIC_MOVE`, `0600`. A record already in `<dir>` that is longer than the machine's is left as it is. It prints one
   line per record and a total, and exits 0, or 70 naming what it could not read or write.
3. **`porelo keep --restore <dir>`**: refused with exit 69 while any task of the account runs, since a broker may be
   appending. For each record in `<dir>/usage/`: copied when the machine has none; when both have one, the longer is
   kept if the shorter is its prefix; two that differ before the shorter one ends are refused, naming the record,
   and nothing of it is changed. A restore never shortens a record.
4. **Where**: `KeepCommand` in `app`, beside the machine's own commands, in `PoreloCli`'s list; no daemon method, since
   only the playbook calls it on the machine. `doc/commands.md` gets its entry, and the contract - a command that writes its
   copy into a directory it is given, and one that puts it back - is stated there.

**Red first:** `KeepCommandTest`: a record whose last line is half written is kept without that line; a restore onto
a machine whose record is longer keeps the longer one; two records that diverge are refused and both stay; a restore
while a task runs is refused with 69.

## Acceptance

- `porelo keep <dir>` on a machine with records, then `porelo keep --restore <dir>` on a fresh account, gives
  byte-for-byte the same complete records.
- No restore shortens a record or writes while a task runs.
