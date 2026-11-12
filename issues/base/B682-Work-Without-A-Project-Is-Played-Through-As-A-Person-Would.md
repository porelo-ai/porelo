# B682 — Work Without A Project Is Played Through As A Person Would

```json
{
  "$schema": "issue-1.json",
  "id": "B682",
  "title": "Work Without A Project Is Played Through As A Person Would",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 31,
  "blockedBy": [],
  "covers": "A script plays ST30 at the command line on the test VM as a person would: a task starts in a checkout in `default`; a followed project naming the repository is offered to take it; a repository with no commit, an unreadable upstream and a failed fetch are each named as what they are, with the next step."
}
```

**Why this size.** one script beside the acceptance tests and one run on the VM with its findings filed.

Filed 2026-11-12 for ST347 (every story played through once, as a user would), for the story ST30 (work without a project, with plain refusals) in
porelo-project's stories.

**What must be true.** The story's test is played once on the test VM by a script that does what a person would at
the command line: a task starts in a checkout in `default`; a followed project naming the repository is offered to take it; a repository with no commit, an unreadable upstream and a failed fetch are each named as what they are, with the next step. Its issues here: [B33](README.md#b33), [B355](README.md#b355), [B457](README.md#b457), [B470](README.md#b470), [B594](README.md#b594), [B595](README.md#b595); PJ14 and the frontend's issues are not played here.

## Plan

- `acceptance/stories/st30.sh`: make a plain checkout and start a task in it, follow a project naming its repository and start again, then an empty repository, one whose upstream cannot be read and one whose fetch fails, reading each answer. Each step says what it does and what it read; the script ends
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
