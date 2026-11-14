# B778 — A Start Refused Before Any Container Leaves No Start Behind

```json
{
  "$schema": "issue-1.json",
  "id": "B778",
  "title": "A Start Refused Before Any Container Leaves No Start Behind",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 135,
  "blockedBy": [],
  "covers": "A task start that is refused before any container is made, as by the secret scan, ends its reservation, so the project can be removed and the name started again without a task removal first",
  "openQuestions": 0
}
```

**Why this size.** S: where the start's reservation ends on a refusal before the container, and its test. Estimate:
38 minutes (Core's median for S).

**What must be true.** A start that refused leaves nothing that counts as a start in progress.

Filed 2026-11-14 under ST361, from the gathered VM run of [B776](README.md#b776) and [B777](README.md#b777).

## Why

The key scenario's start is refused by the secret scan with exit 65, and no container is made. Afterwards
`porelo project remove guarded --force` refused with "task keyed has an unfinished start; retry it, or remove it with
'porelo task remove porelo-guarded_keyed'" (`TaskReservations.projectBusy`). So the kit's removal at the end of the
run left the project and its mirror behind, and every later run in that account started from that mirror. A person
whose start was refused for a key meets the same: the project cannot be removed until a task that never existed is.

## Plan

1. Find where a start refused before the container keeps its reservation (`RESERVED`, or claimed with no container).
2. End the reservation on every refusal before the container is made; a start cut off midway stays recoverable.

## Built

- `TaskReservations.Lease.withdraw()`: ends the lease's claim (retired, as a removal leaves it) when this lease made
  the claim itself, it is still reserved, holds no id and no container is under the name. An attempt taken back from
  an earlier start that died is never withdrawn: what that start may have made stays protected.
- `TaskLaunch`: the start marks itself as making things from its enrolment in the project's conversations on, the
  first thing it makes. A start that ends before that withdraws its claim before it lets it go.
- `doc/commands.md` on unfinished starts; changelog `refused-start-leaves-no-start.yml`.
- Red first: `TaskRunCommandTest.aStartRefusedBeforeItMadeAnythingLeavesNoUnfinishedStart`, a credential refusal,
  failed against the old code with the claim left unfinished. `TaskReservationsTest` pins both sides of `withdraw`.
- Reading (Sonnet sub-agent, one reading): no Red. Fixed: a withdrawn lease lets go under the same lock, so its
  own close cannot meet another start's claim on the freed name; a withdraw that fails leaves the claim unfinished
  and the refusal still answers. Left: the test drives a credential refusal (exit 69), not the secret scan's (65),
  which refuses on the same side of the enrolment; a start refused after enrolment still leaves an unfinished start.
- The key scenario's line removing its unfinished start can go once this is on the VM; that is
  [B779](README.md#b779)'s scenario change.

## Red-first test

- A start refused by the secret scan: afterwards `projectBusy` answers null and the same name starts again.
- A start interrupted after its reservation (no refusal) still counts as unfinished.

## Risks

- Ending a reservation another start of the same name holds: only the refusing start's own claim may go.

## Blockers

None.

## Acceptance

- The red-first tests pass. On the VM, the key scenario leaves no task to remove; its removal line can go.

## Open questions

None.
