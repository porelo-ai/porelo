# [B500](README.md#b500) — Tighten Over The Socket

**Status:** Implementing.
**Size:** S - one method in the machine's contract part (`50-machine`), `MachineMethods`, and its test.

**What must be true.** A client of the daemon asks what `porelo tighten` would change, and has it done, as one method
with a dry run: each path it would tighten or tightened, from what to what; what it cannot change is said, never
skipped in silence.

## Plan

- `Tighten(dryRun)` in `50-machine.varlink`, answered by the code `TightenCommand` runs, which the command and the
  method only render.
- Red first: a unit test that a dry run changes no mode and the real run changes exactly what the dry run listed.

## Acceptance

- Dry run and run agree; the CLI's output is unchanged.
