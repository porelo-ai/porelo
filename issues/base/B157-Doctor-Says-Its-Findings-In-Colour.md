# B157 — Doctor Says Its Findings In Colour

**Status:** Review.
**Size:** S - one command class and its test, a paragraph of `doc/commands.md`.

**What must be true.** `sokar doctor` colours what it found: a failure red, a warning yellow, what is fine green. The
words stay as they are, so colour is never the only signal - for a reader who cannot tell the colours apart, a screen
reader, or a log.

## Why

A machine that cannot run a task says so in one line among twenty, `MISSING - ...`, in the same plain text as every
line that is fine. Found in the joint test, 2026-10-10: the failing line had to be searched for.

## The shape

- Red: `MISSING`. Yellow: `DEGRADED`, `UNKNOWN`, a copy not used or a description not taken, and the note that a command
  failed unexpectedly. Green: `OK`.
- Only the state word and its line's name are coloured; what follows is plain.
- Colour only when standard output is a terminal: piped or redirected output carries no escape codes.
- `NO_COLOR` set to anything turns colour off (no-color.org). `--color=auto|always|never`, `auto` the default;
  `always` and `never` win over both the terminal and `NO_COLOR`, since they are what a person asked for on this
  command.

## Plan

Built and on main; only the operator's test is left.

- `DoctorCommand` (`apps/app/src/main/java/org/fuin/sokar/app/DoctorCommand.java`): `--color=auto|always|never`,
  `colours(mode, terminal, NO_COLOR)` decides once per run, `painted(text, state, colour)` colours the probe's name
  and its state word, the notes and the failures line in yellow; `--json` carries no colour.
- `DoctorColourTest` (seen red first): a terminal stood in gets the codes, a pipe and `NO_COLOR` none, `always` and
  `never` win, the words are the same with colour or without.
- `doc/commands.md` says it under `sokar doctor`.

## Acceptance

- Seen red first: a terminal stood in gets the codes; a pipe and `NO_COLOR` get none; `--color=always` and
  `--color=never` do what they say.
- `doc/commands.md` says it.
