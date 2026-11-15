# B779 — A Fixture Made Again Gets a Mirror of Its Own

```json
{
  "$schema": "issue-1.json",
  "id": "B779",
  "title": "A Fixture Made Again Gets a Mirror of Its Own",
  "state": "Backlog",
  "size": "S",
  "priority": "Low",
  "rank": 136,
  "blockedBy": [],
  "covers": "The acceptance kit's step that makes a project afresh in a scenario also drops the mirror an earlier scenario left for that project, so no scenario has to remove a mirror by path",
  "openQuestions": 0
}
```

**Why this size.** S: the kit's project step and the two scenarios that remove a mirror by hand. Estimate: 38
minutes (Core's median for S).

**What must be true.** A scenario starts from the project its Background made, not from an earlier scenario's.

## Why

`TerminalSteps.aProjectSaying` removes the checkout and runs `git init` for every scenario, so each scenario's
"guarded" is a history of its own, while the project and its mirror live until the end of the run.
A start from the checkout brings a mirror forward to the checkout only fast-forward, and rightly keeps a mirror that went
another way, so a later scenario's start reads the first scenario's tree. The key scenario removes the mirror by
path before its start; that knowledge belongs in the kit.

## Plan

1. When the step makes a project the run already made, it removes that project with `porelo project remove --force`
   first, which a start refused before any container no longer stops, so its mirror goes with it.
2. Drop the mirror removal from the key scenario.

## Red-first test

Test scripts get no red-first (the operator's rule 5). On the VM: `task-guarded.feature` whole, green, and no
`guarded` project afterwards.

## Risks

- A removal that refuses must fail the step loudly, not leave the old mirror in place.

## Blockers

None.

## Acceptance

- The key scenario passes without removing the mirror.

## Open questions

None.
