# B279 — A Windows Log's Line Ends As A Line

**Status:** Implementing.
**Size:** XS - one condition in `BuildStatus.terminalText`, its test and a sentence in `doc/commands.md`.

**What must be true.** `porelo build status` shows a job log written with Windows line ends (CRLF) as plain lines,
as it shows a Linux log, while a carriage return anywhere else is still shown escaped.

## Why

From the operator's test of `porelo build status` against GitHub: every line of `log: Build / Windows` ended in a
literal `\x0d`, because the terminal escaping turns each control character but newline and tab into `\xNN`, and a
Windows runner's log ends its lines with CR LF. The Linux log showed none.

## Plan

Read in `BuildStatus.terminalText` and `BuildStatusCommandTest.forgeTextCannotClearOrRewriteTheTerminal`.

1. **`BuildStatus.terminalText`:** a `\r` directly followed by `\n` is dropped; any other `\r` stays `\x0d`. The rest
   of the escaping is unchanged, so a lone CR can still not move the cursor back over a line.
2. **`--json` is unchanged**: it carries the log as the forge gave it.
3. **`doc/commands.md`**, the `build` section: one clause, that a carriage return before a newline is read as the line
   end.

**Red first:** `BuildStatusCommandTest.aWindowsLineEndIsShownAsALineEnd` - a log `"one\r\ntwo\r\n"` is printed as
`one\ntwo\n` with no `\x0d`; beside it the existing test, whose `\r` before a NUL stays `\x0d`.

## Acceptance

- A CRLF log prints without `\x0d`; a CR not followed by LF prints as `\x0d`; `--json` still holds the `\r`.
