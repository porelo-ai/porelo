# B271 — A Login Is Cancelled From Its Own Terminal

**Status:** Analyzed.
**Size:** M - `AgentLogin`, `VaultLoginCommand`, the stub agent's login, a terminal key and an acceptance scenario,
and two document rows; no daemon contract.

**What must be true.** A person who started `porelo vault login <agent>` can always end it from that terminal, whatever
the agent's login screen does with the keys: the login container is stopped and removed, nothing is stored, and the
command says so and ends non-zero.

## Why

Measured on the Ubuntu VM on porelo `0.5.1~snapshot.323` with Codex CLI 0.163.0 at its device-code page: Ctrl-C, twice,
printed only `^C`. `porelo vault login codex` and its `podman run` stayed alive, and the login container stayed up until
it was stopped by hand from a second connection.

Read in the code: `AgentLogin` runs the login container with `podman run --interactive --tty`, so the terminal is raw
and Ctrl-C reaches the container's own pseudo-terminal as a byte. An agent whose login screen ignores it leaves the
person no way out from their terminal, and Porelo has none of its own.

## Plan

Read in `AgentLogin.run`, `AgentLogin.sweepLeftoverLogins`, `Teardown`, `VaultLoginCommand`, `Podman.stateOf`, the stub's
`login` in `agents/stub/src/main/resources/agent/stub.yaml`, `TerminalSteps.iPressTheKey` and `agent-login.feature`.

Measured on this computer with podman 5.7.0, `podman run --interactive --tty` in a pty, a shell that ignores `SIGINT`:
Ctrl-C twice printed `^C^C` and the container ran on. With `--detach-keys ctrl-],ctrl-]` and Ctrl-] sent twice,
`podman run` exited 0 at once **and the container was still running**. The default detach keys, not given and sent
in one write, did nothing in the same probe, so Porelo names its own.

1. **Porelo's way out is a detach key it names**: the login's `podman run` carries `--detach-keys ctrl-],ctrl-]`, so
   no `containers.conf` can change or switch it off, and the introduction says "Ctrl-] twice ends the login from
   here".
2. **A detach is a cancellation**: when `podman run` returns, `Podman.stateOf(container)` is asked; still running means
   the person detached. Then the container is stopped and removed, nothing is copied out or stored - even if the agent
   had written a credential the watch had not yet seen - and a new `Outcome.CANCELLED` is returned.
   `VaultLoginCommand` prints `cancelled  the login was ended from this terminal; its container is removed and nothing
   was stored` and exits 130, as an interrupted command does.
3. **No time bound**: a person who walks away closes the terminal, the `SIGHUP` reaches Porelo and its shutdown hook
   removes the container; a bound would end a slow but working login and adds a setting nobody needs today.
4. **What a signal leaves is removed too**: the shutdown hook removes only the container today, while the watch's
   temporary copies of the agent's config directory, its credential among them, are deleted only in `finally`, which
   a `SIGHUP` or `SIGTERM` never reaches. Both temporary directories are made before `Teardown.arm`, and its action
   removes the container and deletes them. `sweepLeftoverLogins` also deletes `porelo-login*` directories in the
   temporary directory that this account owns and no live login holds, for what a `SIGKILL` leaves.
5. **The stub's login behaves as a device-code page**: it ignores `SIGINT` and waits for a line before it writes its
   fixture, so the existing two login scenarios press Enter and a new one can cancel it.
6. **`doc/commands.md`** (`vault login`) and **`doc/credentials.md`**: one sentence each on Ctrl-] twice and that a
   cancelled login stores nothing.

**Red first:** `AgentLoginTest.aLoginDetachedFromItsTerminalStoresNothingAndRemovesItsContainer` - a fake runtime whose
`run` returns 0 while `stateOf` answers running, with a credential in the copied directory: today it is stored; then
`CANCELLED`, the container stopped and removed, the vault unchanged, and `--detach-keys ctrl-],ctrl-]` in the
arguments. Beside it `AgentLoginTest.theTeardownDeletesTheLoginsCopiesAsWellAsItsContainer`, which runs the armed
action alone, as a signal does.

## Acceptance

- In the suite, on the stub at its waiting login: Ctrl-C twice leaves it running; Ctrl-] twice ends `porelo vault
  login` with exit 130 and "cancelled"; afterwards no `porelo-login-*` container exists, `porelo vault list` holds no
  stub credential, and no `porelo-login*` copy of this account is left in `/tmp`.
- A unit test sees each part fail first: the detach stored (red), the copies left after the armed action ran (red).
- A finished login is unchanged: the existing scenarios still store their credential.

## To be checked

- That no shipped agent's login screen binds Ctrl-] (Claude Code, Codex, pi, omp, agy), asked of their agents.
- Whether `Keys.bytes` names Ctrl-] and Ctrl-C, or the terminal step gains them (`TerminalSteps.iPressTheKey`).
- Whether the watch can store a credential between the detach and the state check; if so, the watch is stopped
  first and what it found is discarded with the rest.
