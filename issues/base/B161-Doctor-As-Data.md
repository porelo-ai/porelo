# B161 — Doctor As Data

**Status:** Review.
**Size:** S - one command class, its test and a paragraph of docs; the daemon already answered.

**What must be true.** `porelo doctor --json` says every finding as data - the check, its state, the words and the
remedy - from the same checks the text runs, for a client to read; the exit code is the text's.

## Why

The interface is to show what `porelo doctor` finds on a machine it adds, in its words and colours ([B157](README.md#b157)), so the checks
live in one place. Reading the text would tie a client to its layout and its colours.

## The shape

- One JSON object on standard output, nothing else on it:
  `{"version": "...", "findings": [{"check": "hooks", "state": "OK|DEGRADED|MISSING|UNKNOWN", "detail": "...",
  "remedy": "..." | null}], "notes": [{"kind": "not-used|not-taken|failures", "detail": "..."}]}`.
- The same probes, in the same order, as the text; the text and the JSON come from one list, so they cannot disagree.
- The exit code as without `--json`: 69 when anything is `MISSING`, 0 otherwise.
- No colour and no escape codes in it, whatever `--color` says.

## Built so far

- `porelo doctor --json`: one object with `version`, `findings` (`check`, `state`, `detail`, `remedy`), `notes`
  (`kind`, `file`, `detail`), `paths` and `process`, from the same probe list and notes as the text; no colour; the
  text's exit code (`DoctorJsonTest`, seen red first).

## Plan

Built and on main, nothing left but the operator's test: `DoctorCommand --json` (`apps/app/.../DoctorCommand.java`)
renders the list `probesFor` builds, which the text and the daemon's `Doctor` render too; `DoctorJsonTest` was seen
red first; `doc/commands.md` gives the shape under `porelo doctor`.

## Acceptance

- Seen to fail first: `--json` refused as an unknown option today; afterwards every probe of the text is in it, with
  its state and remedy, and a machine with a missing part is `MISSING` and exits 69.
- `doc/commands.md` gives the shape.

## Decided

- The daemon already answers it: `Doctor` returns the same probes (`name`, `state`, `detail`, `action`) and
  `ready`, from `DoctorCommand.probesFor`. A connected client reads that; `--json` is for one that runs the command.
