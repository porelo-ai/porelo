# B500 — Tighten Over The Socket

```json
{
  "$schema": "issue-1.json",
  "id": "B500",
  "title": "Tighten Over The Socket",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 68,
  "blockedBy": [],
  "covers": "`Tighten` with a dry run answers what `porelo tighten` changes, over the socket."
}
```

**Why this size.** one method in the machine's contract part (`50-machine`), `MachineMethods`, and its test.

**What must be true.** A client of the daemon asks what `porelo tighten` would change, and has it done, as one method
with a dry run: each path it would tighten or tightened, from what to what; what it cannot change is said, never
skipped in silence.

## Plan

- `Tighten(dryRun)` in `50-machine.varlink`, answered by `Tightening`, the code `TightenCommand` runs, which the
  command and the method only render: `found`, `changes` (path, `from`, `to` as `rw-r--r--`) and `left` (path and
  `LINK`, `REFUSED` or `GONE`); a directory that cannot be looked through is `Failed`.
- `LooseFiles` keeps the finding; changing a mode moved to `Tightening`.
- Red first: `TighteningTest`, that a dry run changes no mode and the real run changes exactly what the dry run
  listed; `PoreloDaemonTest` asks the same over the socket.
- A refusal the file system makes only when asked (a file another uid owns) a dry run cannot foresee; the run lists
  it in `left`.

## Acceptance

- Dry run and run agree; the CLI's output is unchanged.
