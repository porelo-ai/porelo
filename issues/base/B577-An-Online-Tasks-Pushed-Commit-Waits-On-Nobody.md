# [B577](README.md#b577) — An Online Task's Pushed Commit Waits On Nobody

**Status:** Backlog. Filed 2026-11-09 under ST290, from the fourth move.
**Size:** M - the task's waiting reason, what Needs you is told, and whose account a task pushes as.

**What must be true.** In class `online`, a task whose push passed the gate to the host's forge is never shown as
waiting on the person for that commit: no "Waits on you: its commit waits at the gate", nothing in *Needs you* for it,
and what porelo reports agrees with the gate's own "Nothing is waiting". A push a task makes appears on the forge as
the task's, not as the person's account.

## Plan

Read in the code: `TaskInventory` counts what waits at the gate only for a branch under the gate's incoming
namespace, so an online push counts none there; the "waits at the gate" reason comes from the task's own waiting
state (`waitingFor`) and the attention items, which do not ask the gate.

- Find where the "commit waits at the gate" reason and its *Needs you* item are made for an online task, and derive
  both from the gate's waiting list alone.
- The pusher: the gate pushes to the host's forge with the person's lent token ([B547](README.md#b547)); push with the
  task account's own token where the host issued one, else say in the protocol whose account pushes.
- Red first: an online task whose push passed reports no waiting reason and no attention item.

## To be checked

- Whether the push under the person's account is the gate's or the host's forge's doing.
