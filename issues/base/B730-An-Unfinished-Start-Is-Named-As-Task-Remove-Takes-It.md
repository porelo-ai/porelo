# B730 — An Unfinished Start Is Named As Task Remove Takes It

```json
{
  "$schema": "issue-1.json",
  "id": "B730",
  "title": "An Unfinished Start Is Named As Task Remove Takes It",
  "state": "Implementing",
  "size": "S",
  "priority": "Medium",
  "rank": 57,
  "blockedBy": [],
  "covers": "A start refused before its container exists leaves no claim that `project clear` or `project remove` refuses for, or one `porelo task remove` finds by the name the refusal gives."
}
```

**Why this size.** `TaskReservations.projectBusy` and the refusal's words, `TaskRemoveCommand`'s lookup of a pending
removal by the task's short name, and tests; or the start's own clean-up of a claim it leaves when refused.

Filed from the VM run of [B237](README.md#b237) on ubuntu, smith2, with the packaged `porelo` of that
morning. Checked against main at c1dade058, after [B674](README.md#b674): it retires a removed task's name, and
`task remove` finishes a pending removal by the container's name, but neither covers this case.

**What must be true.** A person told "task cnt has an unfinished start; retry it or remove that task first" can do
what it says. Today:

- `porelo task start -p cnt -r cnt` was refused at the gate ("starting branch 'main' is not in the mirror") and
  said "nothing was created", but left the claim of `porelo-cnt_cnt` in stage `RESERVED`.
- `porelo project clear cnt --yes` and `project remove cnt` then refused with the words above.
- `porelo task remove cnt --yes --force` answered "there are no tasks on this machine": `TaskRemoveCommand` takes a
  pending removal only by the container's name, `porelo-cnt_cnt`, which the refusal does not name.
- Only starting `cnt` again, successfully, and removing it cleared the project.

## Acceptance

- Red first: a start refused before its container exists leaves the project clearable, or the refusal names a
  removal that `task remove` takes and that clears it.

## Estimate

S, est=27 (p80 63) by the median velocity. There are no open questions.
