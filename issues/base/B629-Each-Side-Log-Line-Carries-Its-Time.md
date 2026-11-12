# B629 — Each Side Log Line Carries Its Time

```json
{
  "$schema": "issue-1.json",
  "id": "B629",
  "title": "Each Side Log Line Carries Its Time",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 32,
  "blockedBy": [],
  "covers": "Each line a task's side process writes to gate.log, vault.log, relay.log or clearance.log starts with the time it was written, and Tasks1 `Tail` answers that time beside each line, null for a line without one, for the window's one timeline of a piece of work."
}
```

**Why this size.** One stamping stream installed in Porelo's own entry point for the side processes, the
side processes' launches asking for it, one field in `Tail` and its contract, tests for the stream and for
`Tail` over stamped, unstamped and mixed logs. Several modules, each small.

Filed 2026-11-11 at the coordinator's request, for Agent Frontend's F111 (all of a piece's logs in one
timeline), whose plan reads a time from each line and asks Core for one where a source has none.

**What must be true.** A client that follows a task's side log through `Tail` gets each line's time, with no
format of its own to guess. A line written before this change, or by something other than Porelo, is
answered with no time, and the client keeps it after the line before it.

## Analysis

- **Where the lines come from.** gate.log, vault.log, relay.log and clearance.log are the plain stdout and
  stderr (`redirectErrorStream(true)`) of side processes the start launches. These are `porelo gate serve`
  (`GateWiring`), the vault broker and `porelo vault relay` (`CredentialWiring`), and the clearance watcher
  (`ClearanceWiring`, `RunningClearance`). Each runs in its own `systemd-run --user --scope`, and its output
  goes straight to the file in the task's state directory. No Porelo process sits between the side process
  and its file. These processes outlive the start that launched them, and porelod is not running for each
  of them.
- **What already has a time.** `events.jsonl` and `task.log` carry one per line, as JSON fields. The four side logs
  print free text through `println` with no time.

## Options

1. **The side processes stamp their own lines (recommended).** All four are Porelo's own native binary,
   entered through `PoreloCli.main`.
   - When the launch sets `PORELO_LOG_TIMES=1`, main replaces `System.out` and `System.err` with one
     stamping stream, shared by both and locked per line. It puts an ISO-8601 instant with milliseconds and
     a blank before each line, at the line's first byte.
   - The wirings set the variable on their `ProcessBuilder`. A human reading the raw file sees the time too.
   - It costs no process and no daemon. The time is when the line was written, not when someone read it.
   - It misses what does not pass through `System.out`/`System.err`: a child the side process starts that
     inherits the descriptor (the gate's git), and a native crash's own output. Those lines get no time.
2. **A stamping pipe per side process.** Start `sh -c '<command> | porelo log stamp <file>'` in the same
   scope.
   - It catches the children's lines too.
   - It adds a process for each of the four per task, and each needs its own lifetime: a stamper that dies
     sends SIGPIPE to the gate or the vault, which then fail. Not recommended.
3. **porelod stamps on arrival.**
   - porelod watches each running task's side logs and keeps a side file of byte offset to the time a line
     appeared, and `Tail` answers from it.
   - The times are only as good as the watch; lines written while porelod is down have none.
   - It adds a watch per log and a second file per log. Not recommended.

## Plan (option 1)

- A `StampedLines` print stream in `core`. It is line-buffered and thread-safe, and stamps at the first byte
  of each line, so a partial line written in two calls carries one stamp. It is installed in
  `PoreloCli.main` when `PORELO_LOG_TIMES` is `1`.
- `GateWiring`, `CredentialWiring` (vault, relay) and `ClearanceWiring`/`RunningClearance` set
  `PORELO_LOG_TIMES=1` on the side process's environment.
- Tasks1 `Tail` gains `times: []?string`, one per line in `lines`, read from the line's leading instant.
  - The line is answered without the stamp, so a formatter and an older client see what they saw.
  - A line with no stamp, including a log written before this change, is answered with null.
  - `formatted` keeps the time of each line the agent shows.
- The contract (`15-task.varlink`) and `doc/` name the field. A changelog entry under *Added*.

## Acceptance

- `StampedLinesTest`, red first: two threads writing interleaved partial lines get one stamp per line,
  never one inside a line; a line written in two calls carries one stamp.
- A `Tail` test over a log with stamped, unstamped and older lines answers each line's time or null, and
  lines without their stamps.
- On a VM: a task's gate.log lines carry times, and the window's F111 timeline orders gate and vault lines
  among the agent's.
