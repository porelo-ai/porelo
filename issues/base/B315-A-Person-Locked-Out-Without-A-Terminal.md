# B315 — A Person Locked Out Without A Terminal

**Status:** Implementing.
**Size:** XS - an acceptance scenario of two commands that exist, run as root's helper runs them.

**What must be true.** `porelo task stop --all` and `porelo vault clear --yes --force`, run in a person's account with
no terminal and in
that order, stop every task and clear the vault, with exit codes a root helper can act on.

## Why

Decided by the operator: porelo-host's
`porelo-host-person` locks a developer dropped from the signed list at
once and purges the account later. It calls these two commands in the person's account.

## Plan

Read in the code on 2026-10-28.

- `task stop --all` (`TaskPanic`) exits 0 when nothing of the account runs any more and 70 naming what survived;
  `vault clear --yes --force` (`VaultClearCommand`) clears a locked vault unread.
- **The plan:** an acceptance scenario that starts a task, then runs both with no terminal and standard input closed,
  and checks the exits, that no container of the account is left, and that the vault is gone. `doc/hosts.md` names
  both among the answers a play reads.

## Acceptance

- The scenario green on the VM; `doc/hosts.md` says it.

## To be checked

- None.
