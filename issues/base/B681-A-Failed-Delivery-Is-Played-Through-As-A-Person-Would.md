# B681 — A Failed Delivery Is Played Through As A Person Would

```json
{
  "$schema": "issue-1.json",
  "id": "B681",
  "title": "A Failed Delivery Is Played Through As A Person Would",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 30,
  "blockedBy": [],
  "covers": "A script plays ST39 at the command line on the test VM as a person would: the forge stops answering and other delivery refusals are triggered; each answer ends within the configured bound with its own reason, the failure stays visible in `task list`/`task status`, the card names its repository, and a project push the person makes is picked up."
}
```

**Why this size.** one script beside the acceptance tests and one run on the VM with its findings filed.

Filed 2026-11-12 for ST347 (every story played through once, as a user would), for the story ST39 (a failed delivery named and kept visible) in
porelo-project's stories.

**What must be true.** The story's test is played once on the test VM by a script that does what a person would at
the command line: the forge stops answering and other delivery refusals are triggered; each answer ends within the configured bound with its own reason, the failure stays visible in `task list`/`task status`, the card names its repository, and a project push the person makes is picked up. Its issues here: [B179](README.md#b179), [B197](README.md#b197); the frontend's F117 is not played here.

## Plan

- `acceptance/stories/st39.sh`: stop the test forge's service on the VM (and bring it back), deliver from a task, read the answers' exit codes and words against the bound, read the task's status afterwards; push to the project's repository as the person and see it taken. Each step says what it does and what it read; the script ends
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
