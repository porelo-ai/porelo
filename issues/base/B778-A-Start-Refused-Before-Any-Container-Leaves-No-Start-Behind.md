# B778 — A Start Refused Before Any Container Leaves No Start Behind

```json
{
  "$schema": "issue-1.json",
  "id": "B778",
  "title": "A Start Refused Before Any Container Leaves No Start Behind",
  "state": "Backlog",
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
