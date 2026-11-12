# B685 — The Gathered Findings Are Played Through As A Person Would

```json
{
  "$schema": "issue-1.json",
  "id": "B685",
  "title": "The Gathered Findings Are Played Through As A Person Would",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 34,
  "blockedBy": [],
  "covers": "A script plays ST56 at the command line on the test VM as a person would: the gathered findings' points, porelo's, HO86's and F288's, each hold nothing open; a point that proved larger is an issue of its own."
}
```

**Why this size.** one script beside the acceptance tests and one run on the VM with its findings filed.

Filed 2026-11-12 for ST347 (every story played through once, as a user would), for the story ST56 (the gathered findings worked off) in
porelo-project's stories.

**What must be true.** The story's test is played once on the test VM by a script that does what a person would at
the command line: [B380](README.md#b380)'s, HO86's and F288's points each hold nothing open; a point that proved larger is an issue of its own. Its issues here: [B649](README.md#b649), [B650](README.md#b650), [B651](README.md#b651), [B203](README.md#b203), [B666](README.md#b666); HO86 and F288 are other repositories'.

## Plan

- `acceptance/stories/st56.sh`: for each point of the gathered findings that a person can see at the command line, do what it names and read the answer; a point that is an issue of its own is checked to be filed. Each step says what it does and what it read; the script ends
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
