# B302 — A Broker Stopped By A Signal Leaves No Socket

**Status:** Review: built and tested here, set to Backlog by the operator on 2026-10-27.
**Size:** XS - one line in each of four helpers and a test that sends SIGTERM to a real one.

**What must be true.** A helper beside a task that serves a unix socket - the credential broker `vault serve`, the ssh
agent `vault agent`, the clearance watcher and the builds helper's reader - removes it when SIGTERM or SIGINT ends
it, as it does when it returns, so nothing of it is left in the runtime directory.

## Why

Measured by porelo-codex on 2026-10-27, in an unpaid rehearsal of the ChatGPT run against `.339`: `vault serve
--socket <run>/vault.sock.real ... --seconds 30`, sent SIGTERM to its own pid and waited for, exited and left
`vault.sock.real` behind; a connect answered ECONNREFUSED and no broker process remained. No SIGKILL was sent.

Read in porelo at 7fd7b199: `VaultProxy.close()` deletes the socket, but `VaultServeCommand` reaches it only through
try-with-resources around the serving thread's `join`, which a signal skips; nothing registers a shutdown hook. The
next bind deletes a stale socket first (`VaultProxy`, before `createDirectories`), so a restart copes, and the kernel
clears the runtime directory at the session's end. What is left is a dead socket in between, which a person or a test
reading the directory takes for a live one.

## Built

- **Which helpers, read in the code:** every one that serves a unix socket closes it only through try-with-resources,
  which a signal skips: `vault serve` (`VaultProxy`), `vault agent` (`SshAgentServer`), the clearance watcher
  `shield watch` (`ClearanceService`), and the builds helper `task watch-builds`, whose reader process serves
  `builds.sock` and is ended, and its socket removed, only by `InstalledBuildReader.close()`. Each of those `close()`
  methods already deletes the socket. The gate binds TCP, not a file, and the daemon already closes its server in a
  shutdown hook.
- **The fix:** each of the four arms `Teardown.arm(<server>::close)` beside the server, so the same `close()` runs from
  the shutdown hook on a signal; on return it runs twice, through the teardown and the server's own slot, which each
  `close()` takes as done.
- **Measured:** a native image built with the workflow's GraalVM runs its shutdown hooks on SIGTERM (exit 143) and
  SIGINT (exit 130), so the hook reaches the packaged binary too.
- **Red first:** `HelperSocketOnSignalTest` starts `vault agent --ephemeral` as a real process, sends SIGTERM and finds
  its socket: red before, green after. `vault serve`, the watcher and the builds helper take the same line; a signal
  test of each would need an open vault, root for NFLOG or an installed reader, and has not been written.
