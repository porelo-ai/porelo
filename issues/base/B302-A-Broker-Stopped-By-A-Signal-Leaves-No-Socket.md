# B302 — A Broker Stopped By A Signal Leaves No Socket

**Status:** Idea.

**What must be true.** `porelo vault serve`, the credential broker beside a task, removes its unix socket when it is
ended by SIGTERM or SIGINT, as it does when it returns, so nothing of it is left in the runtime directory.

## Why

Measured by porelo-codex on 2026-10-27, in an unpaid rehearsal of the ChatGPT run against `.339`: `vault serve
--socket <run>/vault.sock.real ... --seconds 30`, sent SIGTERM to its own pid and waited for, exited and left
`vault.sock.real` behind; a connect answered ECONNREFUSED and no broker process remained. No SIGKILL was sent.

Read in porelo at 7fd7b199: `VaultProxy.close()` deletes the socket, but `VaultServeCommand` reaches it only through
try-with-resources around the serving thread's `join`, which a signal skips; nothing registers a shutdown hook. The
next bind deletes a stale socket first (`VaultProxy`, before `createDirectories`), so a restart copes, and the kernel
clears the runtime directory at the session's end. What is left is a dead socket in between, which a person or a test
reading the directory takes for a live one.

## To be checked

- Whether `Teardown.arm` (already used by `task run` and `AgentLogin`) is the whole fix: registered around the proxy,
  it closes it, and so deletes the socket, on SIGTERM and SIGINT, with a test that sends SIGTERM to a started
  `vault serve` and finds the socket gone.
- Which other helper of a task binds a socket the same way and ends the same way (the gate, the builds helper, the
  clearance watcher).
