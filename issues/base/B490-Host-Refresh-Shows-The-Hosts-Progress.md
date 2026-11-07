# [B490](README.md#b490) — Host Refresh Shows The Host's Progress

**Status:** Review; built on the operator's word that every finding of the trial move is fixed before the second
move.
**Size:** S - one command and its test; the host's words are passed on, not changed.

**What must be true.** `porelo org host refresh <host>` says at once that it asks the host to follow the organization,
and then shows each line the host writes while it follows, as it comes, so a person sees a follow of many repositories
move instead of minutes of nothing. No line that holds the credential handed over is shown.

## Found

- **Seen during the trial move.** A refresh that took every repository of porelo showed nothing for several minutes
  and then everything at once.
- `OrgHostRefreshCommand.send` runs `porelo-host-person org refresh` over the pinned ssh with
  `runner().run(command)`, which collects the host's standard error and prints it only after the host has finished.
  The runner already offers `run(command, lines)`, which hands over each line of standard error as it comes.

## Plan

- `send` prints one line first, `asking <host> to follow the organization; this takes a while for many repositories`,
  on standard error, and runs the ssh command with `run(command, lines)`.
- Each line is shown on standard error as it comes, prefixed `<host>: `, cleaned as the final diagnostic is
  (`RegistryText.printable`); a line holding the token, raw or cleaned, is replaced by the same withheld sentence the
  diagnostic uses, so no line shows the credential.
- With `--json`, standard output stays the one JSON answer; the lines go to standard error only.
- The final diagnostic is not printed a second time when its lines were already shown.
- **Red first:** a test whose runner hands two lines through the consumer before it returns, one of them holding the
  token, asserts that both reached standard error before the run returned, the second as the withheld sentence, and
  that the token is in neither stream.

## Acceptance

- The unit test above fails on today's command and passes after.
- The existing tests of `OrgHostRefreshCommandTest` that hold the token out of every answer still pass.
