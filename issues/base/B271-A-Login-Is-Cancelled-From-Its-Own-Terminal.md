# B271 — A Login Is Cancelled From Its Own Terminal

**Status:** Idea.

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

## To be decided in the analysis

- Porelo's own way out: a key sequence Porelo reads before the container does (podman's `--detach-keys` with a stop on
  detach), a bound on the login's time, or both.
- That an ended login removes its container and its temporary directory, after a signal too.
