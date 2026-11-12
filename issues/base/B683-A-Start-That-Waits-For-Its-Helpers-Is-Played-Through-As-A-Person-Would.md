# B683 — A Start That Waits For Its Helpers Is Played Through As A Person Would

```json
{
  "$schema": "issue-1.json",
  "id": "B683",
  "title": "A Start That Waits For Its Helpers Is Played Through As A Person Would",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 32,
  "blockedBy": [],
  "covers": "A script plays ST53 at the command line on the test VM as a person would: a start waits until its gate and relay are both up; a start whose gate or relay does not come up ends with its code; two starts of one name never stop each other's task."
}
```

**Why this size.** one script beside the acceptance tests and one run on the VM with its findings filed.

Filed 2026-11-12 for ST347 (every story played through once, as a user would), for the story ST53 (a start that waits for its helpers) in
porelo-project's stories.

**What must be true.** The story's test is played once on the test VM by a script that does what a person would at
the command line: a start waits until its gate and relay are both up; a start whose gate or relay does not come up ends with its code; two starts of one name never stop each other's task. Its issues here: [B384](README.md#b384).

## Plan

- `acceptance/stories/st53.sh`: start a task and read its steps; make the gate and then the relay fail to come up (a blocked port or a stopped helper) and read the code; start two tasks of one name at once and see neither stops the other. Each step says what it does and what it read; the script ends
  with the list of what held and what did not.
- One run on the VM through the coordinator, by the rules below; product faults found become Backlog issues, script
  fixes one commit, then one new run.

## Rules for a story test (operator, 2026-11-12)

- A test script is test tooling, not production code: no tests for the script, no red-first, no review loops (one
  Sonnet reading at most). The script is proven by its run on the VM.
- On the VM a failing script is fixed on the spot and the run carries on; every fault found, in the script or in the
  product, is collected, not fixed one by one. After the run: product faults become estimated Backlog issues, script
  fixes go into one commit, then one new run.
- One VM run at a time on this host, through the coordinator: asked before it starts, posted when it ends. No second
  VM, no Molecule beside it; cleaned up after.
- A story whose issues are not all in Review yet still gets its script and one run; the run names the missing issue
  as an expected failure.

## Acceptance

- The script's run on the VM shows every point of the story's test holding, or names each that does not with the
  issue it waits on.
