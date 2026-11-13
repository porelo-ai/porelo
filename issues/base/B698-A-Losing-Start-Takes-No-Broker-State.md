# B698 — A Losing Start Takes No Broker State

```json
{
  "$schema": "issue-1.json",
  "id": "B698",
  "title": "A Losing Start Takes No Broker State",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 52,
  "blockedBy": [],
  "covers": "Of two starts racing for one task name, the one that loses starts no vault broker in the winner's state directory and touches no vault.pid, socket or token of it.",
  "openQuestions": 0
}
```

**Why this size.** The order of a start's steps against the name reservation, a reproduction with two racing starts,
and the move of the broker after the reservation, or into a directory of the start's own.

Filed 2026-11-13 from [B384](README.md#b384): in a name race, the losing start's broker uses the same `vault.pid`,
socket and token file as the winner's, before its own create runs. The name reservation should keep the loser out
before it gets that far; whether it does at that step was not read.

**What must be true.** A start that loses the name to another touches nothing of the winner's helpers.

## Plan

- Read the start's order: where the reservation is taken, and where the broker starts.
- Red first: two starts of one name, the second held between its reservation and its create, see the winner's
  broker files unchanged.
- Fix: the broker starts after the reservation is held, or in a directory of the start's own until it is.

## Acceptance

- Two racing starts leave the winner's broker files as the winner wrote them; a test fails while the loser writes them.

## Built

The reservation kept a racing start out only while the other start held it. The race was in the attempt a later
start of the same name is handed, not in the order of the reservation and the broker:

- `TaskLaunch.launchSteps` takes the reservation (`reserveTask`, `:2872`) long before the broker starts
  (`startVault`, `:3686`), and holds it until the launch ends. A second start whose reservation comes while it is
  held is refused with `ContainerNameTaken` and touches nothing.
- `TaskReservations.start` hands a later start of the same project and task the previous claim's attempt when that
  claim is not retired. So a start whose `startExisting` found no container, while another start was still making it,
  was handed that start's finished attempt (stage CREATED, holder 0) once the other start's detached launch had ended.
  `reconcile` accepted it, since the container was listed under its recorded id. The start went on to `startVault` in
  the other's state directory: it deleted `vault.token`, and its broker wrote `vault.pid` and the socket, before its
  own create failed on the taken name.

The fix:

- `TaskReservations.Lease.made()` (app-base, new): whether the name's container was listed under its recorded id when
  the lease was taken. That is a retried attempt whose recorded id `reconcile` matched to a listed container, or a
  fresh claim verified from a listed container.
- `TaskLaunch.launchSteps` refuses a lease with `made()` with `ContainerNameTaken` (64), before anything of the task is
  touched. The lease is released and the other start's claim keeps its attempt and stage.
- The check sits just before `runner.reserving` (`~:3136`), not at the reservation. Nothing of the task is written
  between the two, and the refusals in between keep their order: a stopped task that `startExisting` hands to the
  launch on a machine whose OpenSSH is too old is still refused for OpenSSH, not for the taken name.
- Test `TaskRunCommandTest.aStartThatLosesTheNameToAMadeTaskTakesNoneOfItsBrokerFiles`: it failed on the build box
  before the change (`buildbox-porelo-8cfe2782d1d0-20261113T180713Z.log`: `vault.pid` rewritten, the socket truncated,
  the token replaced) and passes after it (`buildbox-porelo-1e32e34545aa-20261113T181135Z.log`). The targeted run was
  439 tests across `TaskRunCommandTest`, `TaskReservationsTest`, `StartCredentialEffectsTest`,
  `StartProgressReviewTest`, `StartStepsTest`, `TaskRunLimitTest`, `CredentialOfferTest`, `UpstreamProbeTest` and
  `HostKeyOfferTest`, with 0 skipped.
